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
import androidx.compose.ui.viewinterop.AndroidView
import com.intellitrip.AppState
import com.intellitrip.domain.GeoPoint
import com.intellitrip.domain.LatLngBounds
import com.intellitrip.map.CameraView
import com.intellitrip.map.CartoBasemapProvider
import com.intellitrip.map.RenderInput
import com.intellitrip.map.android.MapLibreMapRenderer
import com.intellitrip.weather.WeatherLayer
import com.intellitrip.weather.WeatherLayerComposer
import com.intellitrip.weather.WeatherLayerState
import java.time.Instant
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
    onViewportChanged: (lat: Double, lon: Double, zoom: Double) -> Unit = { _, _, _ -> },
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var renderer by remember { mutableStateOf<MapLibreMapRenderer?>(null) }

    val mapView = remember {
        MapView(context).apply {
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            )
            onCreate(null)
        }
    }

    AndroidView(
        factory = {
            MapLibreMapRenderer(mapView, onCameraMoved = onViewportChanged).also { created ->
                renderer = created
                created.applyBasemap(CartoBasemapProvider.style())
                created.moveCamera(com.intellitrip.DEFAULT_LAT, com.intellitrip.DEFAULT_LON, 11.0)
            }
            mapView
        },
        modifier = Modifier.fillMaxSize(),
    )

    DisposableEffect(Unit) { onDispose { mapView.onDestroy() } }

    LaunchedEffect(renderer, state.vehicles, state.staticFeed, state.scope) {
        val active = renderer ?: return@LaunchedEffect
        if (!throttle.shouldEmit()) return@LaunchedEffect
        val feed = state.staticFeed
        // Rendering and GeoJSON encoding can touch hundreds of thousands of points,
        // so it runs off the main thread before the renderer is touched.
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
                    snapshotAt = Instant.now(),
                )
            )
        }
        active.render(snapshot)
    }

    LaunchedEffect(renderer, state.scope) {
        val active = renderer ?: return@LaunchedEffect
        val camera = state.scope?.cameraScope ?: return@LaunchedEffect
        val bounds = camera.bounds
        active.moveCamera(
            bounds?.let { (it.southWest.lat + it.northEast.lat) / 2 } ?: com.intellitrip.DEFAULT_LAT,
            bounds?.let { (it.southWest.lon + it.northEast.lon) / 2 } ?: com.intellitrip.DEFAULT_LON,
            camera.zoom ?: 11.0,
        )
    }

    LaunchedEffect(renderer, state.radarFrame, state.radarEnabled, state.weatherAlerts) {
        val active = renderer ?: return@LaunchedEffect
        val composed = WeatherLayerComposer.compose(
            state = WeatherLayerState(
                radarEnabled = state.radarEnabled,
                alertsEnabled = state.weatherAlerts.isNotEmpty(),
            ),
            radarFrame = state.radarFrame,
            alerts = state.weatherAlerts,
        )
        composed.layers.forEach { (_, layer) ->
            when (layer) {
                is WeatherLayer.RadarTiles -> active.applyRadar(layer.frame.tileUrlTemplate, layer.opacity.toFloat())
                // NWS alerts are areas, not points; they surface in the status panel
                // rather than as fabricated markers.
                is WeatherLayer.AlertMarkers -> Unit
            }
        }
        if (composed.layers.isEmpty() && (state.radarEnabled || state.weatherAlerts.isNotEmpty())) {
            active.clearWeatherLayers()
        }
    }
}

private fun fallbackBounds(): LatLngBounds = LatLngBounds(
    GeoPoint(com.intellitrip.DEFAULT_LAT - 0.25, com.intellitrip.DEFAULT_LON - 0.35),
    GeoPoint(com.intellitrip.DEFAULT_LAT + 0.25, com.intellitrip.DEFAULT_LON + 0.35),
)