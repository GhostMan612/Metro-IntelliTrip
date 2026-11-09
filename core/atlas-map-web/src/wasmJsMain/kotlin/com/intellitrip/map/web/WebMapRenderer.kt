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
        val cid = containerId ?: error("WebMapRenderer.mount() must be called before setStyle()")
        if (url == null) error("Web renderer needs a remote basemap style URL; bundled styleAssetPath is Android-only")
        val existing = map
        map = if (existing != null) {
            restyle(existing, url)
        } else {
            createMap(cid, url)
        }
    }

    override fun setSources(payloads: List<SourcePayload>, centerLon: Double, centerLat: Double, zoom: Double) {
        val current = map ?: error("WebMapRenderer.setStyle() must be called before setSources()")
        payloads.forEach { payload ->
            applySource(current, payload.layer.name, payload.geoJson, payload.layer == WebLayer.SHAPES)
        }
        if (firstRender) {
            centerMap(current, centerLon, centerLat, zoom)
            firstRender = false
        }
    }

    override fun destroy() {
        map?.let { current -> destroyMap(current) }
        map = null
    }
}

private fun createMap(containerId: String, styleUrl: String): JsAny =
    js("""maplibregl.Map({ container: document.getElementById(containerId), style: styleUrl, center: [-93.265, 44.977], zoom: 12 })""")

private fun restyle(map: JsAny, styleUrl: String): JsAny =
    js("""(map.setStyle(styleUrl), map)""")

private fun centerMap(map: JsAny, lon: Double, lat: Double, zoom: Double): JsAny =
    js("""(map.easeTo({ center: [lon, lat], zoom: zoom }), map)""")

private fun destroyMap(map: JsAny): JsAny =
    js("""(map.remove(), map)""")

private fun applySource(map: JsAny, sourceId: String, geoJson: String, isLine: Boolean): JsAny =
    js("""(() => {
        const m = map;
        const geo = JSON.parse(geoJson);
        const src = m.getSource(sourceId);
        if (src) {
            src.setData(geo);
        } else {
            m.addSource(sourceId, { type: 'geojson', data: geo });
            if (isLine) {
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
                    paint: { 'circle-radius': 6, 'circle-color': '#7aa2ff', 'circle-stroke-width': 1, 'circle-stroke-color': '#ffffff' }
                });
            }
        }
        return m;
    })()""")
