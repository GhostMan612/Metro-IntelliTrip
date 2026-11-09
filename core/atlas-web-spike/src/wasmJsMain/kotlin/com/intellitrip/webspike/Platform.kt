package com.intellitrip.webspike

import com.intellitrip.map.CartoBasemapProvider
import com.intellitrip.map.web.WebMapRenderer
import com.intellitrip.map.web.snapshotToSources
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

actual fun platformNow(): Instant = Clock.System.now()

private fun loadMapLibreGl(): Boolean =
    js(
        """(() => {
        const scripts = document.getElementsByTagName('script');
        for (const s of scripts) {
            if (s.src.includes('maplibre-gl')) return true;
        }
        // Vendored MapLibre GL dist (5.24.0, same-origin): the build copies
        // src/wasmJsResources next to the bundle (vendorMapLibreDev/Prod).
        // No CDN, no network for the map library itself.
        const css = document.createElement('link');
        css.rel = 'stylesheet';
        css.href = 'maplibre-gl.css';
        document.head.appendChild(css);
        const el = document.createElement('script');
        el.src = 'maplibre-gl.js';
        document.head.appendChild(el);
        return true;
    })()"""
    )

private fun hasMapLibre(): Boolean =
    js("""typeof maplibregl !== 'undefined'""")

private fun createShell(): kotlin.js.JsAny =
    js(
        """(() => {
        const body = document.body;
        body.textContent = 'Metro IntelliTrip — web shell (spike)';
        const bar = document.createElement('div');
        bar.setAttribute('style', 'display:flex;gap:8px;margin:8px 0;font-family:system-ui');
        const origins = ['S1', 'S2', 'S3'];
        const mkSelect = (id, first) => {
            const sel = document.createElement('select');
            sel.setAttribute('id', id);
            for (const o of origins) {
                const opt = document.createElement('option');
                opt.value = o; opt.textContent = o;
                if (o === first) opt.selected = true;
                sel.appendChild(opt);
            }
            return sel;
        };
        const originSel = mkSelect('atlas-origin', 'S1');
        const destSel = mkSelect('atlas-dest', 'S3');
        const btn = document.createElement('button');
        btn.textContent = 'Plan';
        const results = document.createElement('div');
        results.setAttribute('id', 'atlas-results');
        results.setAttribute('style', 'font-family:system-ui;max-height:20vh;overflow:auto');
        bar.appendChild(originSel);
        bar.appendChild(destSel);
        bar.appendChild(btn);
        const mapDiv = document.createElement('div');
        mapDiv.setAttribute('id', 'atlas-map');
        mapDiv.setAttribute('style', 'width:100%;height:60vh;border:1px solid #999;');
        const caption = document.createElement('div');
        caption.setAttribute('id', 'atlas-caption');
        caption.setAttribute('style', 'margin-top:8px;font-family:system-ui');
        body.appendChild(bar);
        body.appendChild(results);
        body.appendChild(mapDiv);
        body.appendChild(caption);
        window.atlasPlanClicked = () => {
            const origin = document.getElementById('atlas-origin').value;
            const dest = document.getElementById('atlas-dest').value;
            document.getElementById('atlas-results').textContent =
                'Planning ' + origin + ' -> ' + dest + ' (demo network)…';
            atlasPlan(origin, dest);
        };
        btn.onclick = window.atlasPlanClicked;
        return caption;
    })()"""
    )

private fun setResults(text: String): kotlin.js.JsAny =
    js("""(() => {
        const box = document.getElementById('atlas-results');
        box.textContent = '';
        const li = document.createElement('div');
        li.textContent = text;
        box.appendChild(li);
        return box;
    })()""")

@OptIn(kotlin.js.ExperimentalJsExport::class)
@kotlin.js.JsExport
fun atlasPlan(origin: String, destination: String) {
    MainScope().launch {
        val verdict = WebDemo.demoPlanBetween(WebDemo.stopPoint(origin), WebDemo.stopPoint(destination))
        setResults(origin + " -> " + destination + ": " + verdict)
    }
}

private fun setCaption(caption: kotlin.js.JsAny, text: String): kotlin.js.JsAny =
    js("""(caption.textContent = text, caption)""")

fun main() {
    val caption = createShell()

    loadMapLibreGl()

    MainScope().launch {
        val verdict = WebDemo.demoPlanText()
        setCaption(caption, "Demo network loaded. " + verdict + " — loading map…")

        // MapLibre loads async from the CDN; wait for the global before mounting.
        var tries = 0
        while (!hasMapLibre() && tries < 30) {
            kotlinx.coroutines.delay(200L)
            tries++
        }
        check(hasMapLibre()) { "maplibregl did not load from CDN (offline?)" }
        setCaption(caption, "Demo network loaded. " + verdict + " — rendered below.")

        val renderer = WebMapRenderer()
        renderer.mount("atlas-map")
        renderer.setStyle(CartoBasemapProvider.style(dark = false))
        // Demo radar overlay (RainViewer past frame). Remove to go back to
        // a bare basemap; providers supply a live RadarFrame in the real shell.
        renderer.setRadarOverlay(WebDemo.demoRadarOverlay())

        // 200 ms glide loop mirroring Android's: the demo vehicle walks
        // S1 -> S3 over ~10 s; sources refresh, camera never recenters after
        // the first frame (the renderer owns that rule).
        var progress = 0.0
        while (true) {
            val snapshot = WebDemo.demoSnapshot(progress = progress)
            renderer.setSources(
                snapshotToSources(snapshot),
                centerLon = -93.26,
                centerLat = 44.98,
                zoom = 15.0,
            )
            progress += 0.02
            if (progress >= 1.0) progress = 0.0
            kotlinx.coroutines.delay(200L)
        }
    }
    println(describeNow())
}
