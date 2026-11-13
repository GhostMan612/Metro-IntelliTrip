package com.intellitrip.webspike

import com.intellitrip.domain.GeoMath
import com.intellitrip.domain.GeoPoint
import com.intellitrip.domain.JourneyOption
import com.intellitrip.domain.LatLngBounds
import com.intellitrip.domain.Leg
import com.intellitrip.domain.ServiceAlert
import com.intellitrip.domain.Stop
import com.intellitrip.domain.StopKey
import com.intellitrip.domain.TripPlanRequest
import com.intellitrip.engine.AtlasEngine
import com.intellitrip.engine.AtlasProviders
import com.intellitrip.engine.JourneyOutcome
import com.intellitrip.gtfs.StaticFeed
import com.intellitrip.gtfs.StaticFeedSnapshotStore
import com.intellitrip.gtfsrt.PollingGtfsRealtimeClient
import com.intellitrip.map.CameraView
import com.intellitrip.map.RenderInput
import com.intellitrip.map.RenderSnapshot
import com.intellitrip.map.ShapeLineRender
import com.intellitrip.map.TransitRenderPipeline
import com.intellitrip.routing.RoutingNetwork
import com.intellitrip.routing.StaticNetworkPlanner
import com.intellitrip.routing.StopTimeSource
import kotlin.math.abs
import kotlin.time.Duration.Companion.seconds
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Production web-shell data path.
 *
 * The browser shell is a thin host around the same engine, planner and render
 * pipeline the Android app uses. Everything in this file is JVM-testable;
 * [Platform.kt] only wires DOM events and the async feed lifecycle.
 *
 * There is deliberately no synthetic fallback here: if the static feed cannot
 * be acquired and parsed, the shell shows an error instead of pretending the
 * demo network is Metro Transit.
 */
object WebProduction {

    init {
        installPlatformTimeZones()
    }

    val feedId = com.intellitrip.domain.FeedId("metro-transit-regional")
    val zone: TimeZone = TimeZone.of("America/Chicago")

    const val STATIC_GTFS_URL: String = "https://svc.metrotransit.org/mtgtfs/gtfs.zip"

    val realtimeUrls: PollingGtfsRealtimeClient.RealtimeFeedUrls =
        PollingGtfsRealtimeClient.RealtimeFeedUrls(
            vehiclePositions = "https://svc.metrotransit.org/mtgtfs/vehiclepositions.pb",
            tripUpdates = "https://svc.metrotransit.org/mtgtfs/tripupdates.pb",
            alerts = "https://svc.metrotransit.org/mtgtfs/alerts.pb",
        )

    private val fallbackCenter = GeoPoint(44.9778, -93.2650)

    /** Demo data is opt-in only; production is the default. */
    fun demoRequested(search: String): Boolean =
        search.trimStart('?')
            .split('&')
            .any { it.equals("demo=1", ignoreCase = false) }

    /**
     * Builds the routing view of a parsed feed. Stop times were streamed into
     * the snapshot store during parsing, so the planner reads them from the
     * index instead of retaining a second in-memory copy of the whole feed.
     */
    fun networkOf(feed: StaticFeed, store: StaticFeedSnapshotStore): RoutingNetwork {
        val reader = store.stopTimeReader(feed.feedId)
        val source = reader?.let { index ->
            object : StopTimeSource {
                override fun stopTimesFor(trip: com.intellitrip.domain.TripKey) =
                    index.stopTimesFor(trip) { stopId -> StopKey(feed.feedId, stopId) }

                override fun tripsServing(stop: StopKey) = index.tripsServing(stop)
            }
        }
        return RoutingNetwork(
            feedId = feed.feedId,
            metadata = feed.metadata,
            routes = feed.routes,
            stops = feed.stops,
            trips = feed.trips,
            stopTimes = feed.stopTimes,
            stopTimeSource = source,
            transfers = feed.transfers,
            calendars = feed.calendars,
            calendarDates = feed.calendarDates,
            agencies = feed.agencies,
            frequencies = feed.frequencies,
        )
    }

    fun engineFor(network: RoutingNetwork): AtlasEngine = AtlasEngine(
        feedId = feedId,
        providers = AtlasProviders(routing = StaticNetworkPlanner(network)),
        offlineAvailable = false,
    )

    /** Geographic extent of the feed, or null when it has no usable stops. */
    fun feedBounds(feed: StaticFeed): LatLngBounds? {
        if (feed.stops.isEmpty()) return null
        var south = Double.MAX_VALUE
        var west = Double.MAX_VALUE
        var north = -Double.MAX_VALUE
        var east = -Double.MAX_VALUE
        for (stop in feed.stops) {
            val point = stop.location
            if (!point.lat.isFinite() || !point.lon.isFinite()) continue
            if (point.lat < south) south = point.lat
            if (point.lat > north) north = point.lat
            if (point.lon < west) west = point.lon
            if (point.lon > east) east = point.lon
        }
        if (south > north || west > east) return null
        return LatLngBounds(GeoPoint(south, west), GeoPoint(north, east))
    }

    fun anchorFor(feed: StaticFeed): GeoPoint = feedBounds(feed)?.center() ?: fallbackCenter

