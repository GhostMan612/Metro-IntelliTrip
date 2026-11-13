package com.intellitrip.webspike

import com.intellitrip.contracts.ProviderResult
import com.intellitrip.domain.GeoPoint
import com.intellitrip.domain.ServiceAlert
import com.intellitrip.domain.Stop
import com.intellitrip.domain.Vehicle
import com.intellitrip.engine.AtlasEngine
import com.intellitrip.gtfs.GtfsFeedAcquirer
import com.intellitrip.gtfs.PlatformFileStore
import com.intellitrip.gtfs.RefreshOutcome
import com.intellitrip.gtfs.StaticFeed
import com.intellitrip.gtfs.StaticFeedRefresher
import com.intellitrip.gtfs.StaticFeedSnapshotStore
import com.intellitrip.gtfsrt.PollingGtfsRealtimeClient
import com.intellitrip.gtfsrt.UrlGtfsRealtimeFetcher
import com.intellitrip.map.CartoBasemapProvider
import com.intellitrip.map.ShapeLineRender
import com.intellitrip.map.web.WebMapRenderer
import com.intellitrip.map.web.snapshotToSources
import kotlin.js.JsAny
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

actual fun platformNow(): Instant = Clock.System.now()

// Kotlin/Wasm has a single UI thread, and no platform Main dispatcher is
// installed by kotlinx-coroutines 1.9, so MainScope() fails at module init.
// A supervisor scope on the default event-loop dispatcher keeps one failed
// render/realtime coroutine from tearing down the whole shell. It is lazy so
// a coroutine-runtime problem becomes a visible shell error, not a blank page.
private val scope: CoroutineScope by lazy { CoroutineScope(SupervisorJob()) }
private var isDemoMode = false
private var production: ProductionSession? = null

/**
 * One live-feed session. The object is reused across background refreshes so
 * the realtime collectors and render loop keep pointing at the same state;
 * only [feed], [engine], [stops] and [shapeLines] are swapped atomically
 * after a successful refresh.
 */
private class ProductionSession(
    val store: StaticFeedSnapshotStore,
    val refresher: StaticFeedRefresher,
    var feed: StaticFeed,
    var engine: AtlasEngine,
    var stops: List<Stop>,
    var anchor: GeoPoint,
    var shapeLines: List<ShapeLineRender>,
    var fix: GeoPoint? = null,
    var vehicles: List<Vehicle> = emptyList(),
    var alerts: List<ServiceAlert> = emptyList(),
)

