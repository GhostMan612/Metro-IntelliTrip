package com.intellitrip

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import android.net.Uri
import com.intellitrip.contracts.ProviderResult
import com.intellitrip.domain.FeedId
import com.intellitrip.domain.Focus
import com.intellitrip.domain.GeoPoint
import com.intellitrip.domain.JourneyOption
import com.intellitrip.map.RouteGeometry
import com.intellitrip.map.RouteLine
import com.intellitrip.domain.RadarFrame
import com.intellitrip.domain.ScopeState
import com.intellitrip.scope.ScopeEngine
import com.intellitrip.domain.TripPlanRequest
import com.intellitrip.domain.UserLocation
import com.intellitrip.domain.Vehicle
import com.intellitrip.domain.WeatherAlert
import com.intellitrip.gtfs.RefreshOutcome
import com.intellitrip.gtfs.StaticFeed
import com.intellitrip.offline.BasemapAvailability
import com.intellitrip.offline.CacheEntry
import com.intellitrip.offline.InstallResult
import com.intellitrip.offline.loadStaticFeed
import com.intellitrip.routing.RoutingNetwork
import com.intellitrip.routing.TransferIntelligenceService
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.datetime.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AppState(
    val status: String = "Idle",
    val scopeLabel: String = "System",
    val focus: Focus? = Focus.System,
    // Resolved from Focus.System rather than null: a null scope leaves the render
    // pipeline with no budget, so the map would be blank on a cold start before any
    // provider has been called.
    val scope: ScopeState = ScopeEngine().currentScope(Focus.System),
    val vehicles: List<Vehicle> = emptyList(),
    val vehiclesFetchedAt: Instant? = null,
    /** Stop-specific GTFS-Realtime trip updates, kept separate from vehicles. */
    val tripUpdates: List<com.intellitrip.domain.TripUpdate> = emptyList(),
    val tripUpdatesFetchedAt: Instant? = null,
    val staticFeed: StaticFeed? = null,
    val radarFrame: RadarFrame? = null,
    val radarEnabled: Boolean = false,
    val weatherAlerts: List<WeatherAlert> = emptyList(),
    val userLocation: UserLocation? = null,
    /** True once permission is denied, so the UI can explain rather than re-prompt. */
    val locationDenied: Boolean = false,
    val journeys: List<JourneyOption> = emptyList(),
    /** The journey drawn on the map; defaults to the earliest arrival. */
    val selectedJourneyId: String? = null,
    val journeyWarnings: List<String> = emptyList(),
    val planning: Boolean = false,
    val planningTimeMode: PlanningTimeMode = PlanningTimeMode.DEPART_AT,
    val planningTime: Instant? = null,
    /** Transit is the default; walking is an explicit choice, never inferred. */
    val travelMode: TravelMode = TravelMode.TRANSIT,
    /** Present only in walk mode; a straight-line estimate, not a routed journey. */
    val walkEstimate: WalkEstimate? = null,
    /** Non-null while the rider is following one vehicle on the map. */
    val trackedVehicleKey: com.intellitrip.domain.VehicleKey? = null,
    /** Latest fresh position of [trackedVehicleKey]; null when paused or missing. */
    val followTarget: GeoPoint? = null,
    /** Why tracking is paused, or that it is active. Shown next to the toggle. */
    val trackingStatus: String? = null,
    /** True after the rider loaded alerts, even when the response was empty. */
    val alertsEnabled: Boolean = false,
    val origin: GeoPoint = GeoPoint(DEFAULT_LAT, DEFAULT_LON),
    val destination: GeoPoint = GeoPoint(DEFAULT_DEST_LAT, DEFAULT_DEST_LON),
    val autoRefresh: Boolean = false,
    /**
     * What the rider last tapped on the map.
     *
     * Held in state rather than in a composable so the selection survives
     * configuration changes and so the map and the sheets cannot disagree about what
     * is selected.
     */
    val mapSelection: com.intellitrip.map.MapSelection = com.intellitrip.map.MapSelection.None,
    /** Saved stops, loaded from disk on startup and rewritten on every change. */
    val savedStops: List<com.intellitrip.ui.SavedStop> = emptyList(),
    /** Offline basemap packages found in the app's private store. */
    val basemapPackages: List<com.intellitrip.offline.BasemapPackage> = emptyList(),
    /** The package whose style the map is currently using, if any. */
    val activeBasemapId: String? = null,
    /** Active style JSON with its pmtiles:// source bound to the installed file. */
    val activeBasemapStyle: String? = null,
)

/**
 * Holds all screen state. Network work always runs on a background dispatcher and
 * results are surfaced through [AppState]; nothing blocks the main thread.
 */
class IntelliTripViewModel(private val host: AtlasHost) : ViewModel() {

    private val _state = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = _state.asStateFlow()

    private var viewportJob: Job? = null

    private val savedPlaces = com.intellitrip.ui.SavedPlacesStore(host.filesRoot())

    private var locationJob: Job? = null
    /**
     * One audible arrival cue per selected journey, not per GPS callback.
     *
     * Reset only when the rider starts or selects a different journey. The
     * geofence can be entered repeatedly during one trip without ringing
     * repeatedly.
     */
    private var arrivalCuedJourneyId: String? = null

    private var refreshJob: Job? = null
    private var alertsRefreshJob: Job? = null
    private var planningJob: Job? = null

    init {
        // Saved places are rider-owned, so they load before anything touches the
        // network and are never gated on a feed being present.
        runCatching { savedPlaces.load() }.getOrNull()?.let { saved ->
            if (saved.isNotEmpty()) _state.update { it.copy(savedStops = saved) }
        }

        // Load installed basemaps before the first map render so a rider who
        // already imported a package does not briefly see the online basemap.
        viewModelScope.launch { refreshBasemapState() }

        // Start from any previously stored snapshot so a cold launch has the
        // static network without touching the network.
        viewModelScope.launch {
            // Debug subset seed: instant departures/planner on low-end devices
            // without a 4-minute full-feed parse. Skipped when a snapshot exists.
            if (host.snapshotStore.activeSnapshot(host.feedId) == null) {
                runCatching { host.seedDebugFeedIfNeeded() }
            }
            // A snapshot written before the stop-time index carried boarding
            // restrictions is rebuilt once, off the main thread, before the
            // board or planner reads it. No-op once the index is current.
            withContext(Dispatchers.IO) {
                if (!host.snapshotStore.hasCurrentStopTimeIndex(host.feedId)) {
                    host.snapshotStore.reindexStopTimes(host.feedId)
                }
            }
            host.offlineRegistry.loadStaticFeed(host.feedId)?.let { feed ->
                val focus = _state.value.focus
                _state.update {
                    it.copy(
                        staticFeed = feed,
                        status = "Loaded offline snapshot (${feed.stops.size} stops)",
                        scope = host.scopeEngine.currentScope(focus),
                    )
                }
                adoptLegacySavedStops(host.feedId.value, feed.stops)
            }
        }
    }

