package com.intellitrip

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.intellitrip.contracts.ProviderResult
import com.intellitrip.domain.FeedId
import com.intellitrip.domain.Focus
import com.intellitrip.domain.GeoPoint
import com.intellitrip.domain.JourneyOption
import com.intellitrip.map.RouteGeometry
import com.intellitrip.map.RouteLine
import com.intellitrip.domain.RadarFrame
import com.intellitrip.domain.ScopeState
import com.intellitrip.domain.TripPlanRequest
import com.intellitrip.domain.Vehicle
import com.intellitrip.domain.WeatherAlert
import com.intellitrip.gtfs.RefreshOutcome
import com.intellitrip.gtfs.StaticFeed
import com.intellitrip.offline.BasemapAvailability
import com.intellitrip.offline.CacheEntry
import com.intellitrip.routing.RoutingNetwork
import com.intellitrip.routing.TransferIntelligenceService
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AppState(
    val status: String = "Idle",
    val scopeLabel: String = "System",
    val focus: Focus? = Focus.System,
    val scope: ScopeState? = null,
    val vehicles: List<Vehicle> = emptyList(),
    val vehiclesFetchedAt: Instant? = null,
    val staticFeed: StaticFeed? = null,
    val radarFrame: RadarFrame? = null,
    val radarEnabled: Boolean = false,
    val weatherAlerts: List<WeatherAlert> = emptyList(),
    val journeys: List<JourneyOption> = emptyList(),
    /** The journey drawn on the map; defaults to the earliest arrival. */
    val selectedJourneyId: String? = null,
    val journeyWarnings: List<String> = emptyList(),
    val planning: Boolean = false,
    val origin: GeoPoint = GeoPoint(DEFAULT_LAT, DEFAULT_LON),
    val destination: GeoPoint = GeoPoint(DEFAULT_DEST_LAT, DEFAULT_DEST_LON),
    val autoRefresh: Boolean = false,
)

/**
 * Holds all screen state. Network work always runs on a background dispatcher and
 * results are surfaced through [AppState]; nothing blocks the main thread.
 */
class IntelliTripViewModel(private val host: AtlasHost) : ViewModel() {

    private val _state = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = _state.asStateFlow()

    private var viewportJob: Job? = null

    private var refreshJob: Job? = null

    init {
        // Start from any previously stored snapshot so a cold launch has the
        // static network without touching the network.
        viewModelScope.launch {
            host.offlineRegistry.loadStaticFeed(host.feedId)?.let { feed ->
                _state.value = _state.value.copy(
                    staticFeed = feed,
                    status = "Loaded offline snapshot (${feed.stops.size} stops)",
                    scope = host.scopeEngine.currentScope(_state.value.focus),
                )
            }
        }
    }

    fun setScope(label: String, focus: Focus?) {
        _state.value = _state.value.copy(
            scopeLabel = label,
            focus = focus,
            scope = host.scopeEngine.currentScope(focus),
        )
    }

    /**
     * Adopts the map viewport as the active scope, so panning the map rescales the
     * rendered network. Gestures arrive on every animation frame, so this is
     * debounced and skips updates that would not change the scope meaningfully.
     */
    fun onViewportChanged(lat: Double, lon: Double, zoom: Double) {
        val metres = METERS_PER_LAT_DEGREE * 360.0 / (1 shl zoom.coerceIn(3.0, 20.0).toInt())
        val focus = Focus.Radius(GeoPoint(lat, lon), metres.coerceIn(200.0, 60_000.0))
        val current = _state.value.focus
        if (current is Focus.Radius) {
            val sameCentre = current.center.lat == lat && current.center.lon == lon
            // Within ~2% the label and bounds would churn without telling the user
            // anything new.
            if (sameCentre && kotlin.math.abs(current.meters - focus.meters) < metres * 0.02) return
        }
        viewportJob?.cancel()
        viewportJob = viewModelScope.launch {
            delay(VIEWPORT_DEBOUNCE_MS)
            setScope("Map viewport", focus)
        }
    }

    fun setOrigin(point: GeoPoint) = update { it.copy(origin = point) }

    fun setDestination(point: GeoPoint) = update { it.copy(destination = point) }

