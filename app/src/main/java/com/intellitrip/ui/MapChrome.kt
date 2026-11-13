package com.intellitrip.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
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
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.intellitrip.ScopePreset
import com.intellitrip.TravelMode
import com.intellitrip.WalkEstimator
import com.intellitrip.activeJourneyId
import com.intellitrip.DEFAULT_LAT
import com.intellitrip.DEFAULT_LON
import com.intellitrip.domain.Focus
import com.intellitrip.label
import com.intellitrip.offline.BasemapPackage
import com.intellitrip.presetResolution
import com.intellitrip.ui.theme.Radii
import com.intellitrip.ui.theme.Space
import com.intellitrip.domain.GeoPoint
import kotlinx.datetime.Clock

/** Which overlay sheet is showing. Only one at a time, or none. */
/** Sheets the floating controls can raise. Public so navigation can request one. */
enum class Sheet { None, Data, Scope, Plan }

/** Results shown in the plan sheet before it stops being scannable. */
private const val MAX_RESULTS = 5

/**
 * Destinations a rider can plan to. Each has scheduled service from the default
 * origin; a preset that produced no journeys would be a dead end.
 */
private val PLACE_OPTIONS: List<Pair<String, GeoPoint>> = listOf(
    "Downtown Minneapolis" to GeoPoint(44.9765, -93.2650),
    "Downtown St Paul" to GeoPoint(44.9580, -93.1560),
    "Mall of America" to GeoPoint(44.8557, -93.2367),
    "Minneapolis-St Paul airport" to GeoPoint(44.8820, -93.2218),
    "University of Minnesota" to GeoPoint(44.9712, -93.2432),
    "Edina" to GeoPoint(44.9062, -93.2870),
)

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
    onImportBasemap: () -> Unit,
    onSelectBasemap: (String) -> Unit,
    onRemoveBasemap: (String) -> Unit,
    onLocateMe: () -> Unit,
    locationPermissionGranted: Boolean,
    onSetOrigin: (GeoPoint) -> Unit,
    onSetDestination: (GeoPoint) -> Unit,
    onSwapEndpoints: () -> Unit,
    onPlan: () -> Unit,
    onSetTravelMode: (TravelMode) -> Unit,
    onStopTracking: () -> Unit,
    onSelectJourney: (String) -> Unit,
    onToggleDepartures: () -> Unit,
    /** Extra top offset for the scope chip, so it clears the search entry on Explore. */
    scopeChipInset: androidx.compose.ui.unit.Dp = 0.dp,
    /** Non-null asks for that sheet to open; changes are acted on once per value. */
    requestedSheet: Sheet? = null,
) {
    var sheet by remember { mutableStateOf(Sheet.None) }

    // Navigation can ask for a sheet directly, so choosing Plan in the bottom bar opens
    // the planner instead of leaving a tab that looks selected but does nothing.
    LaunchedEffect(requestedSheet) {
        if (requestedSheet != null) sheet = requestedSheet
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Filter chips under the search pill (Google Maps pattern): time source,
        // layer toggles. Transit/Radar open the data sheet / toggle layers.
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(top = 56.dp, end = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChipRow(
                onTransitClick = { sheet = Sheet.Data },
                onRadarClick = onLoadRadar,
            )
        }
        // Bottom-end stack: Locate above extended Plan (2 controls, not 4).
        // Data/Departures live in the peek sheet, not floating FABs.
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(end = 12.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.End,
        ) {
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
                modifier = Modifier.size(56.dp),
            ) { Icon(Icons.Default.MyLocation, contentDescription = "Show my location") }
            ExtendedFloatingActionButton(
                onClick = { sheet = Sheet.Plan },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                Icon(Icons.Default.TravelExplore, contentDescription = null)
                Text("Plan", modifier = Modifier.padding(start = 8.dp))
            }
        }

        Surface(
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(top = 12.dp + scopeChipInset)
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

        // Following is a mode until the rider stops it. Keeping a visible,
        // dismissible chip means tracking cannot be left running invisibly after
        // the vehicle sheet closes.
        state.trackingStatus?.let { status ->
            Surface(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(top = 12.dp + scopeChipInset + 40.dp)
                    .clickable(onClick = onStopTracking),
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                tonalElevation = 2.dp,
            ) {
                Row(
                    modifier = Modifier.padding(start = 10.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = status,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(end = 4.dp),
                    )
                    TextButton(onClick = onStopTracking) { Text("Stop") }
                }
            }
        }

        AnimatedVisibility(
            visible = sheet != Sheet.None,
            enter = fadeIn(tween(com.intellitrip.ui.theme.Motion.SelectionMillis)) +
                slideInVertically(tween(com.intellitrip.ui.theme.Motion.SheetMillis)) { it },
            exit = fadeOut(tween(com.intellitrip.ui.theme.Motion.SelectionMillis)) +
                slideOutVertically(tween(com.intellitrip.ui.theme.Motion.SheetMillis)) { it },
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
                        onImportBasemap = onImportBasemap,
                        onSelectBasemap = onSelectBasemap,
                        onRemoveBasemap = onRemoveBasemap,
                        onLocateMe = onLocateMe,
                        locationPermissionGranted = locationPermissionGranted,
                    )
                    Sheet.Scope -> ScopeSheet(state = state, onScope = onScope)
                    Sheet.Plan -> PlanSheet(
                        state = state,
                        onSetOrigin = onSetOrigin,
                        onSetDestination = onSetDestination,
                        onSwapEndpoints = onSwapEndpoints,
                        onPlan = onPlan,
                        onSetTravelMode = onSetTravelMode,
                        onSelectJourney = onSelectJourney,
                    )
                    Sheet.None -> Unit
                }
            }
        }
    }
}