    fun cameraFor(feed: StaticFeed): CameraView {
        val bounds = feedBounds(feed)
            ?: LatLngBounds(GeoPoint(44.90, -93.40), GeoPoint(45.05, -93.10))
        val latSpan = bounds.northEast.lat - bounds.southWest.lat
        val lonSpan = abs(bounds.northEast.lon - bounds.southWest.lon)
        val span = maxOf(latSpan, lonSpan)
        val zoom = when {
            span > 0.8 -> 9.5
            span > 0.4 -> 10.5
            span > 0.15 -> 11.5
            span > 0.05 -> 13.0
            else -> 14.5
        }
        return CameraView(bounds = bounds, zoom = zoom)
    }

    /**
     * Stops nearest to [anchor], used to populate the browser's from/to
     * controls. Duplicate names are kept: two different platforms can share a
     * name, and the ID in the option value disambiguates them.
     */
    fun nearbyStops(feed: StaticFeed, anchor: GeoPoint, limit: Int = 24): List<Stop> {
        if (limit <= 0) return emptyList()
        return feed.stops
            .asSequence()
            .filter { it.name.isNotBlank() }
            .map { stop -> stop to GeoMath.distanceMeters(anchor, stop.location) }
            .sortedWith(compareBy({ it.second }, { it.first.name }, { it.first.key.stopId }))
            .take(limit)
            .map { it.first }
            .toList()
    }

    fun snapshot(feed: StaticFeed, vehicles: List<com.intellitrip.domain.Vehicle>, now: Instant): RenderSnapshot =
        TransitRenderPipeline().render(
            RenderInput(
                camera = cameraFor(feed),
                vehicles = vehicles,
                stops = feed.stops,
                // Shapes are static per feed and expensive to re-cull every
                // second; the shell caches them with [shapeLines] and copies
                // them into each live snapshot.
                shapes = emptyList(),
                snapshotAt = now,
                routes = feed.routes,
                fetchedAt = now,
            )
        )

    /** Route geometry for the feed's full extent, computed once per refresh. */
    fun shapeLines(feed: StaticFeed): List<ShapeLineRender> =
        TransitRenderPipeline().render(
            RenderInput(
                camera = cameraFor(feed),
                vehicles = emptyList(),
                stops = emptyList(),
                shapes = feed.shapes,
                snapshotAt = Instant.DISTANT_PAST,
                routes = feed.routes,
                fetchedAt = null,
            )
        ).shapeLines

    /**
     * One-line journey summary for the shell. No synthetic walk leg is added:
     * the planner's own legs and transfer count are the whole answer.
     */
    suspend fun planText(
        engine: AtlasEngine,
        origin: GeoPoint,
        destination: GeoPoint,
        departAt: Instant,
    ): String {
        val outcome = engine.planJourney(
            TripPlanRequest(
                origin = origin,
                destination = destination,
                departAt = departAt,
                arriveBy = null,
                maxTransfers = 2,
            )
        )
        return when (outcome) {
            is JourneyOutcome.Planned -> summarize(outcome.journeys)
            is JourneyOutcome.Unavailable -> "Routing unavailable: ${outcome.reason}"
        }
    }

    private fun summarize(journeys: List<JourneyOption>): String {
        if (journeys.isEmpty()) return "No journey found for this departure time."
        val best = journeys.minByOrNull { it.arrival }!!
        val transitLegs = best.legs.count { it is Leg.Transit }
        val arrival = best.arrival.toLocalDateTime(zone)
        val clock = "${arrival.hour.toString().padStart(2, '0')}:${arrival.minute.toString().padStart(2, '0')}"
        return buildString {
            append(journeys.size)
            append(if (journeys.size == 1) " journey" else " journeys")
            append(" · ")
            append(best.transferCount)
            append(if (best.transferCount == 1) " transfer" else " transfers")
            append(" · ")
            append(transitLegs)
            append(if (transitLegs == 1) " transit leg" else " transit legs")
            append(" · arrive ")
            append(clock)
        }
    }

    fun alertLines(alerts: List<ServiceAlert>, limit: Int = 4): List<String> =
        alerts.take(limit).map { alert ->
            val routes = alert.affectedRoutes.size
            if (routes > 0) {
                val noun = if (routes == 1) "route" else "routes"
                "${alert.severity.name.lowercase()}: ${alert.headline} ($routes $noun)"
            } else {
                "${alert.severity.name.lowercase()}: ${alert.headline}"
            }
        }

    fun metadataLine(feed: StaticFeed): String {
        val agency = feed.agencies.firstOrNull()?.name ?: "Metro Transit"
        val fetched = feed.metadata.fetchedAt
        val routeWord = if (feed.routes.size == 1) "route" else "routes"
        val stopWord = if (feed.stops.size == 1) "stop" else "stops"
        val tripWord = if (feed.trips.size == 1) "trip" else "trips"
        return "$agency · ${feed.routes.size} $routeWord · ${feed.stops.size} $stopWord · " +
            "${feed.trips.size} $tripWord · fetched $fetched"
    }

    /** Realtime freshness policy mirrored from the Android host. */
    val realtimeStaleAfter = 120.seconds

    fun stopById(feed: StaticFeed, stopId: String): Stop? =
        feed.stopsByKey[StopKey(feed.feedId, stopId)]
}