    fun loadStaticNetwork() {
        update { it.copy(status = "Downloading static GTFS…") }
        viewModelScope.launch {
            when (val outcome = host.staticRefresher.refresh()) {
                is RefreshOutcome.Activated -> update {
                    it.copy(
                        staticFeed = outcome.feed,
                        scope = host.scopeEngine.currentScope(it.focus),
                        status = "Static network: ${outcome.feed.routes.size} routes, " +
                            "${outcome.feed.stops.size} stops, ${outcome.feed.trips.size} trips",
                    )
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
            when (val result = host.realtime.vehiclePositions().first()) {
                is ProviderResult.Success -> onVehicles(result.data, result.freshness.fetchedAt, result.freshness.age.seconds)
                is ProviderResult.StaleSuccess -> onVehicles(result.data, result.freshness.fetchedAt, result.freshness.age.seconds)
                else -> update { it.copy(status = "Live transit unavailable: ${describe(result)}") }
            }
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
        update { it.copy(status = "Loading weather alerts…") }
        viewModelScope.launch {
            when (val result = host.weatherAlerts.alerts().first()) {
                is ProviderResult.Success -> update {
                    it.copy(weatherAlerts = result.data, status = "Weather alerts: ${result.data.size}")
                }

                else -> update { it.copy(status = "Weather alerts unavailable: ${describe(result)}") }
            }
        }
    }

    fun planJourney() {
        val current = _state.value
        val network = current.staticFeed?.let { feed -> host.routingNetwork(feed) }
        if (network == null) {
            update { it.copy(status = "Load the static network before planning") }
            return
        }
        val planner = host.routingPlanner(network) ?: return
        update { it.copy(planning = true, status = "Planning…", journeys = emptyList(), journeyWarnings = emptyList()) }
        viewModelScope.launch {
            // Planning searches the network and reads the stop-time index, so it
            // must not run on the main thread.
            val result = withContext(Dispatchers.Default) {
                planner.plan(TripPlanRequest(
                    origin = current.origin,
                    destination = current.destination,
                    departAt = Instant.now().plusSeconds(120),
                    arriveBy = null,
                    maxTransfers = 2,
                ))
            }
            when (result) {
                is ProviderResult.Success -> {
                    val journeys: List<com.intellitrip.domain.JourneyOption> = result.data
                    val report = TransferIntelligenceService().evaluate(
                        journeys = journeys,
                        updates = emptyList(),
                    )
                    update {
                        it.copy(
                            planning = false,
                            journeys = report.map { r -> r.journey },
                            journeyWarnings = report.flatMap { r -> r.warnings }.distinct(),
                            // A new plan replaces the previous selection rather than
                            // leaving an id that no longer matches any journey.
                            selectedJourneyId = null,
                            status = "${report.size} journeys planned",
                        )
                    }
                }

                else -> update { it.copy(planning = false, status = "Planning unavailable: ${describe(result)}") }
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
            while (isActive) {
                delay(15_000)
                when (val result = host.realtime.vehiclePositions().first()) {
                    is ProviderResult.Success -> onVehicles(result.data, result.freshness.fetchedAt, result.freshness.age.seconds)
                    is ProviderResult.StaleSuccess -> onVehicles(result.data, result.freshness.fetchedAt, result.freshness.age.seconds)
                    else -> update { it.copy(status = "Refresh failed: ${describe(result)}") }
                }
            }
        }
    }

    /** The selected journey as map geometry; empty when there is nothing to draw. */
    fun routeGeometry(): List<RouteLine> {
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
        get() {
            val journeys = _state.value.journeys
            if (journeys.isEmpty()) return null
            val id = _state.value.selectedJourneyId
            return journeys.firstOrNull { it.id == id } ?: journeys.minByOrNull { it.arrival }
        }

    /** Selects a journey, draws it on the map, and frames it. */
    fun selectJourney(id: String) {
        _state.value = _state.value.copy(selectedJourneyId = id)
        val focus = focusJourney(id) ?: return
        setScope("Selected journey", focus)
    }

    /** Frames a journey's extent on the map so the plan is visible without hunting. */
    fun focusJourney(id: String): Focus.Radius? {
        val lines = routeGeometry().ifEmpty { return null }
        val lats = lines.flatMap { line -> line.coordinates.map { it[1] } }
        val lons = lines.flatMap { line -> line.coordinates.map { it[0] } }
        val centre = GeoPoint(
            (lats.min() + lats.max()) / 2,
            (lons.min() + lons.max()) / 2,
        )
        // Generous margin so the drawn line is not flush against the screen edge.
        val metres = METERS_PER_LAT_DEGREE * (lats.max() - lats.min()) * 0.75
        return Focus.Radius(centre, metres.coerceIn(300.0, 60_000.0))
    }

    fun offlineCapabilities() = host.offlineRegistry.capabilities(
        feedId = host.feedId,
        realtimeEntry = _state.value.vehiclesFetchedAt?.let { CacheEntry(FeedId(FEED_ID), it) },
        basemap = BasemapAvailability.OnlineOnly,
    )

    private fun onVehicles(vehicles: List<Vehicle>, fetchedAt: Instant, ageSeconds: Long) {
        update {
            it.copy(
                vehicles = vehicles,
                vehiclesFetchedAt = fetchedAt,
                status = "Live vehicles: ${vehicles.size} (${ageSeconds}s old)",
            )
        }
    }

    private fun update(block: (AppState) -> AppState) {
        _state.value = block(_state.value)
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
        /** Metres of latitude spanned by one degree at the equator. */
        private const val METERS_PER_LAT_DEGREE = 111_320.0

        /** Settles a camera gesture before it changes scope, so panning stays smooth. */
        private const val VIEWPORT_DEBOUNCE_MS = 250L

        fun factory(host: AtlasHost): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    IntelliTripViewModel(host) as T
            }
    }
}