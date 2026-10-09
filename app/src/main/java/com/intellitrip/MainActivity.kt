package com.intellitrip

import android.os.Bundle
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.intellitrip.contracts.ProviderResult
import com.intellitrip.domain.FeedId
import com.intellitrip.domain.Focus
import com.intellitrip.domain.GeoPoint
import com.intellitrip.domain.LatLngBounds
import com.intellitrip.domain.RadarFrame
import com.intellitrip.domain.Vehicle
import com.intellitrip.domain.VehicleType
import com.intellitrip.domain.WeatherAlert
import com.intellitrip.gtfsrt.PollingGtfsRealtimeClient
import com.intellitrip.gtfsrt.RealtimeScopeFilter
import com.intellitrip.gtfsrt.UrlGtfsRealtimeFetcher
import com.intellitrip.scope.ScopeEngine
import com.intellitrip.map.CameraView
import com.intellitrip.map.CartoBasemapProvider
import com.intellitrip.map.RenderInput
import com.intellitrip.map.RenderUpdateThrottle
import com.intellitrip.map.TransitRenderPipeline
import com.intellitrip.map.android.MapLibreMapRenderer
import com.intellitrip.weather.NwsWeatherAlertProvider
import com.intellitrip.weather.RainViewerRadarProvider
import com.intellitrip.weather.WeatherHttpClient
import com.intellitrip.weather.WeatherLayer
import com.intellitrip.weather.WeatherLayerComposer
import com.intellitrip.weather.WeatherLayerState
import com.intellitrip.weather.WeatherRequestContext
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.maplibre.android.maps.MapView

private const val FEED_ID = "metro-transit-regional"
private const val DEFAULT_LAT = 44.9778
private const val DEFAULT_LON = -93.2650
private const val USER_AGENT =
    "IntelliTrip/0.1 (https://github.com/GhostMan612/Metro-IntelliTrip)"

private val REALTIME_URLS = PollingGtfsRealtimeClient.RealtimeFeedUrls(
    vehiclePositions = "https://svc.metrotransit.org/mtgtfs/vehiclepositions.pb",
    tripUpdates = "https://svc.metrotransit.org/mtgtfs/tripupdates.pb",
    alerts = "https://svc.metrotransit.org/mtgtfs/alerts.pb",
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // MapLibre must be initialized before any MapView is created. This is a
        // local renderer setup step only: it performs no network activity.
        org.maplibre.android.MapLibre.getInstance(applicationContext)
        setContent {
            MaterialTheme {
                IntelliTripMapScreen()
            }
        }
    }
}

/**
 * Phase 03 shell: a live map with bulk-rendered transit layers. Startup performs
 * no network activity; realtime data is fetched only when the user asks for it.
 */
