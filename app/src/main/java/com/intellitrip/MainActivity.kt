package com.intellitrip

import android.os.Bundle
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.material3.MaterialTheme
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView

private const val CARTO_STYLE = "https://basemaps.cartocdn.com/gl/positron-gl-style/style.json"

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
                                map.setStyle(CARTO_STYLE)
                                map.cameraPosition = CameraPosition.Builder()
                                    .target(LatLng(44.9778, -93.2650))
                                    .zoom(11.0)
                                    .build()
                            }
                        }
                        mapView
                    },
                    modifier = Modifier.fillMaxSize()
                )
                DisposableEffect(mapView) {
                    onDispose {
                        mapView.onDestroy()
                    }
                }
            }
        }
    }
}