private fun loadMapLibreGl(): Boolean =
    js(
        """(() => {
        const scripts = document.getElementsByTagName('script');
        for (const s of scripts) {
            if (s.src.includes('maplibre-gl')) return true;
        }
        // Vendored MapLibre GL dist (same origin): the build copies
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

private fun hasMapLibre(): Boolean = js("""typeof maplibregl !== 'undefined'""")

private fun pageSearch(): String = js("window.location.search")

private fun createShell(isDemo: Boolean): JsAny =
    js(
        """(() => {
        const body = document.body;
        body.textContent = '';
        body.setAttribute('style', 'margin:0;font-family:system-ui,sans-serif;background:#0f1115;color:#e8edf5');
        body.style.visibility = 'visible';
        window.dispatchEvent(new Event('atlas-ready'));
        const header = document.createElement('div');
        header.setAttribute('style', 'padding:10px 14px;display:flex;gap:12px;align-items:center;font-size:18px;font-weight:600');
        const title = document.createElement('span');
        title.textContent = 'Metro IntelliTrip';
        const mode = document.createElement('span');
        mode.textContent = isDemo ? 'demo (?demo=1)' : 'live Metro Transit feed';
        mode.setAttribute('style', 'font-size:12px;font-weight:500;opacity:.7');
        const live = document.createElement('span');
        live.id = 'atlas-live';
        live.textContent = 'Connecting…';
        live.setAttribute('style', 'margin-left:auto;font-size:12px;font-weight:500;opacity:.8');
        header.append(title, mode, live);

        const bar = document.createElement('div');
        bar.setAttribute('style', 'display:flex;gap:8px;padding:0 14px 10px;flex-wrap:wrap;align-items:center');
        const label = (text) => {
            const element = document.createElement('span');
            element.textContent = text;
            element.setAttribute('style', 'font-size:12px;opacity:.7');
            return element;
        };
        const mkSelect = (id) => {
            const select = document.createElement('select');
            select.id = id;
            select.disabled = true;
            select.setAttribute('style', 'min-width:220px;padding:5px');
            return select;
        };
        const origin = mkSelect('atlas-origin');
        const destination = mkSelect('atlas-dest');
        const plan = document.createElement('button');
        plan.id = 'atlas-plan';
        plan.textContent = 'Plan';
        plan.disabled = true;
        const locate = document.createElement('button');
        locate.id = 'atlas-locate';
        locate.textContent = 'Use my location';
        if (isDemo) locate.style.display = 'none';
        const refresh = document.createElement('button');
        refresh.id = 'atlas-refresh';
        refresh.textContent = 'Refresh feed';
        if (isDemo) refresh.style.display = 'none';
        bar.append(label('From'), origin, label('To'), destination, plan, locate, refresh);

        const planResult = document.createElement('div');
        planResult.id = 'atlas-plan-result';
        planResult.setAttribute('style', 'padding:0 14px 6px;font-size:13px;min-height:18px');
        const alerts = document.createElement('div');
        alerts.id = 'atlas-alerts';
        alerts.setAttribute('style', 'padding:0 14px 8px;font-size:12px;opacity:.85;white-space:pre-line;max-height:8vh;overflow:auto');
        const map = document.createElement('div');
        map.id = 'atlas-map';
        map.setAttribute('style', 'width:100%;height:62vh');
        const caption = document.createElement('div');
        caption.id = 'atlas-caption';
        caption.setAttribute('style', 'padding:8px 14px;font-size:12px;opacity:.85');
        body.append(header, bar, planResult, alerts, map, caption);

        // `@JsExport` functions live on the webpack UMD export object, not on
        // window. The UMD entry is async, so the global is a promise until the
        // wasm module finishes initializing. Resolve it at click time instead
        // of relying on a global `atlasPlan` identifier.
        const invoke = (name, ...args) => {
            const bridge = globalThis['atlas-web-spike'];
            if (!bridge) return Promise.reject(new Error('the Atlas module is not loaded'));
            return Promise.resolve(bridge).then((api) => {
                if (!api || typeof api[name] !== 'function') {
                    throw new Error('the Atlas API is missing ' + name);
                }
                return api[name](...args);
            });
        };
        const report = (error) => {
            const element = document.getElementById('atlas-caption');
            if (!element) return;
            element.textContent = 'Action failed: ' + (error && error.message ? error.message : error);
            element.style.color = '#ff9a9a';
        };
        plan.onclick = () => { invoke('atlasPlan', origin.value, destination.value).catch(report); };
        locate.onclick = () => { invoke('atlasLocate').catch(report); };
        refresh.onclick = () => { invoke('atlasRefresh').catch(report); };
        return caption;
    })()"""
    )

private fun setStatus(text: String, error: Boolean = false): Unit =
    js(
        """(() => {
        const element = document.getElementById('atlas-caption');
        element.textContent = text;
        element.style.color = error ? '#ff9a9a' : '#9fb3c8';
        return element;
    })()"""
    )

private fun setLive(text: String, error: Boolean = false): Unit =
    js(
        """(() => {
        const element = document.getElementById('atlas-live');
        element.textContent = text;
        element.style.color = error ? '#ff9a9a' : '#9fe3b1';
        return element;
    })()"""
    )

private fun setPlanText(text: String): Unit =
    js(
        """(() => {
        const element = document.getElementById('atlas-plan-result');
        element.textContent = text;
        return element;
    })()"""
    )

private fun setAlertsText(text: String): Unit =
    js(
        """(() => {
        const element = document.getElementById('atlas-alerts');
        element.textContent = text;
        return element;
    })()"""
    )

private fun setStops(json: String): Unit =
    js(
        """(() => {
        const items = JSON.parse(json);
        for (const id of ['atlas-origin', 'atlas-dest']) {
            const select = document.getElementById(id);
            select.textContent = '';
            for (const item of items) {
                const option = document.createElement('option');
                option.value = item.id;
                option.textContent = item.label;
                select.appendChild(option);
            }
            select.disabled = items.length === 0;
        }
        if (items.length > 1) {
            document.getElementById('atlas-dest').selectedIndex = Math.min(5, items.length - 1);
        }
        document.getElementById('atlas-plan').disabled = items.length < 2;
        return null;
    })()"""
    )

private fun requestLocation(): JsAny =
    js(
        """(() => {
        window.__atlasFix = null;
        window.__atlasFixError = null;
        if (!navigator.geolocation) {
            window.__atlasFixError = 'Geolocation is not available in this browser.';
            return null;
        }
        navigator.geolocation.getCurrentPosition(
            p => { window.__atlasFix = { lat: p.coords.latitude, lon: p.coords.longitude }; },
            e => { window.__atlasFixError = e.message || 'Location permission denied.'; },
            { enableHighAccuracy: false, timeout: 10000, maximumAge: 60000 }
        );
        return null;
    })()"""
    )

private fun hasFix(): Boolean = js("!!window.__atlasFix")
private fun fixLat(): Double = js("window.__atlasFix.lat")
private fun fixLon(): Double = js("window.__atlasFix.lon")
private fun fixError(): String? = js("window.__atlasFixError || null")

private fun jsonEscape(value: String): String = buildString {
    for (ch in value) {
        when (ch) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (ch.code < 0x20) {
                append("\\u")
                append(ch.code.toString(16).padStart(4, '0'))
            } else {
                append(ch)
            }
        }
    }
}

private fun stopsJson(stops: List<Stop>): String =
    stops.joinToString(prefix = "[", postfix = "]") { stop ->
        val code = stop.code?.takeIf { it.isNotBlank() }
        val label = if (code == null) stop.name else "${stop.name} · $code"
        """{"id":"${jsonEscape(stop.key.stopId)}","label":"${jsonEscape(label)}"}"""
    }

private suspend fun waitForMapLibre() {
    var tries = 0
    while (!hasMapLibre() && tries < 50) {
        delay(200L)
        tries++
    }
    check(hasMapLibre()) {
        "MapLibre GL did not load. Serve maplibre-gl.js next to the shell " +
            "with the vendorMapLibreDev/vendorMapLibreProd Gradle tasks."
    }
}

private suspend fun boot() {
    waitForMapLibre()
    val renderer = WebMapRenderer()
    renderer.mount("atlas-map")
    renderer.setStyle(CartoBasemapProvider.style(dark = false))
    if (isDemoMode) runDemo(renderer) else runProduction(renderer)
}

private suspend fun runDemo(renderer: WebMapRenderer) {
    setStatus("Synthetic demo network (?demo=1). This is not Metro Transit data.")
    setStops(stopsJson(WebDemo.demoNetwork().stops))
    setAlertsText("Service alerts are disabled in demo mode.")
    setLive("Demo")
    // No radar overlay: RainViewer licensing is unresolved, its tiles do not
    // cover the demo's zoom level, and a fabricated frame would paint broken
    // placeholder tiles over the map. The renderer keeps the API for a future
    // licensed provider; the browser shell does not pretend to have weather.

    var progress = 0.0
    while (true) {
        val snapshot = WebDemo.demoSnapshot(progress = progress)
        val center = snapshot.camera.bounds.center()
        renderer.setSources(
            snapshotToSources(snapshot),
            centerLon = center.lon,
            centerLat = center.lat,
            zoom = snapshot.camera.zoom,
        )
        progress += 0.02
        if (progress >= 1.0) progress = 0.0
        delay(200L)
    }
}

private suspend fun runProduction(renderer: WebMapRenderer) {
    val store = StaticFeedSnapshotStore(PlatformFileStore("atlas-web"))
    val refresher = StaticFeedRefresher(
        feedId = WebProduction.feedId,
        sourceUrl = WebProduction.STATIC_GTFS_URL,
        acquirer = GtfsFeedAcquirer(),
        store = store,
    )

    val cached = try {
        refresher.rehydrateCached()
    } catch (_: Exception) {
        null
    }

    var session: ProductionSession? = null
    if (cached != null) {
        session = newSession(store, refresher, cached.feed)
        val age = cached.age.inWholeMinutes
        val stale = if (cached.stale) ", stale" else ""
        setStatus("Cached feed (${age} min old$stale). Checking for updates…")
    } else {
        setStatus("Downloading Metro Transit GTFS. The first load is a full ZIP…")
        when (val outcome = refresher.refresh()) {
            is RefreshOutcome.Activated -> {
                session = newSession(store, refresher, outcome.feed)
                setStatus("Live feed loaded · ${WebProduction.metadataLine(outcome.feed)}")
            }

            is RefreshOutcome.NotModified -> {
                showFatal("The feed reported 304 Not Modified, but no validated snapshot exists.")
                return
            }

            is RefreshOutcome.Rejected -> {
                showFatal("Metro Transit feed rejected: ${outcome.reason}")
                return
            }
        }
    }

    val active = session ?: return
    production = active
    setStops(stopsJson(active.stops))
    setAlertsText("Loading service alerts…")
    setLive("Live feed connecting…")

    // Cached data is shown immediately, then revalidated with ETag/Last-Modified.
    if (cached != null) {
        scope.launch { refreshInBackground(active, announce = true) }
    }
    startRealtime(active)
    renderLoop(renderer, active)
}

private fun newSession(
    store: StaticFeedSnapshotStore,
    refresher: StaticFeedRefresher,
    feed: StaticFeed,
): ProductionSession {
    require(feed.routes.isNotEmpty()) { "the feed contains no routes" }
    require(feed.stops.isNotEmpty()) { "the feed contains no stops" }
    require(feed.trips.isNotEmpty()) { "the feed contains no trips" }
    val network = WebProduction.networkOf(feed, store)
    val anchor = WebProduction.anchorFor(feed)
    return ProductionSession(
        store = store,
        refresher = refresher,
        feed = feed,
        engine = WebProduction.engineFor(network),
        stops = WebProduction.nearbyStops(feed, anchor, 24),
        anchor = anchor,
        shapeLines = WebProduction.shapeLines(feed),
    )
}

private fun applyFeed(session: ProductionSession, feed: StaticFeed) {
    require(feed.routes.isNotEmpty()) { "the refreshed feed contains no routes" }
    require(feed.stops.isNotEmpty()) { "the refreshed feed contains no stops" }
    // Build everything first: a failure must leave the previous session intact
    // rather than half-swapping a feed the planner cannot use.
    val network = WebProduction.networkOf(feed, session.store)
    val engine = WebProduction.engineFor(network)
    val anchor = session.fix ?: WebProduction.anchorFor(feed)
    val stops = WebProduction.nearbyStops(feed, anchor, 24)
    val shapes = WebProduction.shapeLines(feed)

    session.feed = feed
    session.engine = engine
    session.anchor = anchor
    session.stops = stops
    session.shapeLines = shapes
    setStops(stopsJson(stops))
}

private suspend fun refreshInBackground(session: ProductionSession, announce: Boolean) {
    val outcome = try {
        session.refresher.refresh()
    } catch (e: Exception) {
        setStatus("Feed refresh failed: ${e.message}", error = true)
        return
    }
    when (outcome) {
        is RefreshOutcome.Activated -> {
            try {
                applyFeed(session, outcome.feed)
                setAlertsText("Loading service alerts…")
                setStatus("Feed refreshed · ${WebProduction.metadataLine(outcome.feed)}")
            } catch (e: Exception) {
                setStatus("Refreshed feed could not be loaded; keeping the previous one: ${e.message}", error = true)
            }
        }

        is RefreshOutcome.NotModified -> {
            if (announce) {
                setStatus("Feed unchanged · ${WebProduction.metadataLine(session.feed)}")
            }
        }

        is RefreshOutcome.Rejected -> {
            setStatus(
                "Feed refresh rejected; keeping the last validated feed. ${outcome.reason}",
                error = true,
            )
        }
    }
}

private fun alertsText(session: ProductionSession): String =
    if (session.alerts.isEmpty()) {
        "No active service alerts."
    } else {
        WebProduction.alertLines(session.alerts).joinToString("\n")
    }

private fun startRealtime(session: ProductionSession) {
    val client = PollingGtfsRealtimeClient(
        feedId = session.feed.feedId,
        urls = WebProduction.realtimeUrls,
        fetcher = UrlGtfsRealtimeFetcher(),
    )
    scope.launch {
        client.vehiclePositions().collect { result ->
            when (result) {
                is ProviderResult.Success -> {
                    session.vehicles = result.data
                    setLive("Live · ${result.data.size} vehicles")
                }

                is ProviderResult.StaleSuccess -> {
                    session.vehicles = result.data
                    setLive("Live · ${result.data.size} vehicles (stale)", error = true)
                }

                is ProviderResult.MalformedResponse ->
                    setLive("Live vehicle feed malformed; retrying", error = true)

                is ProviderResult.NetworkFailure ->
                    setLive("Live vehicle feed unavailable; retrying", error = true)

                else -> setLive("Live vehicle feed unavailable; retrying", error = true)
            }
        }
    }
    scope.launch {
        client.alerts().collect { result ->
            when (result) {
                is ProviderResult.Success -> {
                    session.alerts = result.data
                    setAlertsText(alertsText(session))
                }

                is ProviderResult.StaleSuccess -> {
                    session.alerts = result.data
                    setAlertsText(alertsText(session))
                }

                else -> setAlertsText("Service alerts unavailable; retrying.")
            }
        }
    }
}

private suspend fun renderLoop(renderer: WebMapRenderer, session: ProductionSession) {
    while (true) {
        // Route shapes are static for a feed and are computed once in
        // `shapeLines`; only vehicles change every tick.
        val snapshot = WebProduction.snapshot(
            feed = session.feed,
            vehicles = session.vehicles,
            now = Clock.System.now(),
        ).copy(shapeLines = session.shapeLines)
        val center = snapshot.camera.bounds.center()
        renderer.setSources(
            snapshotToSources(snapshot),
            centerLon = center.lon,
            centerLat = center.lat,
            zoom = snapshot.camera.zoom,
        )
        delay(1_000L)
    }
}

private fun showFatal(message: String) {
    production = null
    setStatus("Metro IntelliTrip could not start: $message", error = true)
    setPlanText("")
    setAlertsText("")
    setLive("Offline", error = true)
    setStops("[]")
}

@OptIn(kotlin.js.ExperimentalJsExport::class)
@kotlin.js.JsExport
fun atlasPlan(originId: String, destinationId: String) {
    if (isDemoMode) {
        scope.launch {
            setPlanText("Planning the demo network…")
            val text = try {
                WebDemo.demoPlanBetween(WebDemo.stopPoint(originId), WebDemo.stopPoint(destinationId))
            } catch (e: Exception) {
                "Demo planning failed: ${e.message}"
            }
            setPlanText(text)
        }
        return
    }
    val current = production
    if (current == null) {
        setStatus("The Metro Transit feed is not loaded yet.", error = true)
        return
    }
    val origin = WebProduction.stopById(current.feed, originId)
    val destination = WebProduction.stopById(current.feed, destinationId)
    if (origin == null || destination == null) {
        setStatus("Choose two stops from the current feed.", error = true)
        return
    }
    scope.launch {
        setPlanText("Planning…")
        val text = try {
            WebProduction.planText(current.engine, origin.location, destination.location, Clock.System.now())
        } catch (e: Exception) {
            "Planning failed: ${e.message}"
        }
        setPlanText(text)
    }
}

@OptIn(kotlin.js.ExperimentalJsExport::class)
@kotlin.js.JsExport
fun atlasRefresh() {
    if (isDemoMode) return
    val current = production
    if (current == null) {
        setStatus("The Metro Transit feed is not loaded yet.", error = true)
        return
    }
    scope.launch {
        setStatus("Refreshing the static feed…")
        refreshInBackground(current, announce = true)
    }
}

@OptIn(kotlin.js.ExperimentalJsExport::class)
@kotlin.js.JsExport
fun atlasLocate() {
    if (isDemoMode) return
    val current = production
    if (current == null) {
        setStatus("The Metro Transit feed is not loaded yet.", error = true)
        return
    }
    scope.launch {
        setStatus("Requesting your location…")
        requestLocation()
        var waited = 0
        while (waited < 40) {
            delay(250L)
            waited++
            if (hasFix()) {
                val fix = GeoPoint(fixLat(), fixLon())
                current.fix = fix
                current.anchor = fix
                current.stops = WebProduction.nearbyStops(current.feed, fix, 24)
                setStops(stopsJson(current.stops))
                setStatus("Using your location. Nearest stops are listed first.")
                return@launch
            }
            val error = fixError()
            if (error != null) {
                setStatus("Location unavailable: $error", error = true)
                return@launch
            }
        }
        setStatus("Location request timed out; using the feed centre.", error = true)
    }
}

fun main() {
    installPlatformTimeZones()
    isDemoMode = WebProduction.demoRequested(pageSearch())
    createShell(isDemoMode)
    loadMapLibreGl()
    scope.launch {
        try {
            boot()
        } catch (error: Throwable) {
            showFatal(error.message ?: "Unexpected error")
        }
    }
    println(describeNow())
}