    fun setScope(label: String, focus: Focus?) {
        _state.update {
            it.copy(
                scopeLabel = label,
                focus = focus,
                scope = host.scopeEngine.currentScope(focus),
            )
        }
    }

    /**
     * Adopts the map viewport as the active scope, so panning the map rescales the
     * rendered network. Gestures arrive on every animation frame, so this is
     * debounced and skips updates that would not change the scope meaningfully.
     */
    fun onViewportChanged(lat: Double, lon: Double, zoom: Double) {
        // A rider's own pan means they are no longer following the vehicle. The
        // renderer suppresses programmatic camera moves, so this only fires for a
        // gesture; leaving follow on would fight the map on the next poll.
        if (_state.value.trackedVehicleKey != null) {
            _state.update {
                it.copy(
                    trackedVehicleKey = null,
                    followTarget = null,
                    trackingStatus = null,
                    status = "Stopped following vehicle",
                )
            }
        }
        // Inverse of DefaultScopeResolver.zoomForRadius, shared so
        // zoom -> meters -> zoom round-trips and MapCanvas's moveCamera back is
        // a no-op (no post-pinch snap). The previous web-mercator approximation
        // truncated fractional zoom with toInt and derived a different zoom.
        val meters = com.intellitrip.scope.DefaultScopeResolver().radiusForZoom(zoom)
        val focus = Focus.Radius(GeoPoint(lat, lon), meters.coerceIn(200.0, 60_000.0))
        val current = _state.value.focus
        if (current is Focus.Radius) {
            val sameCenter = current.center.lat == lat && current.center.lon == lon
            // Within ~2% the label and bounds would churn without telling the user
            // anything new.
            if (sameCenter && kotlin.math.abs(current.meters - focus.meters) < meters * 0.02) return
        }
        viewportJob?.cancel()
        viewportJob = viewModelScope.launch {
            delay(VIEWPORT_DEBOUNCE_MS)
            setScope("Map viewport", focus)
            scheduleAlertRefresh()
        }
    }

    /**
     * Endpoints are the input to a plan, so changing one invalidates any result
     * still on screen.
     *
     * Leaving the previous journeys visible would present a route between the old
     * endpoints as though it answered the new question. The plan is dropped rather
     * than left stale; the user re-runs it explicitly.
     */
    fun setOrigin(point: GeoPoint) {
        if (_state.value.origin == point) return
        invalidatePlan()
        update { it.withEndpoints(origin = point) }
    }

    fun setDestination(point: GeoPoint) {
        if (_state.value.destination == point) return
        invalidatePlan()
        update { it.withEndpoints(destination = point) }
    }

    fun swapEndpoints() {
        if (_state.value.origin == _state.value.destination) return
        invalidatePlan()
        update {
            it.withEndpoints(origin = it.destination, destination = it.origin)
        }
    }

    fun setPlanningTimeMode(mode: PlanningTimeMode) {
        if (_state.value.planningTimeMode == mode) return
        invalidatePlan()
        update { it.withPlanningTime(mode, it.planningTime) }
    }

    fun setPlanningTime(time: Instant?) {
        if (_state.value.planningTime == time) return
        invalidatePlan()
        update { it.withPlanningTime(it.planningTimeMode, time) }
    }

    fun loadStaticNetwork() {
        update { it.copy(status = "Downloading static GTFS…") }
        viewModelScope.launch {
            when (val outcome = host.staticRefresher.refresh()) {
                is RefreshOutcome.Activated -> {
                    update {
                        it.copy(
                            staticFeed = outcome.feed,
                            scope = host.scopeEngine.currentScope(it.focus),
                            status = "Static network: ${outcome.feed.routes.size} routes, " +
                                "${outcome.feed.stops.size} stops, ${outcome.feed.trips.size} trips",
                        )
                    }
                    adoptLegacySavedStops(host.feedId.value, outcome.feed.stops)
                }

                is RefreshOutcome.NotModified -> update { it.copy(status = "Static GTFS already current") }

                is RefreshOutcome.Rejected -> update {
                    it.copy(status = "Static GTFS unavailable: ${outcome.reason}")
                }
            }
        }
    }

    fun loadLiveTransit() {
        update { it.copy(status = "Loading live transit…") }
        viewModelScope.launch {
            // Vehicles and trip updates are independent feeds. Fetch both at
            // once so a slow prediction endpoint cannot delay vehicle markers,
            // and keep their provenance separate: a vehicle is not an ETA.
            val vehiclesDeferred = async { host.realtime.vehiclePositions().first() }
            val updatesDeferred = async { host.realtime.tripUpdates().first() }
            when (val result = vehiclesDeferred.await()) {
                is ProviderResult.Success -> onVehicles(result.data, result.freshness.fetchedAt, result.freshness.age.inWholeSeconds)
                is ProviderResult.StaleSuccess -> onVehicles(result.data, result.freshness.fetchedAt, result.freshness.age.inWholeSeconds)
                else -> update { it.copy(status = "Live transit unavailable: ${describe(result)}") }
            }
            when (val result = updatesDeferred.await()) {
                is ProviderResult.Success -> onTripUpdates(result.data, result.freshness.fetchedAt)
                is ProviderResult.StaleSuccess -> onTripUpdates(result.data, result.freshness.fetchedAt)
                else -> update { it.copy(status = "Live predictions unavailable: ${describe(result)}") }
            }
        }
    }

