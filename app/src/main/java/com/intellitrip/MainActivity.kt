package com.intellitrip

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.intellitrip.domain.GeoPoint
import com.intellitrip.ui.MapCanvas
import com.intellitrip.ui.MapChrome

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Renderer setup only; performs no network activity.
        org.maplibre.android.MapLibre.getInstance(applicationContext)
        setContent {
            MaterialTheme {
                IntelliTripApp()
            }
        }
    }
}

@Composable
private fun IntelliTripApp() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val host = remember { AtlasHost(context) }
    val model: IntelliTripViewModel = viewModel(factory = IntelliTripViewModel.factory(host))
    IntelliTripContent(host, model)
}

/**
 * The map is the whole screen. Chrome floats above it in a right-hand column and
 * everything secondary lives in a bottom sheet, so nothing is permanently laid
 * over the map and there is no permanently visible control panel.
 */
@Composable
private fun IntelliTripContent(host: AtlasHost, model: IntelliTripViewModel) {
    val state by model.state.collectAsStateWithLifecycle()

    Box(modifier = Modifier.fillMaxSize()) {
        MapCanvas(
            state = state,
            throttle = host.throttle,
            pipeline = host.pipeline,
            routeGeometry = model.routeGeometry(),
            onViewportChanged = model::onViewportChanged,
        )

        MapChrome(
            state = state,
            onScope = model::setScope,
            onLoadStatic = model::loadStaticNetwork,
            onLoadTransit = model::loadLiveTransit,
            onLoadRadar = model::loadRadar,
            onLoadAlerts = model::loadWeatherAlerts,
            onToggleAutoRefresh = model::toggleAutoRefresh,
            onRecenter = { model.recentre(GeoPoint(DEFAULT_LAT, DEFAULT_LON)) },
        )
    }
}
