package com.intellitrip.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.intellitrip.AppState
import com.intellitrip.offline.OfflineRegistry

/** Surfaces status and honest data-availability information. */
@Composable
fun StatusPanel(state: AppState, capabilities: OfflineRegistry.Capabilities?) {
    Column(modifier = Modifier.padding(top = 8.dp)) {
        Text(text = state.status, style = MaterialTheme.typography.bodyMedium)

        if (state.staticFeed != null) {
            val feed = state.staticFeed
            Text(
                text = "Static network: ${feed.routes.size} routes · ${feed.stops.size} stops · " +
                    "${feed.trips.size} trips · ${feed.stopTimes.size} stop times",
                style = MaterialTheme.typography.labelSmall,
            )
        }

        capabilities?.let { caps ->
            Text(
                text = "Data: ${if (caps.staticNetworkUsable) "offline network ready" else "no offline snapshot"}" +
                    " · live: ${if (caps.realtimeUsable) "current" else caps.realtimeFreshness.name.lowercase()}" +
                    " · basemap: ${caps.basemap.name}",
                style = MaterialTheme.typography.labelSmall,
            )
            caps.notes.forEach { note ->
                Text(text = "• $note", style = MaterialTheme.typography.labelSmall)
            }
        }

        if (state.vehiclesFetchedAt != null) {
            Text(
                text = "Vehicles last updated ${state.vehiclesFetchedAt}",
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}