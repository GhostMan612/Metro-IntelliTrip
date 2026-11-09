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
        const el = document.createElement('script');
        el.src = 'https://unpkg.com/maplibre-gl@5.0.4/dist/maplibre-gl.js';
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
        const mapDiv = document.createElement('div');
        mapDiv.setAttribute('id', 'atlas-map');
        mapDiv.setAttribute('style', 'width:100%;height:70vh;border:1px solid #999;');
        const caption = document.createElement('div');
        caption.setAttribute('id', 'atlas-caption');
        caption.setAttribute('style', 'margin-top:8px;font-family:system-ui');
        body.appendChild(mapDiv);
        body.appendChild(caption);
        return caption;
    })()"""
    )

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
        val snapshot = WebDemo.demoSnapshot()
        renderer.setSources(
            snapshotToSources(snapshot),
            centerLon = -93.26,
            centerLat = 44.98,
            zoom = 15.0,
        )
    }
    println(describeNow())
}
