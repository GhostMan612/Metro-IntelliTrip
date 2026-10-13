package com.intellitrip

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.intellitrip.ui.Destination
import com.intellitrip.ui.MapCanvas
import com.intellitrip.ui.MapChrome
import com.intellitrip.ui.MapFeatureSheet
import com.intellitrip.ui.NearbyDeparturesSheet
import com.intellitrip.ui.RiderNavigationBar
import com.intellitrip.ui.RiderNavigationRail
import com.intellitrip.ui.theme.IntelliTripTheme
import com.intellitrip.ui.theme.Space
import java.time.Instant

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Renderer setup only; performs no network activity.
        org.maplibre.android.MapLibre.getInstance(applicationContext)
        setContent {
            IntelliTripTheme {
                IntelliTripApp()
            }
        }
    }
}

/**
 * Width at which the navigation moves from a bottom bar to a rail.
 *
 * Measured rather than keyed to a device type, so a rotated phone or a foldable is
 * treated by the same rule as a tablet.
 */
private val RailWidthDp = 600.dp

/** Zoom applied when a rider asks to separate a cluster of vehicles. */
private const val CLUSTER_ZOOM_RADIUS_METERS = 250.0

@Composable
private fun IntelliTripApp() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val host = remember { AtlasHost(context) }
    val model: IntelliTripViewModel = viewModel(factory = IntelliTripViewModel.factory(host))
    IntelliTripContent(host, model)
}

/**
 * The rider-facing shell.
 *
 * Structure follows the information architecture in section 5.1: a map that stays
 * edge-to-edge inside its pane, a persistent four-destination navigator, and one
 * dominant action on Explore. The engine's own controls remain reachable, but only
 * under More, which is the separation section 6.3 asks for. Nothing is removed.
 */
@Composable
private fun IntelliTripContent(host: AtlasHost, model: IntelliTripViewModel) {
    val state by model.state.collectAsStateWithLifecycle()
    var destination by rememberSaveable { mutableStateOf(Destination.Explore) }
    var departuresOpen by rememberSaveable { mutableStateOf(false) }
    var advancedSheet by remember { mutableStateOf<com.intellitrip.ui.Sheet?>(null) }

    // Location permission is requested only when the user taps the locate button.
    // Nothing here runs at startup, so the app never asks before it has been used.
    var permissionGranted by remember { mutableStateOf(host.location.hasPermission()) }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        permissionGranted = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        model.onLocateMe(permissionGranted)
    }

    val requestLocation = {
        permissionLauncher.launch(
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
        )
    }

    val useRail = LocalConfiguration.current.screenWidthDp >= RailWidthDp.value
    val hasFeed = state.staticFeed != null

    Surface(modifier = Modifier.fillMaxSize()) {
        if (useRail) {
            Row(modifier = Modifier.fillMaxSize()) {
                RiderNavigationRail(
                    current = destination,
                    onSelect = { destination = it },
                    showDownloadBadge = !hasFeed,
                )
                MapPane(
                    host = host,
                    model = model,
                    state = state,
                    destination = destination,
                    advancedSheet = advancedSheet,
                    onAdvancedSheetConsumed = { advancedSheet = null },
                    departuresOpen = departuresOpen,
                    onDeparturesOpenChange = { departuresOpen = it },
                    onPlanRequested = { destination = Destination.Plan },
                    onOpenAdvancedSheet = { sheet -> advancedSheet = sheet; destination = Destination.Explore },
                    onCloseToExplore = { destination = Destination.Explore },
                    requestLocation = requestLocation,
                    permissionGranted = permissionGranted,
                )
            }
        } else {
            Scaffold(
                bottomBar = {
                    RiderNavigationBar(
                        current = destination,
                        onSelect = { destination = it },
                        showDownloadBadge = !hasFeed,
                    )
                },
            ) { padding ->
                Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                    MapPane(
                        host = host,
                        model = model,
                        state = state,
                        destination = destination,
                        advancedSheet = advancedSheet,
                        onAdvancedSheetConsumed = { advancedSheet = null },
                        departuresOpen = departuresOpen,
                        onDeparturesOpenChange = { departuresOpen = it },
                        onPlanRequested = { destination = Destination.Plan },
                        onOpenAdvancedSheet = { sheet -> advancedSheet = sheet; destination = Destination.Explore },
                        onCloseToExplore = { destination = Destination.Explore },
                        requestLocation = requestLocation,
                        permissionGranted = permissionGranted,
                    )
                }
            }
        }
    }
}

/**
 * The map and its floating controls, shared by both navigator forms.
 *
 * The departure board is opened from Explore rather than being permanently visible, so
 * the map keeps the screen on first launch and the sheet can be dismissed to get the
 * map back.
 */
