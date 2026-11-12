package com.intellitrip.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.intellitrip.AppState
import com.intellitrip.domain.GeoPoint
import com.intellitrip.domain.LatLngBounds
import com.intellitrip.map.CameraView
import com.intellitrip.map.CartoBasemapProvider
import com.intellitrip.map.GeoJsonWriter
import com.intellitrip.map.RenderInput
import com.intellitrip.map.RouteLine
import com.intellitrip.map.android.MapLibreMapRenderer
import com.intellitrip.weather.WeatherLayer
import com.intellitrip.weather.WeatherLayerComposer
import com.intellitrip.weather.WeatherLayerState
import kotlinx.datetime.Clock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.maplibre.android.maps.MapView

/**
 * Hosts the map and pushes engine snapshots into the renderer. It owns no
 * business logic: it converts [AppState] into render input and applies layers.
 */
@Composable
fun MapCanvas(
    state: AppState,
    throttle: com.intellitrip.map.RenderUpdateThrottle,
    pipeline: com.intellitrip.map.TransitRenderPipeline,
    routeGeometry: List<RouteLine> = emptyList(),
    onViewportChanged: (lat: Double, lon: Double, zoom: Double) -> Unit = { _, _, _ -> },
    /** Reports what was tapped, resolved against the rendered features. */
    onMapTap: (lat: Double, lon: Double) -> com.intellitrip.map.MapSelection =
        { _, _ -> com.intellitrip.map.MapSelection.None },
    /** Publishes the renderer so the host can hit-test against it. */
    onRendererCreated: (MapLibreMapRenderer?) -> Unit = {},
    /**
     * Basemap to draw. Null keeps the CARTO default. An installed offline
     * package supplies a style whose sources point at a local archive.
     */
    basemapStyle: com.intellitrip.map.BasemapStyle? = null,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val isDark = com.intellitrip.ui.theme.LocalIsDark.current
    var renderer by remember { mutableStateOf<MapLibreMapRenderer?>(null) }
    var ready by remember { mutableStateOf(false) }
    // setStyle replaces the whole style, dropping every source and layer. Bumping
    // this after each style load makes the effects below reinstall data instead of
    // assuming the previous style survived.
    var styleGeneration by remember { mutableStateOf(0) }
    val effectiveBasemap = basemapStyle ?: CartoBasemapProvider.style(isDark)

    val mapView = remember {
        MapView(context).apply {
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            )
            onCreate(null)
        }
    }

    // The renderer is created here but is not usable until MapLibre's async map and
    // style are ready. Everything pushed before that point would be dropped, so the
    // Compose side waits for [ready] before rendering anything. Recreated on
    // theme change so the basemap follows light/dark (setStyle resets layers).
    val rendererCreated = remember(isDark) {
        MapLibreMapRenderer(
            mapView = mapView,
            onReady = {
                ready = true
                styleGeneration++
            },
            onCameraMoved = onViewportChanged,
            onMapTapped = { lat, lon -> onMapTap(lat, lon) },
        ).also { created ->
            created.moveCamera(
                com.intellitrip.DEFAULT_LAT,
                com.intellitrip.DEFAULT_LON,
                11.0,
            )
        }
    }

    // One effect so the first composition and a later basemap change follow the
    // same path. Clearing `ready` before setStyle keeps render effects from
    // touching a style whose layers are gone; onReady raises it again.
    LaunchedEffect(rendererCreated, effectiveBasemap) {
        ready = false
        rendererCreated.applyBasemap(effectiveBasemap)
    }

    AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())

    LaunchedEffect(rendererCreated) { renderer = rendererCreated }

    DisposableEffect(rendererCreated) {
        onRendererCreated(rendererCreated)
        onDispose { onRendererCreated(null) }
    }

    /**
     * MapLibre's MapView owns GL resources and must see the same activity lifecycle the
     * activity does. Forwarding only ON_DESTROY leaves the map rendering while the app
     * is backgrounded: on return the surface is often already lost, which presents as a
     * blank or grey tile grid that no amount of state refresh fixes.
     *
     * ON_PAUSE and ON_RESUME are forwarded, and ON_STOP pauses only if a pause has not
     * already been delivered, since MapLibre is not idempotent across paired calls and
     * a backgrounding activity emits both events. ON_DESTROY is left to `onDispose`,
     * which runs exactly once when the composable leaves composition.
     */
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, mapView) {
        var paused = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    if (paused) {
                        paused = false
                        mapView.onResume()
                    }
                }

                Lifecycle.Event.ON_PAUSE -> {
                    if (!paused) {
                        paused = true
                        mapView.onPause()
                    }
                }

                // Backgrounding emits PAUSE then STOP; the second must not pause again.
                Lifecycle.Event.ON_STOP -> Unit

                // Releasing caches on memory pressure is MapLibre's documented behavior
                // for the same path as a pause; a later ON_RESUME rebuilds them.
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onDestroy()
        }
    }

    // `ready` is part of every key: without it the first render fires before layers exist
    // and is thrown away, and nothing ever repaints because no other key changed.
    // Previous snapshot is retained so the pipeline can interpolate between polls
    // at frame cadence (V2 glide) instead of teleporting every 15s.
    var previousVehicles by remember { mutableStateOf<List<com.intellitrip.domain.Vehicle>>(emptyList()) }
    LaunchedEffect(renderer, ready, styleGeneration, state.vehicles, state.staticFeed, state.scope) {
        val active = renderer ?: return@LaunchedEffect
        if (!ready) return@LaunchedEffect
        val feed = state.staticFeed
        val prev = previousVehicles
        val snapshot = withContext(Dispatchers.Default) {
            pipeline.render(
                RenderInput(
                    camera = CameraView(
                        bounds = state.scope?.dataScope?.bounds ?: fallbackBounds(),
                        zoom = state.scope?.cameraScope?.zoom ?: 11.0,
                    ),
                    vehicles = state.vehicles,
                    stops = feed?.stops ?: emptyList(),
                    shapes = feed?.shapes ?: emptyList(),
                    routes = feed?.routes ?: emptyList(),
                    snapshotAt = Clock.System.now(),
                    previousVehicles = prev,
                    // Fetch time, not "now": this is what decides whether a vehicle
                    // paints as stale, and it must match the status line.
                    fetchedAt = state.vehiclesFetchedAt,
                )
            )
        }
        active.render(snapshot)
        previousVehicles = state.vehicles
    }
    // 60fps glide between polls (V2): re-render interpolation without refetch.
    LaunchedEffect(renderer, ready, styleGeneration, state.vehicles, state.scope) {
        val active = renderer ?: return@LaunchedEffect
        if (!ready || state.vehicles.isEmpty()) return@LaunchedEffect
        val feed = state.staticFeed ?: return@LaunchedEffect
        val prev = previousVehicles
        while (true) {
            kotlinx.coroutines.delay(200)
            val now = Clock.System.now()
            val snapshot = withContext(Dispatchers.Default) {
                pipeline.render(
                    RenderInput(
                        camera = CameraView(
                            bounds = state.scope?.dataScope?.bounds ?: fallbackBounds(),
                            zoom = state.scope?.cameraScope?.zoom ?: 11.0,
                        ),
                        vehicles = state.vehicles,
                        stops = feed.stops,
                        shapes = feed.shapes,
                        routes = feed.routes,
                        snapshotAt = now,
                        previousVehicles = prev,
                        fetchedAt = state.vehiclesFetchedAt,
                    )
                )
            }
            if (!throttle.shouldEmit()) continue
            active.render(snapshot)
        }
    }

    // The camera follows scope only after the user changes it. Applying it on the
    // initial composition would snap the view to the System extent on every cold
    // start, when the app already opens on a sensible default framing.
    var cameraFramed by remember { mutableStateOf(false) }
    LaunchedEffect(renderer, ready, styleGeneration, state.scope, state.focus) {
        val active = renderer ?: return@LaunchedEffect
        if (!ready) return@LaunchedEffect
        // A followed vehicle is framed by followTarget below. Running the scope
        // camera too would start two animations to the same place and let the
        // slower one win after the vehicle has already moved on.
        if (state.focus is com.intellitrip.domain.Focus.Vehicle) return@LaunchedEffect
        val camera = state.scope?.cameraScope ?: return@LaunchedEffect
        if (!cameraFramed) {
            cameraFramed = true
            return@LaunchedEffect
        }
        val bounds = camera.bounds
        active.moveCamera(
            bounds?.let { (it.southWest.lat + it.northEast.lat) / 2 } ?: com.intellitrip.DEFAULT_LAT,
            bounds?.let { (it.southWest.lon + it.northEast.lon) / 2 } ?: com.intellitrip.DEFAULT_LON,
            camera.zoom ?: 11.0,
        )
    }

    // The followed vehicle owns the camera while a fresh fix exists. A null
    // target (missing or stale report) deliberately leaves the camera where it
    // is; VehicleFollow has already explained the pause in the UI.
    LaunchedEffect(renderer, ready, styleGeneration, state.followTarget) {
        val active = renderer ?: return@LaunchedEffect
        if (!ready) return@LaunchedEffect
        val target = state.followTarget ?: return@LaunchedEffect
        active.moveCamera(target.lat, target.lon, FOLLOW_VEHICLE_ZOOM)
    }

    LaunchedEffect(renderer, ready, styleGeneration, state.userLocation) {
        val active = renderer ?: return@LaunchedEffect
        if (!ready) return@LaunchedEffect
        // A null location clears the layer; the renderer takes an empty collection
        // rather than needing a separate "clear" call it could forget.
        active.applyUserLocation(
            withContext(Dispatchers.Default) { GeoJsonWriter.userLocation(state.userLocation) }
        )
    }

    LaunchedEffect(renderer, ready, styleGeneration, routeGeometry) {
        val active = renderer ?: return@LaunchedEffect
        if (!ready) return@LaunchedEffect
        if (routeGeometry.isEmpty()) {
            active.clearRoutes()
        } else {
            active.applyRoutes(
                withContext(Dispatchers.Default) { GeoJsonWriter.routes(routeGeometry) }
            )
        }
    }

    LaunchedEffect(renderer, ready, styleGeneration, state.radarFrame, state.radarEnabled, state.weatherAlerts) {
        val active = renderer ?: return@LaunchedEffect
        if (!ready) return@LaunchedEffect
        val composed = WeatherLayerComposer.compose(
            state = WeatherLayerState(
                radarEnabled = state.radarEnabled,
                alertsEnabled = state.weatherAlerts.isNotEmpty(),
            ),
            radarFrame = state.radarFrame,
            alerts = state.weatherAlerts,
        )
        /**
         * Reconciles weather layers against the composed state.
         *
         * Clearing is unconditional and happens first, then the composed layers are
         * re-applied. Two earlier versions were wrong:
         *
         * The first cleared only when a radar or alert toggle was *on*, so switching
         * radar off skipped the clear and the last frame stayed on the map.
         *
         * The second cleared only when the composed set was entirely empty. That fixed
         * the single-layer case but still leaked: switch radar off while an alert is
         * active and the collection is not empty, so nothing is cleared and the radar
         * layer survives the toggle.
         *
         * Clearing everything and rebuilding from the composed set is the only rule
         * that cannot leak a layer that the current state no longer contains. It costs
         * one extra style mutation per change, which is not a concern for a switch a
         * rider taps deliberately.
         */
        active.clearWeatherLayers()
        composed.layers.forEach { (_, layer) ->
            when (layer) {
                is WeatherLayer.RadarTiles ->
                    active.applyRadar(layer.frame.tileUrlTemplate, layer.opacity.toFloat())
                // NWS alerts are areas, not points; they surface in the list and
                // status panel rather than as fabricated markers.
                is WeatherLayer.AlertSummary -> Unit
            }
        }
    }
}

private const val FOLLOW_VEHICLE_ZOOM = 15.0

private fun fallbackBounds(): LatLngBounds = LatLngBounds(
    GeoPoint(com.intellitrip.DEFAULT_LAT - 0.25, com.intellitrip.DEFAULT_LON - 0.35),
    GeoPoint(com.intellitrip.DEFAULT_LAT + 0.25, com.intellitrip.DEFAULT_LON + 0.35),
)