    /**
     * Imports a PMTiles archive and its MapLibre style JSON chosen together in
     * one SAF picker. The pair is validated as a unit: a style that does not
     * reference a PMTiles source is rejected rather than leaving the map on the
     * online basemap while claiming an offline install.
     */
    fun importBasemap(uris: List<Uri>) {
        if (uris.isEmpty()) return
        update { it.copy(status = "Importing offline basemap…") }
        viewModelScope.launch {
            try {
                when (val result = withContext(Dispatchers.IO) { performBasemapImport(uris) }) {
                    is InstallResult.Installed -> {
                        refreshBasemapState(result.pkg.id)
                        update {
                            it.copy(
                                status = "Offline basemap installed: ${result.pkg.displayName} " +
                                    "(zoom ${result.pkg.minZoom}–${result.pkg.maxZoom})",
                            )
                        }
                    }

                    is InstallResult.Rejected -> update {
                        it.copy(status = "Basemap import rejected: ${result.reason}")
                    }
                }
            } catch (error: Exception) {
                update {
                    it.copy(status = "Basemap import failed: ${error.message ?: "I/O error"}")
                }
            }
        }
    }

    /** Makes an installed package the map's basemap. */
    fun selectBasemap(id: String) {
        viewModelScope.launch {
            refreshBasemapState(id)
            val active = _state.value.basemapPackages.firstOrNull { it.id == id }
            update {
                it.copy(
                    status = active?.let { pkg -> "Offline basemap: ${pkg.displayName}" }
                        ?: "Offline basemap not found",
                )
            }
        }
    }

    fun removeBasemap(id: String) {
        viewModelScope.launch {
            val removed = withContext(Dispatchers.IO) {
                runCatching { host.basemapStore.remove(id) }.getOrDefault(false)
            }
            refreshBasemapState()
            update {
                it.copy(
                    status = if (removed) "Offline basemap removed"
                    else "Could not remove the offline basemap",
                )
            }
        }
    }

    /**
     * Re-reads installed packages and the active style from disk. Disk work is
     * off the main thread; the state update is atomic so a concurrent refresh
     * cannot restore a stale active id.
     */
    private suspend fun refreshBasemapState(preferredId: String? = null) {
        val packages = withContext(Dispatchers.IO) {
            runCatching { host.basemapStore.packages() }.getOrDefault(emptyList())
        }
        val requested = preferredId ?: _state.value.activeBasemapId
        val active = packages.firstOrNull { it.id == requested } ?: packages.firstOrNull()
        val style = active?.let { pkg ->
            withContext(Dispatchers.IO) {
                runCatching { host.basemapStore.styleFor(pkg) }.getOrNull()
            }
        }
        update {
            it.copy(
                basemapPackages = packages,
                activeBasemapId = active?.id,
                activeBasemapStyle = style,
            )
        }
    }

    private fun performBasemapImport(uris: List<Uri>): InstallResult {
        val staged = mutableListOf<BasemapImport.StagedFile>()
        try {
            uris.forEach { uri -> staged += BasemapImport.stage(host.context, uri) }
            val archives = staged.filter { it.kind == BasemapImport.Kind.PMTILES }
            val styles = staged.filter { it.kind == BasemapImport.Kind.STYLE }
            val mbtiles = staged.filter { it.kind == BasemapImport.Kind.MBTILES }
            val unknown = staged.filter { it.kind == BasemapImport.Kind.UNKNOWN }

            if (mbtiles.isNotEmpty()) {
                return InstallResult.Rejected(
                    "MBTiles is not supported. Convert it with " +
                        "`pmtiles convert input.mbtiles output.pmtiles`, then select the " +
                        ".pmtiles archive and its .json style.",
                )
            }
            if (unknown.isNotEmpty()) {
                return InstallResult.Rejected(
                    "Could not identify ${unknown.joinToString { it.displayName }}. " +
                        "Select one .pmtiles archive and one MapLibre .json style.",
                )
            }
            if (archives.size != 1 || styles.size != 1) {
                return InstallResult.Rejected(
                    "Select exactly one .pmtiles archive and one .json style " +
                        "(found ${archives.size} archive(s) and ${styles.size} style(s)).",
                )
            }

            val archive = archives.single()
            val style = styles.single()
            if (style.sizeBytes > BasemapImport.MAX_STYLE_BYTES) {
                return InstallResult.Rejected(
                    "style is ${style.sizeBytes} bytes; the maximum is " +
                        "${BasemapImport.MAX_STYLE_BYTES} bytes",
                )
            }
            if (archive.sizeBytes > host.context.filesDir.usableSpace) {
                return InstallResult.Rejected(
                    "not enough internal storage for this archive " +
                        "(${archive.sizeBytes / (1024 * 1024)} MiB)",
                )
            }
            val baseName = archive.displayName.substringBeforeLast('.', archive.displayName)
            return host.basemapStore.install(
                id = archive.sha256,
                displayName = baseName.trim().ifBlank { "Offline basemap" }.take(120),
                stagedPath = archive.file.absolutePath,
                styleJson = style.file.readText(Charsets.UTF_8),
            )
        } finally {
            staged.forEach { runCatching { it.file.delete() } }
        }
    }

    fun loadRadar() {
        update { it.copy(status = "Loading radar…") }
        viewModelScope.launch {
            when (val result = host.radar.latestFrame()) {
                is ProviderResult.Success -> update {
                    it.copy(
                        radarFrame = result.data,
                        radarEnabled = result.data != null,
                        status = result.data?.let { frame -> "Radar ${frame.timestamp} (${frame.kind})" }
                            ?: "No radar frame available",
                    )
                }

                else -> update {
                    it.copy(radarEnabled = false, status = "Radar unavailable: ${describe(result)}")
                }
            }
        }
    }

    fun loadWeatherAlerts() {
        update { it.copy(status = "Loading weather alerts…", alertsEnabled = true) }
        refreshWeatherAlerts()
    }

    private fun refreshWeatherAlerts() {
        viewModelScope.launch {
            // The viewport is passed through deliberately. An earlier version called
            // alerts() with no bounds, which asked for the national active set and then
            // applied no filter at all, so a rider in Minnesota saw warnings for counties
            // they were not near. The provider needs the bounds to sweep and spatially
            // filter.
            val bounds = _state.value.scope.renderScope.viewport
            when (val result = host.weatherAlerts.alerts(bounds).first()) {
                is ProviderResult.Success -> update {
                    it.copy(weatherAlerts = result.data, status = "Weather alerts: ${result.data.size}")
                }
                is ProviderResult.PartialResult -> update {
                    it.copy(
                        weatherAlerts = result.data,
                        status = "Weather alerts: ${result.data.size} (partial coverage: ${result.missing.joinToString("; ")})",
                    )
                }

                else -> update { it.copy(status = "Weather alerts unavailable: ${describe(result)}") }
            }
        }
    }