@Composable
private fun MapPane(
    host: AtlasHost,
    model: IntelliTripViewModel,
    state: AppState,
    destination: Destination,
    advancedSheet: com.intellitrip.ui.Sheet?,
    onAdvancedSheetConsumed: () -> Unit,
    departuresOpen: Boolean,
    onDeparturesOpenChange: (Boolean) -> Unit,
    onPlanRequested: () -> Unit,
    onOpenAdvancedSheet: (com.intellitrip.ui.Sheet) -> Unit,
    onCloseToExplore: () -> Unit,
    requestLocation: () -> Unit,
    permissionGranted: Boolean,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        // A sheet requested from More is consumed on arrival, so returning to Explore
        // shows the requested sheet once instead of reopening it forever.
        LaunchedEffect(advancedSheet) {
            if (advancedSheet != null) onAdvancedSheetConsumed()
        }

        MapCanvas(
            state = state,
            throttle = host.throttle,
            pipeline = host.pipeline,
            routeGeometry = model.routeGeometry(),
            onViewportChanged = model::onViewportChanged,
            // A tap is resolved against what is actually drawn, so tapping the drawn
            // route selects that route rather than whatever shares its pixels.
            onMapTap = { lat, lon ->
                val renderer = host.currentRenderer()
                    ?: return@MapCanvas com.intellitrip.map.MapSelection.None
                val queried = renderer.queryFeatures(lat, lon)
                val features = queried.mapNotNull { hit ->
                    val kind = layerKindOf(hit.layerId) ?: return@mapNotNull null
                    com.intellitrip.map.MapSelectionResolver.resolve(hit.values, kind)
                }
                val selection = com.intellitrip.map.MapSelectionResolver.collapse(features)
                model.selectFeature(selection)
                selection
            },
            onRendererCreated = host::attachRenderer,
        )

        // One dominant entry action, above the map, per the wireframe in section 5.2.
        // A rider arrives with a single intent and should not have to pick a mode first.
        if (destination == Destination.Explore && !departuresOpen) {
            com.intellitrip.ui.DestinationSearchEntry(
                onClick = onPlanRequested,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = Space.lg, start = Space.lg, end = 76.dp),
            )
        }

        MapChrome(
            state = state,
            onScope = model::setScope,
            onLoadStatic = model::loadStaticNetwork,
            onLoadTransit = model::loadLiveTransit,
            onLoadRadar = model::loadRadar,
            onLoadAlerts = model::loadWeatherAlerts,
            onToggleAutoRefresh = model::toggleAutoRefresh,
            onLocateMe = requestLocation,
            locationPermissionGranted = permissionGranted,
            onSetOrigin = model::setOrigin,
            onSetDestination = model::setDestination,
            onSwapEndpoints = model::swapEndpoints,
            onPlan = model::planJourney,
            onSelectJourney = model::selectJourney,
            onToggleDepartures = { onDeparturesOpenChange(!departuresOpen) },
            // Clears the Explore search entry so the two top surfaces do not overlap.
            scopeChipInset = if (destination == Destination.Explore && !departuresOpen) 72.dp else 0.dp,
            // Choosing Plan in the navigator opens the planner; choosing More offers the
            // advanced sheets. Without this those tabs would look selected and do nothing.
            requestedSheet = advancedSheet ?: when (destination) {
                Destination.Plan -> com.intellitrip.ui.Sheet.Plan
                else -> null
            },
        )

        if (departuresOpen) {
            NearbyDeparturesSheet(
                departures = model.nearbyDepartures(),
                hasFeed = hasFeed(state),
                feedAgeLabel = state.staticFeed?.metadata?.fetchedAt?.let { "Schedule data loaded $it" },
                onDownloadSchedules = model::loadStaticNetwork,
                onDismiss = { onDeparturesOpenChange(false) },
                onSelectDeparture = { },
            )
        }

        val selection = state.mapSelection
        if (selection !is com.intellitrip.map.MapSelection.None) {
            MapFeatureSheet(
                selection = selection,
                stops = state.staticFeed?.stops
                    // Scoped by feed as well as stop id, so two agencies that both use
                    // "1" for their first stop cannot collapse into one lookup.
                    ?.associateBy { "${it.key.feedId.value}/${it.key.stopId}" }
                    ?: emptyMap(),
                vehicles = state.vehicles.associateBy { it.key.vehicleId },
                journeys = state.journeys,
                departures = model.nearbyDepartures(),
                now = Instant.now(),
                onDismiss = model::clearSelection,
                onSaveStop = model::toggleSavedStop,
                onSelectJourney = model::selectJourney,
                onZoomToCluster = { cluster ->
                    // Separating vehicles is a zoom operation; there is no cluster
                    // expansion to fake.
                    model.setScope(
                        "Map area",
                        com.intellitrip.domain.Focus.Radius(
                            com.intellitrip.domain.GeoPoint(cluster.centroidLat, cluster.centroidLon),
                            CLUSTER_ZOOM_RADIUS_METERS,
                        ),
                    )
                },
                onChooseCandidate = { chosen ->
                    // Picking a candidate narrows the selection to it, so the sheet
                    // shows that feature's detail rather than the list again.
                    model.selectFeature(com.intellitrip.map.MapSelection.Single(chosen))
                },
            )
        }

        // Non-map destinations replace the map rather than covering it, so Saved and
        // More are full-height surfaces a rider can scroll.
        if (destination == Destination.Saved) {
            SavedScreenSurface(
                saved = state.savedStops,
                onOpenStop = { model.openSavedStop(it); onCloseToExplore() },
                onRemoveStop = { saved ->
                    model.savedStopDomain(saved)?.let(model::toggleSavedStop)
                },
                onCloseToExplore = onCloseToExplore,
            )
        }

        if (destination == Destination.More) {
            MoreScreenSurface(
                state = state,
                onToggleRadar = model::toggleRadar,
                onToggleAutoRefresh = model::toggleAutoRefresh,
                onLoadAlerts = model::loadWeatherAlerts,
                onLoadStatic = model::loadStaticNetwork,
                onLocateMe = requestLocation,
                onOpenAdvancedSheet = onOpenAdvancedSheet,
                onCloseToExplore = onCloseToExplore,
            )
        }
    }
}

