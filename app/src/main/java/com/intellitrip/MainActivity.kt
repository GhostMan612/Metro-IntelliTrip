package com.intellitrip

import android.os.Bundle
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import com.intellitrip.transit.TransitRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

private const val CARTO_STYLE = "https://basemaps.cartocdn.com/gl/positron-gl-style/style.json"
private const val SOURCE_ID = "vehicles"
private const val LAYER_ID = "vehicle-dots"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(applicationContext)
        setContent {
            MaterialTheme {
                val mapView = remember {
                    MapView(applicationContext).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        onCreate(null)
                    }
                }
                AndroidView(
                    factory = {
                        mapView.apply {
                            getMapAsync { map ->
                                map.setStyle(CARTO_STYLE) { style ->
                                    style.addSource(GeoJsonSource(SOURCE_ID))
                                    style.addLayer(
                                        CircleLayer(LAYER_ID, SOURCE_ID).withProperties(
                                            PropertyFactory.circleRadius(5f),
                                            PropertyFactory.circleColor("#0078D4"),
                                            PropertyFactory.circleStrokeWidth(1.5f),
                                            PropertyFactory.circleStrokeColor("#FFFFFF"),
                                        )
                                    )
                                }
                                map.cameraPosition = CameraPosition.Builder()
                                    .target(LatLng(44.9778, -93.2650))
                                    .zoom(11.0)
                                    .build()
                                lifecycleScope.launch {
                                    while (isActive) {
                                        runCatching {
                                            TransitRepository.vehicleLocations("901")
                                        }.onSuccess { vehicles ->
                                            val features = vehicles.map { v ->
                                                Feature.fromGeometry(Point.fromLngLat(v.lon, v.lat))
                                            }
                                            map.getStyle { style ->
                                                style.getSourceAs<GeoJsonSource>(SOURCE_ID)
                                                    ?.setGeoJson(FeatureCollection.fromFeatures(features))
                                            }
                                        }
                                        delay(15_000)
                                    }
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
                DisposableEffect(mapView) {
                    onDispose { mapView.onDestroy() }
                }
            }
        }
    }
}
