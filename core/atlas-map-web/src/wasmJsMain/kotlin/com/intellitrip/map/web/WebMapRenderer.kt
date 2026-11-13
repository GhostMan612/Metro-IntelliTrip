package com.intellitrip.map.web

import com.intellitrip.map.BasemapStyle
import kotlin.js.JsAny

/**
 * Browser renderer: MapLibre GL JS behind JS interop.
 *
 * The host page must load MapLibre GL JS (global `maplibregl`) and its CSS
 * before mounting (the web shell does both at startup). No npm/webpack
 * dependency: the Kotlin side talks to the global, so bundling stays
 * dependency-free. Each [WebLayer] maps to one GeoJSON source + one layer,
 * mirroring the Android bulk-update pipeline.
 *
 * MapLibre is asynchronous in a way that is easy to get wrong: `setStyle`
 * replaces the style object, and any source or layer added before the style
 * finishes loading is lost or throws. The JS bridge therefore owns a small
 * lifecycle queue. Sources, radar and the first camera move are recorded,
 * applied when `style.load`/`load` fires, and replayed after every restyle.
 * Kotlin never assumes that a call did anything; it just records intent.
 *
 * A first render centers the camera on the supplied fix; later renders only
 * refresh sources (so we never fight a user's pan).
 */
class WebMapRenderer : WebRenderer {

    private var map: JsAny? = null
    private var containerId: String? = null
    private var firstRender = true

    override fun mount(containerId: String) {
        this.containerId = containerId
    }

    override fun setStyle(style: BasemapStyle) {
        val url = webStyleUrl(style)
            ?: error("Web renderer needs a remote basemap style URL; bundled styleAssetPath is Android-only")
        val cid = containerId ?: error("WebMapRenderer.mount() must be called before setStyle()")
        val existing = map
        map = if (existing != null) {
            restyle(existing, url)
        } else {
            atlasInitLifecycle(createMap(cid, url))
        }
    }

    override fun setSources(payloads: List<SourcePayload>, centerLon: Double, centerLat: Double, zoom: Double) {
        val current = map ?: error("WebMapRenderer.setStyle() must be called before setSources()")
        completeSources(payloads).forEach { payload ->
            queueSource(
                map = current,
                sourceId = payload.layer.name,
                geoJson = payload.geoJson,
                isLine = payload.layer == WebLayer.SHAPES,
            )
        }
        if (firstRender) {
            queueCamera(current, centerLon, centerLat, zoom)
            firstRender = false
        }
    }

    override fun setRadarOverlay(overlay: RadarOverlay?) {
        val current = map ?: error("WebMapRenderer.setStyle() must be called before setRadarOverlay()")
        queueRadar(current, overlay?.tileUrlTemplate, overlay?.opacity?.toDouble() ?: 0.0, overlay != null)
    }

    override fun destroy() {
        map?.let { current -> destroyMap(current) }
        map = null
        firstRender = true
    }
}

/*
 * The JS below is intentionally one self-contained runtime per map. Kotlin/
 * wasm cannot cheaply hand Kotlin lambdas to MapLibre event emitters, so the
 * queue lives on the map object (`__atlas*` fields) and the Kotlin methods
 * only enqueue. `flush` is idempotent: it applies the latest value per source
 * and leaves nothing behind on a style that is not ready.
 */

private fun createMap(containerId: String, styleUrl: String): JsAny =
    js(
        """new maplibregl.Map({
            container: document.getElementById(containerId),
            style: styleUrl,
            center: [-93.265, 44.977],
            zoom: 12
        })"""
    )

private fun restyle(map: JsAny, styleUrl: String): JsAny =
    js(
        """(() => {
        const m = map;
        m.__atlasStyleReady = false;
        // setStyle drops every source and layer; replay the last payloads when
        // the replacement style is usable.
        m.__atlasNeedReplay = true;
        m.__atlasRadarDirty = true;
        m.setStyle(styleUrl);
        return m;
    })()"""
    )

