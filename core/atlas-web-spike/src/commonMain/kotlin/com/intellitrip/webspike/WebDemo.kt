package com.intellitrip.webspike

import com.intellitrip.contracts.ProviderResult
import com.intellitrip.domain.Agency
import com.intellitrip.domain.AgencyId
import com.intellitrip.domain.FeedId
import com.intellitrip.domain.FeedMetadata
import com.intellitrip.domain.GeoPoint
import com.intellitrip.domain.GtfsServiceTime
import com.intellitrip.domain.Route
import com.intellitrip.domain.RouteKey
import com.intellitrip.domain.ServiceKey
import com.intellitrip.domain.Stop
import com.intellitrip.domain.StopKey
import com.intellitrip.domain.StopTime
import com.intellitrip.domain.Trip
import com.intellitrip.domain.TripKey
import com.intellitrip.domain.TripPlanRequest
import com.intellitrip.engine.AtlasEngine
import com.intellitrip.engine.AtlasProviders
import com.intellitrip.map.CameraView
import com.intellitrip.map.RenderInput
import com.intellitrip.map.RenderSnapshot
import com.intellitrip.map.TransitRenderPipeline
import com.intellitrip.routing.RoutingNetwork
import com.intellitrip.routing.StaticNetworkPlanner
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant

/**
 * First real screen, shared by JVM (tests) and browser (shell): a tiny
 * in-memory network (3 stops, one route, two trips) planned through the
 * production `AtlasEngine` + `StaticNetworkPlanner`, then rendered through
 * the production render pipeline. No fixtures, no network.
 */
object WebDemo {

    val feedId: FeedId = FeedId("metro-transit-regional")
    val zone: TimeZone = TimeZone.of("America/Chicago")
    val departAt: Instant = LocalDateTime(2026, 10, 5, 7, 0).toInstant(zone)

    fun demoNetwork(): RoutingNetwork {
        val agency = Agency(AgencyId("metro-transit-regional/1"), feedId, "Metro Transit", null, zone, null, null, null, null)
        val route = Route(RouteKey(feedId, "901"), agency.id, "A", "Blue Line", null, 0, null, null, null, null)
        val stops = listOf(
            Stop(StopKey(feedId, "S1"), "S1", "First", null, GeoPoint(44.9778, -93.2650), null, 0, null, null),
            Stop(StopKey(feedId, "S2"), "S2", "Second", null, GeoPoint(44.9900, -93.2600), null, 0, null, null),
            Stop(StopKey(feedId, "S3"), "S3", "Third", null, GeoPoint(44.9950, -93.2500), null, 0, null, null),
        )
        val svc = ServiceKey(feedId, "SVC")
        val tripA = Trip(TripKey(feedId, "T1"), route.key, svc, null, 0, null, null, null)
        val tripB = Trip(TripKey(feedId, "T2"), route.key, svc, null, 0, null, null, null)
        val times = listOf(
            StopTime(TripKey(feedId, "T1"), StopKey(feedId, "S1"), 1, GtfsServiceTime.parse("08:00:00"), GtfsServiceTime.parse("08:00:00"), null, null, null, null),
            StopTime(TripKey(feedId, "T1"), StopKey(feedId, "S2"), 2, GtfsServiceTime.parse("08:10:00"), GtfsServiceTime.parse("08:10:00"), null, null, null, null),
            StopTime(TripKey(feedId, "T1"), StopKey(feedId, "S3"), 3, GtfsServiceTime.parse("08:20:00"), GtfsServiceTime.parse("08:20:00"), null, null, null, null),
            StopTime(TripKey(feedId, "T2"), StopKey(feedId, "S1"), 1, GtfsServiceTime.parse("08:15:00"), GtfsServiceTime.parse("08:15:00"), null, null, null, null),
            StopTime(TripKey(feedId, "T2"), StopKey(feedId, "S3"), 2, GtfsServiceTime.parse("08:35:00"), GtfsServiceTime.parse("08:35:00"), null, null, null, null),
        )
        return RoutingNetwork(
            feedId = feedId,
            metadata = FeedMetadata(feedId, null, null, null, "1", "hash", Instant.DISTANT_PAST, null, null, null),
            routes = listOf(route),
            stops = stops,
            trips = listOf(tripA, tripB),
            stopTimes = times,
        )
    }

    fun demoEngine(network: RoutingNetwork): AtlasEngine {
        val planner = StaticNetworkPlanner(network)
        return AtlasEngine(feedId, AtlasProviders(routing = planner), offlineAvailable = false)
    }

    /**
     * Demo radar overlay (RainViewer tilecache, latest PAST frame). Mirrors
     * `RadarFrame.tileUrlTemplate`; providers on the shell would convert a
     * live `RadarFrame` directly.
     */
    fun demoRadarOverlay(): com.intellitrip.map.web.RadarOverlay {
        val time = departAt.toEpochMilliseconds() / 1000L
        return com.intellitrip.map.web.RadarOverlay(
            "https://tilecache.rainviewer.com/v2/radar/$time/256/{z}/{x}/{y}/2/1_1.png",
        )
    }

    /** One-line verdict for the shell caption + tests. */
    suspend fun demoPlanText(network: RoutingNetwork = demoNetwork()): String {
        val engine = demoEngine(network)
        val outcome = engine.planJourney(
            TripPlanRequest(
                origin = GeoPoint(44.9778, -93.2650),
                destination = GeoPoint(44.9950, -93.2500),
                departAt = departAt,
                arriveBy = null,
                maxTransfers = 1,
            )
        )
        return when (outcome) {
            is com.intellitrip.engine.JourneyOutcome.Planned ->
                "planned ${outcome.journeys.size} journeys (e.g. ${outcome.journeys.firstOrNull()?.legs?.size ?: 0} legs)"
            is com.intellitrip.engine.JourneyOutcome.Unavailable ->
                "routing unavailable: ${outcome.reason}"
        }
    }

    /**
     * Pipeline snapshot for the render (one vehicle for color/proof).
     * [progress] 0..1 glides the vehicle S1 → S3 so the web shell can
     * poll every 200 ms like the Android glide loop.
     */
    fun demoSnapshot(now: Instant = Clock.System.now(), progress: Double = 0.55): RenderSnapshot {
        val net = demoNetwork()
        val origin = GeoPoint(44.9778, -93.2650)
        val destination = GeoPoint(44.9950, -93.2500)
        val t = progress.coerceIn(0.0, 1.0)
        val vehicle = com.intellitrip.domain.Vehicle(
            key = com.intellitrip.domain.VehicleKey(feedId, "veh-1"),
            routeKey = net.routes.single().key,
            tripKey = TripKey(feedId, "T1"),
            position = GeoPoint(origin.lat + (destination.lat - origin.lat) * t, origin.lon + (destination.lon - origin.lon) * t),
            bearing = 90.0,
            speedMps = 11.0,
            updatedAt = now,
            vehicleType = com.intellitrip.domain.VehicleType.BUS,
        )
        return TransitRenderPipeline().render(
            RenderInput(
                camera = CameraView(
                    bounds = com.intellitrip.domain.LatLngBounds(
                        GeoPoint(44.97, -93.28), GeoPoint(45.0, -93.24),
                    ),
                    zoom = 15.0,
                ),
                vehicles = listOf(vehicle),
                stops = net.stops,
                shapes = emptyList(),
                snapshotAt = now,
                routes = net.routes,
                fetchedAt = now,
            )
        )
    }

}