    /**
     * Re-queries alerts after the map settles, not on every animation frame.
     *
     * Alerts are area warnings; panning to a new county can change the set. The
     * delay is deliberately longer than the scope debounce so a continuous pan
     * issues one request, not one per viewport update.
     */
    private fun scheduleAlertRefresh() {
        if (!_state.value.alertsEnabled) return
        alertsRefreshJob?.cancel()
        alertsRefreshJob = viewModelScope.launch {
            delay(ALERTS_VIEWPORT_DEBOUNCE_MS)
            refreshWeatherAlerts()
        }
    }

    /**
     * Switches between transit planning and a straight-line walk estimate.
     *
     * The previous sheet kept the mode in local Compose state, so tapping "Walk
     * only" changed a chip but Plan still ran the transit planner. The mode now
     * lives in the single state object and invalidates transit results.
     */
    fun setTravelMode(mode: TravelMode) {
        if (_state.value.travelMode == mode) return
        invalidatePlan()
        _state.update { state ->
            state.copy(
                travelMode = mode,
                walkEstimate = if (mode == TravelMode.WALK_ONLY) {
                    WalkEstimator.estimate(state.origin, state.destination)
                } else {
                    null
                },
                journeys = emptyList(),
                journeyWarnings = emptyList(),
                selectedJourneyId = null,
                planning = false,
                status = when (mode) {
                    TravelMode.TRANSIT -> "Transit mode"
                    TravelMode.WALK_ONLY -> "Walk mode: straight-line estimate, no routed path"
                },
            )
        }
    }

    fun planJourney() {
        val current = _state.value
        val request = current.toTripPlanRequest(Clock.System.now())
        if (current.travelMode == TravelMode.TRANSIT && request == null) {
            update { it.copy(status = "Choose an arrival time before planning") }
            return
        }
        // A new plan is a new arrival opportunity. Reset the one-shot cue so
        // the next selected journey can announce its own destination once.
        arrivalCuedJourneyId = null
        if (current.travelMode == TravelMode.WALK_ONLY) {
            val estimate = WalkEstimator.estimate(current.origin, current.destination)
            update {
                it.copy(
                    planning = false,
                    journeys = emptyList(),
                    journeyWarnings = emptyList(),
                    selectedJourneyId = null,
                    walkEstimate = estimate,
                    status = if (estimate.withinCoverage) {
                        "Walk estimate: ${estimate.meters.toInt()} m, about ${estimate.minutes} min"
                    } else {
                        "Outside walk coverage (${estimate.meters.toInt()} m); try transit"
                    },
                )
            }
            return
        }
        val network = current.staticFeed?.let { feed -> host.routingNetwork(feed) }
        if (network == null) {
            update { it.copy(status = "Load the static network before planning") }
            return
        }
        val engine = host.engineForRouting(network)
        update {
            it.copy(
                planning = true,
                status = "Planning…",
                walkEstimate = null,
                journeys = emptyList(),
                journeyWarnings = emptyList(),
            )
        }
        invalidatePlan()
        planningJob = viewModelScope.launch {
            // Planning searches the network and reads the stop-time index, so it
            // must not run on the main thread. AtlasEngine is the single path:
            // the ViewModel no longer owns transfer scoring.
            val outcome = withContext(Dispatchers.Default) {
                engine.planJourney(requireNotNull(request))
            }
            when (outcome) {
                is com.intellitrip.engine.JourneyOutcome.Planned -> {
                    val report = engine.evaluateTransfers(outcome.journeys)
                    update {
                        it.copy(
                            planning = false,
                            journeys = report.map { r -> r.journey },
                            journeyWarnings = report.flatMap { r -> r.warnings }.distinct(),
                            selectedJourneyId = null,
                            status = "${report.size} journeys planned",
                        )
                    }
                }
                is com.intellitrip.engine.JourneyOutcome.Unavailable ->
                    update { it.copy(planning = false, status = "Planning unavailable: ${outcome.reason}") }
            }
        }
    }

    fun toggleAutoRefresh() {
        val enabled = !_state.value.autoRefresh
        if (!enabled) {
            refreshJob?.cancel()
            refreshJob = null
            update { it.copy(autoRefresh = false, status = "Auto refresh off") }
            return
        }
        update { it.copy(autoRefresh = true, status = "Auto refresh on (every 15s)") }
        refreshJob = viewModelScope.launch {
            var failures = 0
            while (isActive) {
                delay(backoffMs(failures))
                val vehiclesDeferred = async { host.realtime.vehiclePositions().first() }
                val updatesDeferred = async { host.realtime.tripUpdates().first() }
                when (val result = vehiclesDeferred.await()) {
                    is ProviderResult.Success -> { failures = 0; onVehicles(result.data, result.freshness.fetchedAt, result.freshness.age.inWholeSeconds) }
                    is ProviderResult.StaleSuccess -> { failures = 0; onVehicles(result.data, result.freshness.fetchedAt, result.freshness.age.inWholeSeconds) }
                    is ProviderResult.RateLimited -> {
                        failures++
                        update { it.copy(status = "Refresh rate-limited (backing off ${backoffMs(failures) / 1000}s)") }
                    }
                    else -> {
                        failures++
                        update { it.copy(status = "Refresh failed: ${describe(result)}") }
                    }
                }
                when (val result = updatesDeferred.await()) {
                    is ProviderResult.Success ->
                        onTripUpdates(result.data, result.freshness.fetchedAt)
                    is ProviderResult.StaleSuccess ->
                        onTripUpdates(result.data, result.freshness.fetchedAt)
                    else -> update {
                        it.copy(status = "Live predictions unavailable: ${describe(result)}")
                    }
                }
            }
        }
    }

    /** The selected journey as map geometry; empty when there is nothing to draw. */
    /**
     * Turns the radar overlay on or off.
     *
     * Switching off clears the frame as well as the flag. Leaving the frame in state
     * would let any later render re-add the layer, so the overlay would reappear
     * without the rider asking for it.
     */
    fun toggleRadar() {
        val enabled = !_state.value.radarEnabled
        if (!enabled) {
            update { it.copy(radarEnabled = false, radarFrame = null, status = "Radar off") }
            return
        }
        loadRadar()
    }

