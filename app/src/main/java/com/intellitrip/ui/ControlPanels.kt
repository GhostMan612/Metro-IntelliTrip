package com.intellitrip.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.intellitrip.AppState
import com.intellitrip.DEFAULT_LAT
import com.intellitrip.DEFAULT_LON
import com.intellitrip.domain.Focus
import com.intellitrip.domain.GeoPoint

@Composable
fun ScopeControls(state: AppState, onScope: (String, Focus?) -> Unit) {
    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                onScope("Nearby (½ mile)", Focus.Radius(GeoPoint(DEFAULT_LAT, DEFAULT_LON), 805.0))
            }) { Text("Nearby") }
            Button(onClick = {
                onScope("Area (2 miles)", Focus.Radius(GeoPoint(DEFAULT_LAT, DEFAULT_LON), 3218.0))
            }) { Text("Area") }
            Button(onClick = { onScope("System", Focus.System) }) { Text("System") }
        }
        state.scope?.let { scope ->
            Text(
                text = "${state.scopeLabel} · ${scope.renderScope.zoomBucket} · " +
                    "${scope.renderScope.maxRenderedVehicles} vehicle budget",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
fun DataControls(
    state: AppState,
    onLoadStatic: () -> Unit,
    onLoadTransit: () -> Unit,
    onLoadRadar: () -> Unit,
    onLoadAlerts: () -> Unit,
    onToggleAutoRefresh: () -> Unit,
) {
    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onLoadStatic) { Text("Network") }
            Button(onClick = onLoadTransit) { Text("Live transit") }
            Button(onClick = { onToggleAutoRefresh() }) { Text(if (state.autoRefresh) "Auto on" else "Auto") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            Button(onClick = onLoadRadar) { Text(if (state.radarEnabled) "Radar on" else "Radar") }
            Button(onClick = onLoadAlerts) { Text("Alerts") }
        }
    }
}