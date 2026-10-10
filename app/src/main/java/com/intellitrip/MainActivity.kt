package com.intellitrip

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.intellitrip.domain.Focus
import com.intellitrip.domain.GeoPoint
import com.intellitrip.ui.DataControls
import com.intellitrip.ui.MapCanvas
import com.intellitrip.ui.ScopeControls
import com.intellitrip.ui.StatusPanel
import com.intellitrip.ui.TripPlannerPanel

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
    val model: IntelliTripViewModel = viewModel(
        factory = IntelliTripViewModel.factory(host),
    )
    IntelliTripContent(host, model)
}

@Composable
private fun IntelliTripContent(host: AtlasHost, model: IntelliTripViewModel) {
    val state by model.state.collectAsStateWithLifecycle()

    Surface(color = Color.Transparent, modifier = Modifier.fillMaxSize()) {
        MapCanvas(
            state = state,
            throttle = host.throttle,
            pipeline = host.pipeline,
        )

        Column(
            modifier = Modifier
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ScopeControls(state = state, onScope = model::setScope)
            DataControls(
                state = state,
                onLoadStatic = model::loadStaticNetwork,
                onLoadTransit = model::loadLiveTransit,
                onLoadRadar = model::loadRadar,
                onLoadAlerts = model::loadWeatherAlerts,
                onToggleAutoRefresh = model::toggleAutoRefresh,
            )
            TripPlannerPanel(
                state = state,
                onOrigin = model::setOrigin,
                onDestination = model::setDestination,
                onPlan = model::planJourney,
            )
            StatusPanel(state = state, capabilities = model.offlineCapabilities())
        }
    }
}