    /** Records what the rider tapped on the map. */
    fun selectFeature(selection: com.intellitrip.map.MapSelection) {
        update { it.copy(mapSelection = selection) }
    }

    fun clearSelection() {
        update { it.copy(mapSelection = com.intellitrip.map.MapSelection.None) }
    }

    /**
     * Saves or unsaves a stop.
     *
     * Saved stops are written immediately rather than on exit, so a crash or a force
     * stop cannot lose them. This is the difference between a list a rider trusts and
     * one they have to re-check every launch.
     */
/**
     * Saves a stop the rider chose from the map.
     *
     * Saving genuinely needs the live feed object, because that is where the stop's
     * current name and coordinates come from.
     */
    fun saveStop(stop: com.intellitrip.domain.Stop) {
        applySave { savedPlaces.save(com.intellitrip.ui.SavedPlacesStore.from(stop)) }
            ?.let { saved ->
                update { it.copy(savedStops = saved, status = "Saved ${stop.name}") }
            }
    }

    /**
     * Removes a saved stop using only its stored key.
     *
     * Removal must never depend on the transit feed. The previous implementation looked
     * the stop up in the loaded network and did nothing when it was absent, so a saved
     * stop became impossible to delete while offline, after a feed change, or whenever
     * the snapshot had been cleared. The stored record already carries everything
     * needed to delete it.
     */
    fun removeSavedStop(saved: com.intellitrip.ui.SavedStop) {
        applySave { savedPlaces.remove(saved.feedId, saved.stopId) }
            ?.let { remaining ->
                update { it.copy(savedStops = remaining, status = "Removed ${saved.name}") }
            }
    }

    /** Adds or removes a stop that the map has in hand. */
    fun toggleSavedStop(stop: com.intellitrip.domain.Stop) {
        val existing = _state.value.savedStops.firstOrNull {
            it.stopId == stop.key.stopId &&
                (it.feedId == stop.key.feedId.value || it.feedId == com.intellitrip.ui.SavedPlacesStore.LEGACY_FEED)
        }
        if (existing != null) removeSavedStop(existing) else saveStop(stop)
    }

    /**
     * Runs a storage mutation, converting failure into rider-visible state.
     *
     * On failure the previously loaded list is left in place, so the screen keeps
     * showing what is actually stored rather than optimistically dropping an entry that
     * was never deleted.
     */
    private fun applySave(block: () -> List<com.intellitrip.ui.SavedStop>): List<com.intellitrip.ui.SavedStop>? =
        try {
            block()
        } catch (e: com.intellitrip.ui.SavedPlacesWriteException) {
            update { it.copy(status = "Could not update saved places. ${e.message}") }
            null
        } catch (e: java.io.IOException) {
            update { it.copy(status = "Could not update saved places: ${e.message ?: "storage unavailable"}") }
            null
        }

    /**
     * Re-scopes records written before feed ids were stored.
     *
     * Called once the feed is available so a rider's existing saved stops keep working
     * across the update instead of appearing to vanish.
     */
    private fun adoptLegacySavedStops(feedId: String, stops: List<com.intellitrip.domain.Stop>) {
        val known = stops.mapTo(mutableSetOf()) { it.key.stopId }
        if (_state.value.savedStops.none { it.feedId == com.intellitrip.ui.SavedPlacesStore.LEGACY_FEED }) return
        val (rescoped, changed) = savedPlaces.adoptFeedScope(feedId, known)
        if (changed) update { it.copy(savedStops = rescoped) }
    }

    /** Focuses the map on a stop and selects it (departure-board tap). */
    fun focusStop(stopKey: com.intellitrip.domain.StopKey) {
        // Resolve by key, not name: a GTFS feed routinely has several stops
        // named "Main St" (opposite directions, station platforms).
        val stop = _state.value.staticFeed?.stops?.firstOrNull { it.key == stopKey } ?: return
        setScope(stop.name, com.intellitrip.domain.Focus.Radius(stop.location, 300.0))
        update {
            it.copy(
                mapSelection = com.intellitrip.map.MapSelection.Single(
                    com.intellitrip.map.MapFeature.Stop(
                        stop.key.feedId.value, stop.key.stopId, stop.name,
                    ),
                ),
            )
        }
    }

    /** Follows a vehicle on the map. */
    fun trackVehicle(vehicleKey: com.intellitrip.domain.VehicleKey) {
        val follow = VehicleFollow.update(vehicleKey, _state.value.vehicles, Clock.System.now())
        val focus = Focus.Vehicle(vehicleKey, follow.target)
        _state.update {
            it.copy(
                scopeLabel = "Vehicle",
                focus = focus,
                scope = host.scopeEngine.currentScope(focus),
                trackedVehicleKey = vehicleKey,
                followTarget = follow.target,
                trackingStatus = follow.status,
                status = follow.status,
            )
        }
    }

    /**
     * Stops following without changing the camera.
     *
     * The rider may still want to look at the vehicle's last position; the map
     * simply stops moving with it.
     */
    fun stopTrackingVehicle() {
        if (_state.value.trackedVehicleKey == null) return
        _state.update {
            it.copy(
                trackedVehicleKey = null,
                followTarget = null,
                trackingStatus = null,
                status = "Stopped following vehicle",
            )
        }
    }

    /** Moves the map to a saved stop and selects it. */
    fun openSavedStop(stop: com.intellitrip.ui.SavedStop) {
        setScope(stop.name, com.intellitrip.domain.Focus.Radius(com.intellitrip.ui.SavedPlacesStore.locationOf(stop), 400.0))
        update {
            it.copy(
                mapSelection = com.intellitrip.map.MapSelection.Single(
                    com.intellitrip.map.MapFeature.Stop(stop.feedId, stop.stopId, stop.name),
                ),
            )
        }
    }

    /** Looks up a saved stop in the loaded feed so detail can show its departures. */
    fun savedStopDomain(saved: com.intellitrip.ui.SavedStop): com.intellitrip.domain.Stop? =
        _state.value.staticFeed?.stops?.firstOrNull {
            it.key.feedId.value == saved.feedId && it.key.stopId == saved.stopId
        }

