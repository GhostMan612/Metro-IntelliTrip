package com.intellitrip

import android.os.Bundle
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import com.intellitrip.domain.GeoPoint
import com.intellitrip.domain.LatLngBounds
import com.intellitrip.domain.Vehicle
import com.intellitrip.domain.VehicleType
import com.intellitrip.gtfsrt.PollingGtfsRealtimeClient
import com.intellitrip.gtfsrt.UrlGtfsRealtimeFetcher
import com.intellitrip.map.CameraView
import com.intellitrip.map.CartoBasemapProvider
import com.intellitrip.map.RenderInput
import com.intellitrip.map.RenderUpdateThrottle
import com.intellitrip.map.TransitRenderPipeline
import com.intellitrip.map.android.MapLibreMapRenderer
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.maplibre.android.maps.MapView

private const val FEED_ID = "metro-transit-regional"
private const val DEFAULT_LAT = 44.9778
private const val DEFAULT_LON = -93.2650

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
    var renderer by remember { mutableStateOf<MapLibreMapRenderer?>(null) }

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

    LaunchedEffect(renderer, vehicles) {
        val active = renderer ?: return@LaunchedEffect
        if (vehicles.isEmpty()) return@LaunchedEffect
        if (!throttle.shouldEmit()) return@LaunchedEffect
        val camera = active.camera()
        active.render(
            pipeline.render(
                RenderInput(
                    camera = CameraView(
                        bounds = camera?.let {
                            LatLngBounds(
                                GeoPoint(it.southWestLat, it.southWestLon),
                                GeoPoint(it.northEastLat, it.northEastLon),
                            )
                        } ?: fallbackBounds(),
                        zoom = camera?.zoom ?: 11.0,
                    ),
                    vehicles = vehicles,
                    stops = emptyList(),
                    shapes = emptyList(),
                    snapshotAt = Instant.now(),
                )
            )
        )
    }

    Surface(color = Color.Transparent, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
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
            }) { Text("Load live transit") }

            Text(text = status, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private fun fallbackBounds(): LatLngBounds =
    LatLngBounds(GeoPoint(DEFAULT_LAT - 0.25, DEFAULT_LON - 0.35), GeoPoint(DEFAULT_LAT + 0.25, DEFAULT_LON + 0.35))

private fun describe(result: ProviderResult<*>): String = when (result) {
    is ProviderResult.NetworkFailure -> "${result.error.message} (${result.error.cause?.javaClass?.simpleName})"
    is ProviderResult.MalformedResponse -> "${result.error.message} [${result.error.cause?.javaClass?.name}]"
    is ProviderResult.Unavailable -> result.error.message ?: "provider unavailable"
    is ProviderResult.RateLimited -> "rate limited"
    is ProviderResult.AuthFailure -> result.error.message ?: "authentication failure"
    is ProviderResult.Timeout -> result.error.message ?: "timeout"
    else -> "unexpected ${result::class.simpleName}: ${(result as ProviderResult.MalformedResponse).error.cause?.javaClass?.name}"
}