private fun atlasInitLifecycle(map: JsAny): JsAny =
    js(
        """(() => {
        const m = map;
        // Diagnostic hook for the browser-host receipt. The renderer itself
        // never reads this; it lets a headless check inspect the live map
        // without parsing canvas pixels.
        globalThis.__atlasMap = m;
        m.__atlasLastSources = {};
        m.__atlasPendingSources = {};
        m.__atlasStyleReady = false;
        m.__atlasNeedReplay = false;
        m.__atlasRadarDirty = false;
        m.__atlasLastRadar = null;
        m.__atlasPendingCamera = null;

        const applySource = (sourceId, payload) => {
            const geo = JSON.parse(payload.geoJson);
            const src = m.getSource(sourceId);
            if (src) {
                src.setData(geo);
                return;
            }
            m.addSource(sourceId, { type: 'geojson', data: geo });
            if (payload.isLine) {
                m.addLayer({
                    id: sourceId + '-line',
                    type: 'line',
                    source: sourceId,
                    paint: { 'line-width': 4, 'line-color': '#3a6fd8', 'line-opacity': 0.9 }
                });
            } else {
                m.addLayer({
                    id: sourceId,
                    type: 'circle',
                    source: sourceId,
                    paint: {
                        'circle-radius': 6,
                        'circle-color': '#7aa2ff',
                        'circle-stroke-width': 1,
                        'circle-stroke-color': '#ffffff'
                    }
                });
            }
        };

        const applyRadar = (overlay) => {
            if (!overlay) {
                if (m.getLayer('radar-layer')) m.removeLayer('radar-layer');
                if (m.getSource('radar')) m.removeSource('radar');
                return;
            }
            const style = m.getStyle();
            const sourceSpec = style && style.sources && style.sources.radar;
            if (m.getLayer('radar-layer') && sourceSpec &&
                sourceSpec.tiles && sourceSpec.tiles[0] === overlay.tileUrl) {
                m.setPaintProperty('radar-layer', 'raster-opacity', overlay.opacity);
                return;
            }
            if (m.getLayer('radar-layer')) m.removeLayer('radar-layer');
            if (m.getSource('radar')) m.removeSource('radar');
            m.addSource('radar', { type: 'raster', tiles: [overlay.tileUrl], tileSize: 256 });
            m.addLayer({
                id: 'radar-layer',
                type: 'raster',
                source: 'radar',
                paint: { 'raster-opacity': overlay.opacity }
            });
        };

        m.__atlasFlush = () => {
            if (!m.__atlasStyleReady) return;
            if (m.__atlasNeedReplay) {
                for (const id of Object.keys(m.__atlasLastSources)) {
                    applySource(id, m.__atlasLastSources[id]);
                }
                m.__atlasNeedReplay = false;
            }
            for (const id of Object.keys(m.__atlasPendingSources)) {
                applySource(id, m.__atlasPendingSources[id]);
            }
            m.__atlasPendingSources = {};
            if (m.__atlasRadarDirty) {
                applyRadar(m.__atlasLastRadar);
                m.__atlasRadarDirty = false;
            }
            if (m.__atlasPendingCamera) {
                m.easeTo(m.__atlasPendingCamera);
                m.__atlasPendingCamera = null;
            }
        };

        // `style.load` is the reliable point at which addSource/addLayer are
        // accepted. `load` covers the initial style; `styledata` handles
        // dynamic restyles that do not emit a second `load`.
        m.on('style.load', () => { m.__atlasStyleReady = true; m.__atlasFlush(); });
        m.on('load', () => { m.__atlasStyleReady = true; m.__atlasFlush(); });
        m.on('styledata', () => { if (m.__atlasStyleReady) m.__atlasFlush(); });
        return m;
    })()"""
    )

private fun queueSource(map: JsAny, sourceId: String, geoJson: String, isLine: Boolean): JsAny =
    js(
        """(() => {
        const m = map;
        const payload = { geoJson: geoJson, isLine: isLine };
        const previous = m.__atlasLastSources[sourceId];
        if (previous && previous.geoJson === geoJson && previous.isLine === isLine) return m;
        m.__atlasLastSources[sourceId] = payload;
        m.__atlasPendingSources[sourceId] = payload;
        m.__atlasFlush();
        return m;
    })()"""
    )

private fun queueCamera(map: JsAny, lon: Double, lat: Double, zoom: Double): JsAny =
    js(
        """(() => {
        const m = map;
        m.__atlasPendingCamera = { center: [lon, lat], zoom: zoom };
        m.__atlasFlush();
        return m;
    })()"""
    )

private fun queueRadar(
    map: JsAny,
    tileUrl: String?,
    opacity: Double,
    present: Boolean,
): JsAny =
    js(
        """(() => {
        const m = map;
        const next = present ? { tileUrl: tileUrl, opacity: opacity } : null;
        const previous = m.__atlasLastRadar;
        if (!m.__atlasRadarDirty && previous && next &&
            previous.tileUrl === next.tileUrl && previous.opacity === next.opacity) return m;
        if (!m.__atlasRadarDirty && !previous && !next) return m;
        m.__atlasLastRadar = next;
        m.__atlasRadarDirty = true;
        m.__atlasFlush();
        return m;
    })()"""
    )

private fun destroyMap(map: JsAny): JsAny =
    js(
        """(() => {
        const m = map;
        m.__atlasPendingSources = {};
        m.__atlasLastSources = {};
        m.__atlasPendingCamera = null;
        m.__atlasLastRadar = null;
        m.__atlasRadarDirty = false;
        m.remove();
        return m;
    })()"""
    )