    fun routeGeometry(): List<RouteLine> {
        // A walk-only answer is an estimate, not a routed line. Drawing a straight
        // line would read as a street route the app cannot produce.
        if (_state.value.travelMode != TravelMode.TRANSIT) return emptyList()
        val feed = _state.value.staticFeed ?: return emptyList()
        val journey = selectedJourney ?: return emptyList()
        return RouteGeometry.build(
            journey = journey,
            shapes = feed.shapes,
            stops = feed.stops,
            trips = feed.trips,
        )
    }

    /** The journey currently drawn, falling back to the best one available. */
    private val selectedJourney: JourneyOption?
        get() = _state.value.activeJourney()

    /**
     * Builds the Explore departure board from the schedule actually stored on device.
 *
     * Reads stop times through the disk index rather than from memory: the feed's
     * stop_times table is the largest part of the archive and is deliberately not
     * retained. Returns an empty list when there is no feed or no index yet, which
     * the UI presents as "download schedules" rather than as a blank list.
     *
     * A departure is marked live only when a fresh, stop-specific GTFS-Realtime
     * prediction matches its trip, stop and service date. A reporting vehicle
     * with no stop ETA is shown as Schedule; an estimated arrival with no data
     * behind it is worse than admitting the time comes from the timetable.
     *
     * Frequency-based trips are expanded at headway intervals (capped, 2 h
     * horizon) the same way the planner expands them, so the board shows the
     * next real departures, not the template time.
     */
    fun nearbyDepartures(now: Instant = Clock.System.now()): List<com.intellitrip.ui.Departure> {
        val current = _state.value
        val feed = current.staticFeed ?: return emptyList()
        val reader = host.snapshotStore.stopTimeReader(host.feedId) ?: return emptyList()

        val origin = current.userLocation?.position
        val liveTrips = current.vehicles
            .filter { it.tripKey != null && isLiveVehicle(it, now) }
            .mapNotNull { it.tripKey?.tripId }
            .toSet()
        val routesByKey = feed.routes.associate { it.key.routeId to it }
        val tripsByKey = feed.trips.associate { it.key.tripId to it }
        val frequenciesByTrip = feed.frequencies.groupBy { it.tripKey.tripId }
        // GTFS times are agency-local, not device-local. Use the feed's zone so
        // a device in another timezone does not shift every departure.
        val agencyZone = feed.agencies.firstOrNull { it.timezone != null }?.timezone
            ?: TimeZone.of("America/Chicago")
        // Service-day candidates mirror DepartureBoard.resolveServiceTime's
        // -1..+1 window, so a 25:30 trip's service day is considered.
        val todayAgency = now.toLocalDateTime(agencyZone).date
        val candidateDates = (-1..1).map { todayAgency.plus(it, DateTimeUnit.DAY) }

        // Distances computed once per stop (filter/sort shared one walk):
        // the haversine is trig-heavy and was run twice per stop.
        val stopsToScan = origin?.let { point ->
            feed.stops
                .map { it to com.intellitrip.ui.DepartureBoard.metersBetween(point, it.location) }
                .filter { (_, meters) -> meters <= NEARBY_RADIUS_METERS }
                .sortedBy { (_, meters) -> meters }
                .map { (stop, _) -> stop }
                .take(NEARBY_STOP_SCAN_LIMIT)
        } ?: feed.stops.take(NEARBY_STOP_SCAN_LIMIT)

        // In-memory reader (bytes, no channel to close); `use` no longer
        // applies since the KMP port dropped Closeable. See StopTimeIndex.
        return run {
            val index = reader
            // Template first-departure per frequency trip, cached for this board.
            val firstDepartureSec = mutableMapOf<String, Int>()
            com.intellitrip.ui.DepartureBoard.build(
                now = now,
                origin = origin,
                stops = stopsToScan,
                radiusMeters = NEARBY_RADIUS_METERS,
                departuresFor = { stop ->
                    index.tripsServing(stop.key).flatMap { tripKey ->
                        com.intellitrip.ui.DepartureBoard.boardableRowsAt(
                            index.stopTimesFor(tripKey) { stopId ->
                                com.intellitrip.domain.StopKey(feed.feedId, stopId)
                            },
                            stop.key,
                        )
                    }.flatMap { stopTime ->
                        val trip = tripsByKey[stopTime.tripKey.tripId] ?: return@flatMap emptyList()
                        val scheduled = stopTime.departureTime ?: stopTime.arrivalTime
                            ?: return@flatMap emptyList()
                        val activeDates = candidateDates.filter { date ->
                            com.intellitrip.ui.DepartureBoard.isServiceActive(
                                feed.calendars, feed.calendarDates, trip.serviceKey, date,
                            )
                        }
                        if (activeDates.isEmpty()) return@flatMap emptyList()
                        val frequencies = frequenciesByTrip[stopTime.tripKey.tripId].orEmpty()
                        val firstSec = if (frequencies.isEmpty()) {
                            null
                        } else {
                            firstDepartureSec.getOrPut(stopTime.tripKey.tripId) {
                                index.stopTimesFor(stopTime.tripKey) { stopId ->
                                    com.intellitrip.domain.StopKey(feed.feedId, stopId)
                                }.minByOrNull { it.stopSequence }
                                    ?.let { it.departureTime ?: it.arrivalTime }
                                    ?.secondsSinceServiceDayStart
                                    ?: scheduled.secondsSinceServiceDayStart
                            }
                        }
                        com.intellitrip.ui.DepartureBoard.occurrences(
                            tripId = stopTime.tripKey.tripId,
                            stopId = stopTime.stopKey.stopId,
                            serviceTime = scheduled,
                            activeDates = activeDates,
                            zone = agencyZone,
                            now = now,
                            frequencies = frequencies,
                            firstDepartureSeconds = firstSec,
                        ).map { occurrence ->
                            val date = occurrence.serviceDate ?: return@map occurrence
                            val prediction = DepartureRealtime.resolve(
                                updates = current.tripUpdates,
                                tripId = occurrence.tripId,
                                stopId = occurrence.stopId ?: stopTime.stopKey.stopId,
                                serviceDate = date,
                                scheduled = occurrence.departure,
                                now = now,
                                fetchedAt = current.tripUpdatesFetchedAt,
                                frequencyRunStartSeconds = occurrence.frequencyRunStartSeconds,
                            )
                            occurrence.copy(
                                reportedTime = prediction?.time,
                                reportedAt = prediction?.reportedAt,
                                status = prediction?.status
                                    ?: com.intellitrip.ui.DepartureStatus.SCHEDULED,
                            )
                        }
                    }.sortedBy { it.departure }
                },
                liveTrips = liveTrips,
                routesByKey = routesByKey,
                tripsByKey = tripsByKey,
                zone = agencyZone,
                headwayMinutesByTrip = frequenciesByTrip.mapValues { (_, v) ->
                    v.firstOrNull()?.let { it.headwaySeconds / 60 } ?: 0
                }.filterValues { it > 0 },
            )
        }
    }