@Composable
private fun IntelliTripMapScreen() {
    val context = LocalContext.current
    var status by remember { mutableStateOf("Idle — no network activity at startup") }
    var vehicles by remember { mutableStateOf<List<Vehicle>>(emptyList()) }
    var radarFrame by remember { mutableStateOf<RadarFrame?>(null) }
    var radarEnabled by remember { mutableStateOf(false) }
    var weatherAlerts by remember { mutableStateOf<List<WeatherAlert>>(emptyList()) }
    var scopeLabel by remember { mutableStateOf("System") }
    var focus by remember { mutableStateOf<Focus?>(Focus.System) }
    var renderer by remember { mutableStateOf<MapLibreMapRenderer?>(null) }

    val scopeEngine = remember { ScopeEngine() }
    val scopeState = scopeEngine.currentScope(focus)

    val pipeline = remember { TransitRenderPipeline() }
    val throttle = remember { RenderUpdateThrottle() }
    val scope = rememberCoroutineScope()

    val mapView = remember {
        MapView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            onCreate(null)
        }
    }

    AndroidView(
        factory = {
            MapLibreMapRenderer(mapView).also { created ->
                renderer = created
                created.applyBasemap(CartoBasemapProvider.style())
                created.moveCamera(DEFAULT_LAT, DEFAULT_LON, 11.0)
            }
            mapView
        },
        modifier = Modifier.fillMaxSize(),
    )

    DisposableEffect(Unit) {
        onDispose { mapView.onDestroy() }
    }

    LaunchedEffect(renderer, vehicles, scopeState) {
        val active = renderer ?: return@LaunchedEffect
        if (!throttle.shouldEmit()) return@LaunchedEffect
        val dataScope = scopeState.dataScope
        val visible = RealtimeScopeFilter.filter(vehicles, dataScope)
        val cameraBounds = dataScope.bounds ?: fallbackBounds()
        active.render(
            pipeline.render(
                RenderInput(
                    camera = CameraView(bounds = cameraBounds, zoom = scopeState.cameraScope.zoom ?: 11.0),
                    vehicles = visible,
                    stops = emptyList(),
                    shapes = emptyList(),
                    snapshotAt = Instant.now(),
                )
            )
        )
    }

    LaunchedEffect(renderer, focus) {
        val active = renderer ?: return@LaunchedEffect
        val camera = scopeState.cameraScope
        active.moveCamera(
            camera.bounds?.let { (it.southWest.lat + it.northEast.lat) / 2 } ?: DEFAULT_LAT,
            camera.bounds?.let { (it.southWest.lon + it.northEast.lon) / 2 } ?: DEFAULT_LON,
            camera.zoom ?: 11.0,
        )
    }

    LaunchedEffect(renderer, radarFrame, radarEnabled, weatherAlerts) {
        val active = renderer ?: return@LaunchedEffect
        val composed = WeatherLayerComposer.compose(
            state = WeatherLayerState(radarEnabled = radarEnabled, alertsEnabled = weatherAlerts.isNotEmpty()),
            radarFrame = radarFrame,
            alerts = weatherAlerts,
        )
        composed.layers.forEach { (slot, layer) ->
            when (layer) {
                is WeatherLayer.RadarTiles -> active.applyRadar(
                    tileUrlTemplate = layer.frame.tileUrlTemplate,
                    opacity = layer.opacity.toFloat(),
                )

                is WeatherLayer.AlertMarkers -> {
                    // NWS alerts describe areas, not points. Rather than inventing
                    // coordinates, alert text is surfaced in the status line and
                    // no marker layer is drawn.
                    status = "Weather alerts: ${layer.alerts.size} (${layer.alerts.first().event})"
                }
            }
        }
        if (composed.layers.isEmpty() && (radarEnabled || weatherAlerts.isNotEmpty())) {
            active.clearWeatherLayers()
        }
    }

    Surface(color = Color.Transparent, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ScopeButton("Nearby") {
                    scopeLabel = "Nearby (½ mile)"
                    focus = Focus.Radius(GeoPoint(DEFAULT_LAT, DEFAULT_LON), 805.0)
                }
                ScopeButton("Area") {
                    scopeLabel = "Area (2 miles)"
                    focus = Focus.Radius(GeoPoint(DEFAULT_LAT, DEFAULT_LON), 3218.0)
                }
                ScopeButton("System") {
                    scopeLabel = "System"
                    focus = Focus.System
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    status = "Loading live transit…"
                    scope.launch {
                        val client = PollingGtfsRealtimeClient(
                            feedId = FeedId(FEED_ID),
                            urls = REALTIME_URLS,
                            fetcher = UrlGtfsRealtimeFetcher(),
                        )
                        when (val result = client.vehiclePositions().first()) {
                            is ProviderResult.Success -> {
                                vehicles = result.data
                                status = "Live vehicles: ${result.data.size} (LOD ${result.freshness.age.seconds}s)"
                            }

                            is ProviderResult.StaleSuccess -> {
                                vehicles = result.data
                                status = "Stale live vehicles: ${result.data.size}"
                            }

                            else -> status = "Live transit unavailable: ${describe(result)}"
                        }
                    }
                }) { Text("Live transit") }

                Button(onClick = {
                    status = "Loading radar…"
                    scope.launch {
                        val provider = RainViewerRadarProvider(
                            http = WeatherHttpClient.urlConnection(),
                            context = WeatherRequestContext("rainviewer", USER_AGENT),
                        )
                        when (val result = provider.latestFrame()) {
                            is ProviderResult.Success -> {
                                radarFrame = result.data
                                radarEnabled = result.data != null
                                status = result.data?.let { "Radar frame ${it.timestamp} (${it.kind})" }
                                    ?: "No radar frame available"
                            }

                            else -> {
                                radarFrame = null
                                radarEnabled = false
                                renderer?.clearWeatherLayers()
                                status = "Radar unavailable: ${describe(result)}"
                            }
                        }
                    }
                }) { Text(if (radarEnabled) "Radar on" else "Radar") }

                Button(onClick = {
                    status = "Loading weather alerts…"
                    scope.launch {
                        val provider = NwsWeatherAlertProvider(
                            http = WeatherHttpClient.urlConnection(),
                            context = WeatherRequestContext("nws", USER_AGENT),
                        )
                        when (val result = provider.alerts().first()) {
                            is ProviderResult.Success -> {
                                weatherAlerts = result.data
                                status = "Weather alerts: ${result.data.size}"
                            }

                            else -> {
                                weatherAlerts = emptyList()
                                status = "Weather alerts unavailable: ${describe(result)}"
                            }
                        }
                    }
                }) { Text("Alerts") }
            }

            Text(
                text = "$scopeLabel · ${scopeState.renderScope.zoomBucket} · " +
                    "${scopeState.renderScope.maxRenderedVehicles} vehicle budget",
                style = MaterialTheme.typography.labelMedium,
            )

            Text(text = status, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private fun fallbackBounds(): LatLngBounds =
    LatLngBounds(GeoPoint(DEFAULT_LAT - 0.25, DEFAULT_LON - 0.35), GeoPoint(DEFAULT_LAT + 0.25, DEFAULT_LON + 0.35))

@Composable
private fun ScopeButton(label: String, onClick: () -> Unit) {
    Button(onClick = onClick) { Text(label) }
}

private fun describe(result: ProviderResult<*>): String = when (result) {
    is ProviderResult.NetworkFailure -> "${result.error.message} [${result.error.cause?.javaClass?.simpleName}]"
    is ProviderResult.MalformedResponse -> "${result.error.message} [${result.error.cause?.javaClass?.name}]"
    is ProviderResult.Unavailable -> result.error.message ?: "provider unavailable"
    is ProviderResult.RateLimited -> "rate limited"
    is ProviderResult.AuthFailure -> result.error.message ?: "authentication failure"
    is ProviderResult.Timeout -> result.error.message ?: "timeout"
    else -> "unexpected ${result::class.simpleName}: ${(result as ProviderResult.MalformedResponse).error.cause?.javaClass?.name}"
}
