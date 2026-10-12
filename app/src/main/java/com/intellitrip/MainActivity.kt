package com.intellitrip

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
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
import com.intellitrip.ui.NearbyDeparturesSheet
import com.intellitrip.ui.RiderNavigationBar
import com.intellitrip.ui.RiderNavigationRail
import com.intellitrip.ui.theme.IntelliTripTheme
import com.intellitrip.ui.theme.Space

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
                    departuresOpen = departuresOpen,
                    onDeparturesOpenChange = { departuresOpen = it },
                    onPlanRequested = { destination = Destination.Plan },
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
                        departuresOpen = departuresOpen,
                        onDeparturesOpenChange = { departuresOpen = it },
                        onPlanRequested = { destination = Destination.Plan },
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
    departuresOpen: Boolean,
    onDeparturesOpenChange: (Boolean) -> Unit,
    onPlanRequested: () -> Unit,
    requestLocation: () -> Unit,
    permissionGranted: Boolean,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        MapCanvas(
            state = state,
            throttle = host.throttle,
            pipeline = host.pipeline,
            routeGeometry = model.routeGeometry(),
            onViewportChanged = model::onViewportChanged,
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
    }
}

private fun hasFeed(state: AppState): Boolean = state.staticFeed != null