    /** Re-frames the map on the default view, after a gesture moved it elsewhere. */
    fun recenter(point: GeoPoint) {
        setScope("System", Focus.Radius(point, 3_218.0))
    }

    /**
     * Centers on the user and starts tracking. Permission is requested here, from
     * an explicit tap, never at startup — a map app that asks for location before
     * the user has touched anything is the behavior people distrust.
     */
    fun onLocateMe(permissionGranted: Boolean) {
        if (!permissionGranted) {
            update { it.copy(locationDenied = true, status = "Location permission denied") }
            return
        }
        update { it.copy(locationDenied = false, status = "Locating…") }
        // Tapping locate means "show me where I am", so it always re-centers on the
        // first fix regardless of the current scope. Only *subsequent* fixes respect
        // the follow limits below.
        var firstFix = true
        locationJob?.cancel()
        locationJob = viewModelScope.launch {
            host.location.positions().collect { fix ->
                update { it.copy(userLocation = fix, status = locationStatus(fix)) }
                // Arrival cue (V2): within 100m of the active journey's alight.
                val journey = _state.value.activeJourney()
                if (journey != null && fix.isFresh(Clock.System.now())) {
                    journey.legs.lastOrNull()?.let { last ->
                        val dest = when (last) {
                            is com.intellitrip.domain.Leg.Transit -> _state.value.staticFeed?.stops?.firstOrNull { it.key == last.toStopKey }?.location
                            is com.intellitrip.domain.Leg.WalkLeg -> last.to
                            is com.intellitrip.domain.Leg.Other -> null
                        }
                        if (dest != null) {
                            val dist = com.intellitrip.ui.DepartureBoard.metersBetween(fix.position, dest)
                            if (dist < 100) com.intellitrip.ui.ArrivalCue.playIfAllowed(host.context, true)
                        }
                    }
                }
                // A stale fix is shown as "last known" but must never move the camera. Indoors
                // the platform hands back a fix hours or days old; framing on it would
                // fling the map somewhere the user is not and cannot undo.
                //
                // Precision is deliberately not part of this test. A rider who grants
                // only approximate location still expects the map to move when they
                // tap locate; refusing to center for them reads as a dead button.
                if (!fix.shouldCenterCamera()) return@collect
                val current = _state.value
                val isAreaView = current.focus is Focus.Radius && current.focus.meters <= FOLLOW_METERS
                if (firstFix || isAreaView) {
                    firstFix = false
                    setScope("You", Focus.Radius(fix.position, followRadius(fix)))
                }
            }
        }
    }

    /** Says what the fix actually is, rather than implying it is live. */
    private fun locationStatus(fix: UserLocation): String = fix.describe()

    fun stopFollowing() {
        locationJob?.cancel()
        locationJob = null
    }

    /**
     * Radius that frames the user at their own accuracy, with a margin.
     *
     * Capped well below the System extent: a poor indoor fix can report hundreds of
     * meters, and a viewport sized to that would be indistinguishable from no zoom.
     */
    private fun followRadius(fix: UserLocation): Double =
        (fix.accuracyMeters * 3).coerceIn(MIN_FOLLOW_METERS, MAX_FOLLOW_METERS)

    /** Selects a journey, draws it on the map, and frames it. */
    fun selectJourney(id: String) {
        if (_state.value.selectedJourneyId != id) arrivalCuedJourneyId = null
        _state.update { it.copy(selectedJourneyId = id) }
        val focus = focusJourney(id) ?: return
        setScope("Selected journey", focus)
    }

    /** Frames a journey's extent on the map so the plan is visible without hunting. */
    fun focusJourney(id: String): Focus.Radius? {
        val lines = routeGeometry().ifEmpty { return null }
        val lats = lines.flatMap { line -> line.coordinates.map { it[1] } }
        val lons = lines.flatMap { line -> line.coordinates.map { it[0] } }
        val center = GeoPoint(
            (lats.min() + lats.max()) / 2,
            (lons.min() + lons.max()) / 2,
        )
        // Generous margin so the drawn line is not flush against the screen edge.
        val meters = METERS_PER_LAT_DEGREE * (lats.max() - lats.min()) * 0.75
        return Focus.Radius(center, meters.coerceIn(300.0, 60_000.0))
    }

    fun offlineCapabilities() = host.offlineRegistry.capabilities(
        feedId = host.feedId,
        realtimeEntry = _state.value.vehiclesFetchedAt?.let { CacheEntry(FeedId(FEED_ID), it) },
        basemap = com.intellitrip.offline.BasemapPolicy(
            onlineEnabled = true,
            offlineInstalled = runCatching { host.basemapStore.hasPackage() }.getOrDefault(false),
        ).availability(),
    )

    private fun onTripUpdates(updates: List<com.intellitrip.domain.TripUpdate>, fetchedAt: Instant) {
        _state.update {
            it.copy(
                tripUpdates = updates,
                tripUpdatesFetchedAt = fetchedAt,
            )
        }
    }

    private fun onVehicles(vehicles: List<Vehicle>, fetchedAt: Instant, ageSeconds: Long) {
        val now = Clock.System.now()
        _state.update { state ->
            val tracked = state.trackedVehicleKey
            val follow = tracked?.let { VehicleFollow.update(it, vehicles, now) }
            // Keep the data scope around the latest fix as well, so the followed
            // vehicle cannot drive out of its own render bounds. The camera effect
            // deliberately ignores Focus.Vehicle; followTarget owns the camera.
            val focus = if (tracked != null && follow?.target != null) {
                Focus.Vehicle(tracked, follow.target)
            } else {
                state.focus
            }
            state.copy(
                vehicles = vehicles,
                vehiclesFetchedAt = fetchedAt,
                focus = focus,
                scope = if (focus != state.focus) host.scopeEngine.currentScope(focus) else state.scope,
                followTarget = follow?.target,
                trackingStatus = follow?.status ?: state.trackingStatus,
                status = follow?.status ?: "Live vehicles: ${vehicles.size} (${ageSeconds}s old)",
            )
        }
    }