/**
 * Rider-facing description of what the map is currently showing.
 *
 * Section 6.3 asks for the consumer surface to stop exposing engine vocabulary. The
 * raw scope name and zoom bucket ("System", "Cluster", "VehicleBudget") describe the
 * pipeline, not the map, so they are translated here. The engine values remain
 * available under More for anyone diagnosing the pipeline.
 */
private fun scopeSummary(state: AppState): String {
    val bucket = state.scope.renderScope.zoomBucket.name.lowercase()
    val area = when {
        state.scopeLabel.contains("You", ignoreCase = true) -> "Near you"
        state.scopeLabel.contains("Viewport", ignoreCase = true) -> "Map area"
        else -> "Citywide"
    }
    val detail = when {
        bucket.contains("neighborhood", true) -> "neighborhood detail"
        bucket.contains("street", true) -> "street level"
        bucket.contains("metro", true) -> "metro area"
        else -> "regional view"
    }
    return "$area, $detail"
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
    onImportBasemap: () -> Unit,
    onSelectBasemap: (String) -> Unit,
    onRemoveBasemap: (String) -> Unit,
    onLocateMe: () -> Unit,
    locationPermissionGranted: Boolean,
) {
    SheetHeader("Data")
    SheetRow("Static network", state.staticFeed?.let { "${it.routes.size} routes" } ?: "not loaded", onLoadStatic)
    SheetRow(
        "Live transit",
        if (state.vehicles.isNotEmpty()) "${state.vehicles.size} vehicles" else "tap to load",
        onLoadTransit,
    )
    SheetToggle("Auto refresh (15s)", state.autoRefresh, onToggleAutoRefresh)
    SheetToggle("Radar", state.radarEnabled, onLoadRadar)
    SheetRow(
        "Weather alerts",
        if (state.weatherAlerts.isEmpty()) "none loaded" else "${state.weatherAlerts.size}",
        onLoadAlerts,
    )
    if (state.weatherAlerts.isNotEmpty()) {
        Text(
            text = "Active alerts",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 8.dp),
        )
        state.weatherAlerts.take(5).forEach { alert ->
            Column(modifier = Modifier.padding(top = 4.dp)) {
                Text(
                    text = "${alert.event} · ${alert.severity.name.lowercase()}",
                    style = MaterialTheme.typography.labelMedium,
                )
                Text(
                    text = alert.headline,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (alert.areas.isNotEmpty()) {
                    Text(
                        text = alert.areas.take(3).joinToString(", "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Text(
            text = "Alerts are area warnings. No alert shape is drawn on the map, so " +
                "this list is the complete view.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
    val activeBasemap = state.basemapPackages.firstOrNull { it.id == state.activeBasemapId }
    SheetRow(
        "Offline basemap",
        when {
            activeBasemap != null ->
                "${activeBasemap.displayName} · z${activeBasemap.minZoom}–${activeBasemap.maxZoom}"
            state.basemapPackages.isNotEmpty() ->
                "${state.basemapPackages.size} installed, none active"
            else -> "import .pmtiles + style"
        },
        onImportBasemap,
    )
    state.basemapPackages.forEach { pkg ->
        BasemapPackageRow(
            pkg = pkg,
            active = pkg.id == state.activeBasemapId,
            onUse = { onSelectBasemap(pkg.id) },
            onRemove = { onRemoveBasemap(pkg.id) },
        )
    }
    Text(
        text = "Offline basemaps are PMTiles v3 archives plus a MapLibre style JSON. " +
            "MBTiles must be converted with `pmtiles convert` first. The style controls " +
            "labels, colours and attribution.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
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
private fun BasemapPackageRow(
    pkg: BasemapPackage,
    active: Boolean,
    onUse: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = pkg.displayName, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = "${formatBytes(pkg.sizeBytes)} · zoom ${pkg.minZoom}–${pkg.maxZoom}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onUse, enabled = !active) {
            Text(if (active) "Active" else "Use")
        }
        TextButton(onClick = onRemove) { Text("Remove") }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000L -> String.format(java.util.Locale.US, "%.1f GB", bytes / 1_000_000_000.0)
    bytes >= 1_000_000L -> String.format(java.util.Locale.US, "%.0f MB", bytes / 1_000_000.0)
    bytes >= 1_000L -> String.format(java.util.Locale.US, "%.0f KB", bytes / 1_000.0)
    else -> "$bytes B"
}

@Composable
private fun ScopeSheet(state: AppState, onScope: (String, Focus?) -> Unit) {
    SheetHeader("Scope")
    val now = Clock.System.now()
    ScopePreset.entries.forEach { preset ->
        val resolution = state.presetResolution(preset, now)
        SheetRow(
            "${preset.label} (${preset.radiusLabel})",
            current(state, preset.label),
        ) {
            onScope(resolution.label(preset), resolution.focus)
        }
    }
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
        text = "Presets centre on your fresh location, then the map view. A stale " +
            "fix is shown as last known but never moves the camera. Panning also sets the scope.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun current(state: AppState, label: String): String =
    if (state.scopeLabel.startsWith(label)) "current" else ""

/**
 * The planner is the product's central task, so the sheet is a real flow: choose
 * endpoints, swap them, plan, then select a result to draw it on the map.
 *
 * Endpoints are named places rather than raw coordinates. A rider does not know
 * that the origin is 44.9765,-93.2650, and typing coordinates on a phone is not a
 * planning UI. Until geocoding exists these presets are the honest minimum: they
 * are real transit destinations with scheduled service.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlanSheet(
    state: AppState,
    onSetOrigin: (GeoPoint) -> Unit,
    onSetDestination: (GeoPoint) -> Unit,
    onSwapEndpoints: () -> Unit,
    onPlan: () -> Unit,
    onSetTravelMode: (TravelMode) -> Unit,
    onSelectJourney: (String) -> Unit,
) {
    SheetHeader("Plan a trip")
    val modeWalkOnly = state.travelMode == TravelMode.WALK_ONLY

    if (state.staticFeed == null && state.travelMode == TravelMode.TRANSIT) {
        Text(
            "Download the local transit network first.",
            style = MaterialTheme.typography.bodyMedium,
        )
        return
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = state.travelMode == TravelMode.TRANSIT,
            onClick = { onSetTravelMode(TravelMode.TRANSIT) },
            label = { Text("Transit") },
        )
        FilterChip(
            selected = state.travelMode == TravelMode.WALK_ONLY,
            onClick = { onSetTravelMode(TravelMode.WALK_ONLY) },
            label = { Text("Walk only") },
        )
    }
    if (state.travelMode == TravelMode.WALK_ONLY) {
        val estimate = state.walkEstimate
            ?: WalkEstimator.estimate(state.origin, state.destination)
        Text(
            text = if (estimate.withinCoverage) {
                "Walk ${estimate.meters.toInt()} m · about ${estimate.minutes} min"
            } else {
                "Outside walk coverage (${estimate.meters.toInt()} m). Try transit."
            },
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            text = "Straight-line estimate at 1.35 m/s. This is not a routed path, so " +
                "the map draws no walking line.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp),
        )
    }

    PlaceRow("From", labelFor(state.origin), state.origin) {
        if (state.userLocation != null) {
            PlaceChoice("My location") { onSetOrigin(state.userLocation.position) }
        }
        PLACE_OPTIONS.forEach { (label, point) -> PlaceChoice(label) { onSetOrigin(point) } }
    }
    PlaceRow("To", labelFor(state.destination), state.destination) {
        if (state.userLocation != null) {
            PlaceChoice("My location") { onSetDestination(state.userLocation.position) }
        }
        PLACE_OPTIONS.forEach { (label, point) -> PlaceChoice(label) { onSetDestination(point) } }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            // One atomic swap. Two separate setter calls would publish an
            // intermediate state where origin already equals the old destination.
            onClick = onSwapEndpoints,
            modifier = Modifier.weight(1f),
        ) { Text("Swap") }
        Button(
            onClick = onPlan,
            // Walk only is an explicitly unrouted distance estimate, not a
            // transit request. Disable the transit action rather than sending
            // a hidden transit plan under the walk selection.
            enabled = canSubmitTransitPlan(modeWalkOnly, state.planning),
            modifier = Modifier.weight(2f),
        ) {
            Text(
                when {
                    modeWalkOnly -> "Walk estimate only"
                    state.planning -> "Planning…"
                    else -> "Plan trip"
                },
            )
        }
    }
    if (!modeWalkOnly && state.planning) {
        androidx.compose.material3.LinearProgressIndicator(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
    }

    if (!modeWalkOnly && state.journeyWarnings.isNotEmpty()) {
        state.journeyWarnings.forEach { warning ->
            Text(
                text = "! $warning",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }

    // Same rule the map uses to decide what to draw, so the highlighted card and the
    // drawn route can never disagree.
    val activeId = state.activeJourneyId()
    if (!modeWalkOnly && !state.planning && state.journeys.isEmpty() && state.staticFeed != null) {
        Text(
            text = riderEmptyPlanMessage(state),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(vertical = 8.dp),
        )
    }
    if (!modeWalkOnly) state.journeys.take(MAX_RESULTS).forEach { journey ->
        val selected = journey.id == activeId
        val transfersText = if (journey.transferCount == 0) "Direct" else "${journey.transferCount} transfer${if (journey.transferCount > 1) "s" else ""}"
        Surface(
            onClick = { onSelectJourney(journey.id) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            shape = RoundedCornerShape(Radii.card),
            color = if (selected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            },
        ) {
            Column(modifier = Modifier.padding(Space.md)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = journeyHeadline(journey.departure, journey.arrival, journey.duration.inWholeMinutes),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    // Mode icons: walk / transit / walk.
                    journey.legs.forEach { leg ->
                        Icon(
                            imageVector = when (leg) {
                                is com.intellitrip.domain.Leg.WalkLeg -> Icons.Default.DirectionsWalk
                                is com.intellitrip.domain.Leg.Transit -> Icons.Default.DirectionsBus
                                is com.intellitrip.domain.Leg.Other -> Icons.Default.Info
                            },
                            contentDescription = null,
                            modifier = Modifier.size(18.dp).padding(horizontal = 2.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = transfersText,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
                // Inline timeline for the selected journey.
                if (selected) {
                    JourneyTimeline(journey)
                    Text(
                        text = "Shown on map",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                } else {
                    journey.legs.forEach { leg ->
                        Text(
                            text = legSummary(leg),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * A walk-only estimate must never call the transit planner. Kept pure so
 * regressions can be caught without requiring an emulator.
 */
internal fun canSubmitTransitPlan(walkEstimateOnly: Boolean, planning: Boolean): Boolean =
    !walkEstimateOnly && !planning

private fun riderEmptyPlanMessage(state: AppState): String {
    val s = state.status.lowercase()
    return when {
        "walk" in s || "stops-within" in s -> "No route within walk limit — try a closer destination."
        "arrive" in s -> "No trip arrives by that time."
        "unavailable" in s -> "Planning unavailable. ${state.status}"
        else -> "No route found. Check the endpoints or download schedules."
    }
}

@Composable
private fun JourneyTimeline(journey: com.intellitrip.domain.JourneyOption) {
    Column(modifier = Modifier.padding(top = 8.dp)) {
        journey.legs.forEach { leg ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = when (leg) {
                        is com.intellitrip.domain.Leg.WalkLeg -> Icons.Default.DirectionsWalk
                        is com.intellitrip.domain.Leg.Transit -> Icons.Default.DirectionsBus
                        is com.intellitrip.domain.Leg.Other -> Icons.Default.Info
                    },
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = legSummary(leg),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            // Transfer confidence dot after each transit leg that has a following transfer.
            val transfer = journey.transfers.firstOrNull { it.arrivingLeg == leg }
            if (transfer != null) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 28.dp, top = 2.dp)) {
                    Box(
                        modifier = Modifier.size(10.dp)
                            .background(confidenceColor(transfer.confidence), CircleShape),
                    )
                    Text(
                        text = "Transfer ${transfer.scheduledBuffer.inWholeMinutes} min · ${transfer.confidence.name.lowercase()}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
            }
        }
    }
}

private fun confidenceColor(c: com.intellitrip.domain.TransferConfidence): androidx.compose.ui.graphics.Color =
    when (c) {
        com.intellitrip.domain.TransferConfidence.HIGH -> androidx.compose.ui.graphics.Color(0xFF087F65)
        com.intellitrip.domain.TransferConfidence.MEDIUM -> androidx.compose.ui.graphics.Color(0xFFA96B00)
        com.intellitrip.domain.TransferConfidence.LOW -> androidx.compose.ui.graphics.Color(0xFFB3261E)
        else -> androidx.compose.ui.graphics.Color(0xFF526478)
    }

/** One origin/destination row; tapping opens the place picker for that end. */
@Composable
private fun PlaceRow(label: String, value: String, point: GeoPoint, onPick: @Composable () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier = Modifier.padding(bottom = 6.dp)) {
        TextButton(
            onClick = { expanded = !expanded },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("$label  $value", style = MaterialTheme.typography.bodyMedium)
                Text(
                    if (expanded) "-" else "+",
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
        if (expanded) onPick()
    }
}

@Composable
private fun PlaceChoice(label: String, onPick: () -> Unit) {
    TextButton(onClick = onPick, modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 16.dp))
    }
}

/** A preset name when the point matches one, otherwise the raw coordinates. */
private fun labelFor(point: GeoPoint): String =
    PLACE_OPTIONS.firstOrNull { it.second == point }?.first
        ?: "%.4f, %.4f".format(point.lat, point.lon)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterChipRow(
    onTransitClick: () -> Unit = {},
    onRadarClick: () -> Unit = {},
) {
    var nowSelected by remember { mutableStateOf(true) }
    FilterChip(
        selected = nowSelected,
        onClick = { nowSelected = true },
        label = { Text("Now") },
        trailingIcon = { Icon(Icons.Default.KeyboardArrowDown, contentDescription = null) },
    )
    FilterChip(selected = false, onClick = onTransitClick, label = { Text("Transit") })
    FilterChip(selected = false, onClick = onRadarClick, label = { Text("Radar") })
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
