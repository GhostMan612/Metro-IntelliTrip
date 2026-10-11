package com.intellitrip.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.intellitrip.AppState
import com.intellitrip.DEFAULT_LAT
import com.intellitrip.DEFAULT_LON
import com.intellitrip.domain.Focus
import com.intellitrip.domain.GeoPoint

/** Which overlay sheet is showing. Only one at a time, or none. */
enum class Sheet { None, Data, Scope, Plan }

/**
 * Full-screen map chrome.
 *
 * The map is the product, so it fills the viewport edge to edge and nothing is
 * permanently laid over it. Controls float in a single right-hand column; every
 * secondary action lives in a bottom sheet that is hidden until asked for, so the
 * default view is the map plus two small affordances.
 */
@Composable
fun MapChrome(
    state: AppState,
    onScope: (String, Focus?) -> Unit,
    onLoadStatic: () -> Unit,
    onLoadTransit: () -> Unit,
    onLoadRadar: () -> Unit,
    onLoadAlerts: () -> Unit,
    onToggleAutoRefresh: () -> Unit,
    onLocateMe: () -> Unit,
    locationPermissionGranted: Boolean,
) {
    var sheet by remember { mutableStateOf(Sheet.None) }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.End,
        ) {
            FloatingActionButton(
                onClick = { sheet = Sheet.Plan },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(56.dp),
            ) {
                Icon(Icons.Default.TravelExplore, contentDescription = "Plan a trip")
            }
            FloatingActionButton(
                onClick = { sheet = if (sheet == Sheet.Data) Sheet.None else Sheet.Data },
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(44.dp),
            ) { Icon(Icons.Default.Layers, contentDescription = "Data and layers") }
            FloatingActionButton(
                onClick = onLocateMe,
                // Tinted when a fix is active, so the button reports state instead
                // of just being a control you have already pressed.
                containerColor = if (state.userLocation != null) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surface
                },
                contentColor = if (state.userLocation != null) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                modifier = Modifier.size(44.dp),
            ) { Icon(Icons.Default.MyLocation, contentDescription = "Show my location") }
        }

        Surface(
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(12.dp)
                .clickable { sheet = if (sheet == Sheet.Scope) Sheet.None else Sheet.Scope },
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
            tonalElevation = 2.dp,
        ) {
            Text(
                text = scopeSummary(state),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            )
        }

        AnimatedVisibility(
            visible = sheet != Sheet.None,
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it },
            modifier = Modifier.fillMaxSize(),
        ) {
            Scrim(onDismiss = { sheet = Sheet.None }) {
                when (sheet) {
                    Sheet.Data -> DataSheet(
                        state = state,
                        onLoadStatic = onLoadStatic,
                        onLoadTransit = onLoadTransit,
                        onLoadRadar = onLoadRadar,
                        onLoadAlerts = onLoadAlerts,
                        onToggleAutoRefresh = onToggleAutoRefresh,
                        onLocateMe = onLocateMe,
                        locationPermissionGranted = locationPermissionGranted,
                    )
                    Sheet.Scope -> ScopeSheet(state = state, onScope = onScope)
                    Sheet.Plan -> PlanSheet(state = state)
                    Sheet.None -> Unit
                }
            }
        }
    }
}

private fun scopeSummary(state: AppState): String {
    val bucket = state.scope.renderScope.zoomBucket.name.lowercase().replaceFirstChar { it.uppercase() }
    return "${state.scopeLabel} · $bucket"
}

/** Dims the map behind a sheet so the sheet reads as the foreground surface. */
@Composable
private fun Scrim(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.4f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.BottomCenter,
    ) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            // Swallow taps so they do not dismiss through the sheet.
            .clickable(enabled = false) { }
            .padding(top = 4.dp),
        shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 6.dp,
    ) {
        // Drag handle: the expected affordance for dismissing a bottom sheet, and
        // works without needing a visible close button.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(width = 36.dp, height = 4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                    .clickable(onClick = onDismiss),
            )
        }
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) { content() }
        }
    }
}

@Composable
private fun SheetHeader(title: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun DataSheet(
    state: AppState,
    onLoadStatic: () -> Unit,
    onLoadTransit: () -> Unit,
    onLoadRadar: () -> Unit,
    onLoadAlerts: () -> Unit,
    onToggleAutoRefresh: () -> Unit,
    onLocateMe: () -> Unit,
    locationPermissionGranted: Boolean,
) {
    SheetHeader("Data")
    SheetRow("Static network", state.staticFeed?.let { "${it.routes.size} routes" } ?: "not loaded", onLoadStatic)
    SheetRow("Live transit", state.status.takeIf { it.startsWith("Live vehicles") } ?: "tap to load", onLoadTransit)
    SheetToggle("Auto refresh (15s)", state.autoRefresh, onToggleAutoRefresh)
    SheetToggle("Radar", state.radarEnabled, onLoadRadar)
    SheetRow(
        "Weather alerts",
        if (state.weatherAlerts.isEmpty()) "none loaded" else "${state.weatherAlerts.size}",
        onLoadAlerts,
    )
    SheetRow(
        "My location",
        when {
            state.userLocation != null ->
                "±${state.userLocation.accuracyMeters.toInt()} m"
            state.locationDenied -> "permission denied"
            locationPermissionGranted -> "tap to locate"
            else -> "permission needed"
        },
        onLocateMe,
    )
    state.status.takeIf { it.isNotBlank() }?.let {
        Text(
            text = it,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun ScopeSheet(state: AppState, onScope: (String, Focus?) -> Unit) {
    SheetHeader("Scope")
    val centre = GeoPoint(DEFAULT_LAT, DEFAULT_LON)
    SheetRow("Nearby (½ mile)", current(state, "Nearby"), { onScope("Nearby", Focus.Radius(centre, 805.0)) })
    SheetRow("Area (2 miles)", current(state, "Area"), { onScope("Area", Focus.Radius(centre, 3218.0)) })
    SheetRow("System", current(state, "System"), { onScope("System", Focus.System) })
    state.scope.let { scope ->
        Text(
            text = "${scope.renderScope.zoomBucket} · ${scope.renderScope.maxRenderedVehicles} vehicle budget",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
    Text(
        text = "Panning the map also sets the scope.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun current(state: AppState, label: String): String =
    if (state.scopeLabel.startsWith(label)) "current" else ""

@Composable
private fun PlanSheet(state: AppState) {
    SheetHeader("Plan")
    if (state.staticFeed == null) {
        Text(
            "Load the static network before planning.",
            style = MaterialTheme.typography.bodyMedium,
        )
        return
    }
    if (state.journeys.isEmpty()) {
        Text("No journeys yet.", style = MaterialTheme.typography.bodyMedium)
        return
    }
    state.journeys.take(5).forEach { journey ->
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                Text(
                    text = journeyHeadline(journey.departure, journey.arrival, journey.duration.toMinutes()),
                    style = MaterialTheme.typography.titleSmall,
                )
                journey.legs.forEach { leg ->
                    Text(
                        text = legSummary(leg),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SheetRow(label: String, value: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                value,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SheetToggle(label: String, checked: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = { onToggle() })
    }
}