    private fun update(block: (AppState) -> AppState) {
        // Atomic CAS so concurrent launches (plan, radar, polls) cannot clobber
        // each other's status via a read-modify-write race on _state.value.
        _state.update(block)
    }

    private fun invalidatePlan() {
        planningJob?.cancel()
        planningJob = null
    }

    /** Whether a vehicle report is recent enough to represent a live service. */
    private fun isLiveVehicle(
        vehicle: com.intellitrip.domain.Vehicle,
        now: Instant,
    ): Boolean {
        val age = (now - vehicle.updatedAt).inWholeSeconds
        return age in 0..LIVE_VEHICLE_MAX_AGE_SECONDS
    }

    private fun describe(result: ProviderResult<*>): String = when (result) {
        is ProviderResult.NetworkFailure -> "${result.error.message} [${result.error.cause?.javaClass?.simpleName}]"
        is ProviderResult.MalformedResponse -> "${result.error.message} [${result.error.cause?.javaClass?.simpleName}]"
        is ProviderResult.Unavailable -> result.error.message ?: "provider unavailable"
        is ProviderResult.RateLimited -> "rate limited"
        is ProviderResult.AuthFailure -> result.error.message ?: "authentication failure"
        is ProviderResult.Timeout -> result.error.message ?: "timeout"
        else -> "no result"
    }

    companion object {
        /** Roughly a ten-minute walk; enough stops to be useful, few enough to stay cheap. */
        private const val NEARBY_RADIUS_METERS = 1_000.0

        /**
         * Caps how many stops are opened for schedule lookups.
         *
         * Each scanned stop means a disk read per trip serving it, so this is the
         * difference between a board that appears instantly and one that blocks the
         * main thread on a large feed.
         */
        private const val NEARBY_STOP_SCAN_LIMIT = 12

        /**
         * How recent a vehicle report must be before it is called live.
         *
         * Older than this the position is shown as a stale marker rather than counted
         * as a prediction, so a departure is never upgraded to "Live" on the strength
         * of a report from several minutes ago.
         */
        private const val LIVE_VEHICLE_MAX_AGE_SECONDS = 120L

        /** Metres of latitude spanned by one degree at the equator. */
        private const val METERS_PER_LAT_DEGREE = 111_320.0

        /** Settles a camera gesture before it changes scope, so panning stays smooth. */
        private const val VIEWPORT_DEBOUNCE_MS = 250L

        /** One alert request per settled pan, not one per frame. */
        private const val ALERTS_VIEWPORT_DEBOUNCE_MS = 2_000L

        /** Above this the camera stops following, so System view stays put. */
        private const val FOLLOW_METERS = 5_000.0
        private const val MIN_FOLLOW_METERS = 500.0
        private const val MAX_FOLLOW_METERS = 4_000.0

        fun factory(host: AtlasHost): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    IntelliTripViewModel(host) as T
            }

        /**
         * Poll interval with exponential backoff on consecutive failures.
         * 15 s base, doubling per failure, capped at 120 s. Rate limits must
         * not be retried at a fixed 15 s cadence.
         */
        internal fun backoffMs(failures: Int): Long =
            (15_000L * (1L shl failures.coerceIn(0, 3))).coerceAtMost(120_000L)
    }
}

/**
 * Applies new trip endpoints and discards the plan they produced.
 *
 * Endpoints are the input to a plan, so changing either one invalidates any result
 * still on screen. Leaving the previous journeys visible would present a route
 * between the old endpoints as though it answered the new question, which is worse
 * than showing nothing. The user re-runs the plan explicitly.
 *
 * This is a no-op when the endpoints are unchanged, so selecting the same place
 * twice does not throw away a valid plan.
 *
 * Deliberately a top-level pure function rather than a private method on the view
 * model: it holds the staleness rule, and a top-level function can be exercised by
 * a plain JVM test without instantiating Android lifecycle classes.
 */
internal fun AppState.withEndpoints(
    origin: GeoPoint = this.origin,
    destination: GeoPoint = this.destination,
): AppState = if (origin == this.origin && destination == this.destination) {
    this
} else {
    copy(
        origin = origin,
        destination = destination,
        journeys = emptyList(),
        journeyWarnings = emptyList(),
        selectedJourneyId = null,
        // The estimate is derived from the endpoints, so new endpoints must
        // produce a new estimate rather than leaving the old distance on screen.
        walkEstimate = if (travelMode == TravelMode.WALK_ONLY) {
            WalkEstimator.estimate(origin, destination)
        } else {
            null
        },
    )
}

internal fun AppState.withPlanningTime(
    mode: PlanningTimeMode,
    time: Instant?,
): AppState = if (mode == planningTimeMode && time == planningTime) {
    this
} else {
    copy(
        planningTimeMode = mode,
        planningTime = time,
        journeys = emptyList(),
        journeyWarnings = emptyList(),
        selectedJourneyId = null,
        planning = false,
    )
}

internal fun AppState.toTripPlanRequest(now: Instant): TripPlanRequest? =
    when (planningTimeMode) {
        PlanningTimeMode.DEPART_AT -> TripPlanRequest(
            origin = origin,
            destination = destination,
            departAt = planningTime ?: now + 2.minutes,
            arriveBy = null,
            maxTransfers = 2,
        )
        PlanningTimeMode.ARRIVE_BY -> planningTime?.let { time ->
            TripPlanRequest(
                origin = origin,
                destination = destination,
                departAt = null,
                arriveBy = time,
                maxTransfers = 2,
            )
        }
    }

/**
 * The single journey drawn on the map, or null when there is no plan.
 *
 * An explicit selection wins; otherwise the earliest arrival is shown, so a plan is
 * never invisible just because the rider has not tapped a card. A selection that no
 * longer exists in [journeys] falls back rather than rendering nothing.
 *
 * The planner sheet previously recomputed this inline, which let the highlighted
 * card and the drawn route disagree. There is now one rule and one implementation.
 */
internal fun AppState.activeJourney(): JourneyOption? {
    if (journeys.isEmpty()) return null
    return journeys.firstOrNull { it.id == selectedJourneyId } ?: journeys.minByOrNull { it.arrival }
}

/** The id of the drawn journey, for callers that need to highlight it. */
internal fun AppState.activeJourneyId(): String? = activeJourney()?.id