/**
 * Maps a MapLibre layer id to the feature kind the selection rules understand.
 *
 * Kept as a total function with an explicit unknown branch: an unmapped layer id
 * yields null and the feature is dropped, so adding a new selectable layer cannot
 * silently produce a feature the UI cannot render.
 */
private fun layerKindOf(layerId: String): com.intellitrip.map.MapSelectionResolver.MapFeatureKind? =
    when (layerId) {
        "intellitrip-route-layer" -> com.intellitrip.map.MapSelectionResolver.MapFeatureKind.JOURNEY
        "intellitrip-vehicle-layer" -> com.intellitrip.map.MapSelectionResolver.MapFeatureKind.VEHICLE
        "intellitrip-stop-layer" -> com.intellitrip.map.MapSelectionResolver.MapFeatureKind.STOP
        "intellitrip-cluster-layer" -> com.intellitrip.map.MapSelectionResolver.MapFeatureKind.CLUSTER
        else -> null
    }

@Composable
private fun SurfaceScaffold(title: String, onClose: () -> Unit, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding(),
    ) {
        content()
        Spacer(Modifier.weight(1f))
        Row(modifier = Modifier.fillMaxWidth().padding(Space.md)) {
            TextButton(onClick = onClose) { Text("Back to map") }
        }
    }
}

@Composable
private fun SavedScreenSurface(
    saved: List<com.intellitrip.ui.SavedStop>,
    onOpenStop: (com.intellitrip.ui.SavedStop) -> Unit,
    onRemoveStop: (com.intellitrip.ui.SavedStop) -> Unit,
    onCloseToExplore: () -> Unit,
) {
    SurfaceScaffold(title = "Saved", onClose = {}) {
        com.intellitrip.ui.SavedScreen(
            saved = saved,
            onOpenStop = onOpenStop,
            onRemoveStop = onRemoveStop,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun MoreScreenSurface(
    state: AppState,
    onToggleRadar: () -> Unit,
    onToggleAutoRefresh: () -> Unit,
    onLoadAlerts: () -> Unit,
    onLoadStatic: () -> Unit,
    onLocateMe: () -> Unit,
    onOpenAdvancedSheet: (com.intellitrip.ui.Sheet) -> Unit,
    onCloseToExplore: () -> Unit,
) {
    SurfaceScaffold(title = "More", onClose = onCloseToExplore) {
        com.intellitrip.ui.MoreScreen(
            state = state,
            onToggleRadar = onToggleRadar,
            onToggleAutoRefresh = onToggleAutoRefresh,
            onLoadAlerts = onLoadAlerts,
            onLoadStatic = onLoadStatic,
            onOpenScope = { onOpenAdvancedSheet(com.intellitrip.ui.Sheet.Scope) },
            onOpenData = { onOpenAdvancedSheet(com.intellitrip.ui.Sheet.Data) },
            onLocateMe = onLocateMe,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

private fun hasFeed(state: AppState): Boolean = state.staticFeed != null