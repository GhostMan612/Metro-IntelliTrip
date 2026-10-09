package com.intellitrip.routing

import com.intellitrip.contracts.FreshnessMetadata
import com.intellitrip.contracts.ProviderResult
import com.intellitrip.contracts.RoutingProvider
import com.intellitrip.domain.AgencyId
import com.intellitrip.domain.FeedId
import com.intellitrip.domain.FeedMetadata
import com.intellitrip.domain.GeoPoint
import com.intellitrip.domain.GtfsServiceTime
import com.intellitrip.domain.JourneyOption
import com.intellitrip.domain.Leg
import com.intellitrip.domain.RealtimeStatus
import com.intellitrip.domain.Route
import com.intellitrip.domain.RouteKey
import com.intellitrip.domain.ScheduleRelationship
import com.intellitrip.domain.ServiceKey
import com.intellitrip.domain.Stop
import com.intellitrip.domain.StopKey
import com.intellitrip.domain.StopTime
import com.intellitrip.domain.Transfer
import com.intellitrip.domain.TransferConfidence
import com.intellitrip.domain.TransferConnection
import com.intellitrip.domain.Trip
import com.intellitrip.domain.TripKey
import com.intellitrip.domain.TripPlanRequest
import com.intellitrip.domain.TripUpdate
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** The static network a planner searches: routes, stops, trips, stop times, transfers. */
class RoutingNetwork(
    val feedId: FeedId,
    val metadata: FeedMetadata,
    val routes: List<Route>,
    val stops: List<Stop>,
    val trips: List<Trip>,
    val stopTimes: List<StopTime>,
    val transfers: List<Transfer> = emptyList(),
    val activeServiceDates: Set<LocalDate> = emptySet(),
) {
    val stopsByKey: Map<StopKey, Stop> = stops.associateBy { it.key }
    val tripsByKey: Map<TripKey, Trip> = trips.associateBy { it.key }
    val routesByKey: Map<RouteKey, Route> = routes.associateBy { it.key }

    /** Stop times stay ordered so boarding/alighting sequences are stable. */
    val stopTimesByTrip: Map<TripKey, List<StopTime>> = stopTimes
        .groupBy { it.tripKey }
        .mapValues { (_, values) -> values.sortedBy { it.stopSequence } }

    fun isActive(serviceKey: ServiceKey, date: LocalDate): Boolean =
        activeServiceDates.isEmpty() || date in activeServiceDates && activeServiceDates.contains(date)

    fun servicesOn(date: LocalDate): Set<ServiceKey> =
        if (activeServiceDates.isEmpty()) emptySet() else activeServiceDates.filter { it == date }
            .map { ServiceKey(feedId, "any") }
            .toSet()
}

/**
 * Provider-neutral planner over the static network.
 *
 * Builds walking legs, transit legs and transfer connections from GTFS data.
 * Service-day-relative times are converted to instants using the agency
 * timezone and the service date. Realtime updates are applied as an annotation
 * layer; they never change the underlying schedule.
 */
class StaticNetworkPlanner(
    private val network: RoutingNetwork,
    private val agencyZone: ZoneId = ZoneId.of("America/Chicago"),
    private val maxJourneys: Int = 5,
    override val providerId: String = "static-network",
) : RoutingProvider {

    override suspend fun plan(request: TripPlanRequest): ProviderResult<List<JourneyOption>> =
        planWithRealtime(request, emptyList())

    fun planWithRealtime(
        request: TripPlanRequest,
        updates: List<TripUpdate>,
    ): ProviderResult<List<JourneyOption>> {
        val earliest = request.departAt ?: Instant.now().plusSeconds(60)
        val serviceDate = LocalDate.ofInstant(earliest, agencyZone)

        val originStops = nearestStops(request.origin, request.maxWalkMeters)
        val destinationStops = nearestStops(request.destination, request.maxWalkMeters)
        if (originStops.isEmpty() || destinationStops.isEmpty()) {
            return ProviderResult.PartialResult(
                data = emptyList(),
                missing = listOf("stops-within-walk-limit"),
                freshness = freshness(),
            )
        }

        val options = buildOptions(request, originStops, destinationStops, serviceDate, earliest, updates)
        return ProviderResult.Success(options.sortedBy { it.arrival }.take(maxJourneys), freshness())
    }

    private fun freshness(): FreshnessMetadata = FreshnessMetadata(
        sourceTimestamp = network.metadata.fetchedAt,
        fetchedAt = Instant.now(),
        age = Duration.between(network.metadata.fetchedAt, Instant.now()).let { if (it.isNegative) Duration.ZERO else it },
        providerId = providerId,
    )

    private fun buildOptions(
        request: TripPlanRequest,
        originStops: List<Stoped>,
        destinationStops: List<Stoped>,
        serviceDate: LocalDate,
        earliest: Instant,
        updates: List<TripUpdate>,
    ): List<JourneyOption> {
        val destinationKeys = destinationStops.map { it.stop.key }.toSet()
        val options = mutableListOf<JourneyOption>()
        val maxTransfers = request.maxTransfers ?: 1

        for (origin in originStops) {
            for (trip in network.trips) {
                val times = network.stopTimesByTrip[trip.key] ?: continue
                val boardIndex = times.indexOfFirst { it.stopKey == origin.stop.key }
                if (boardIndex < 0) continue

                for (board in times.drop(boardIndex)) {
                    val boardInstant = board.departureTime?.instantOn(serviceDate, agencyZone) ?: continue
                    if (boardInstant.isBefore(earliest)) continue

                    val alight = times.drop(boardIndex + 1).firstOrNull { it.stopKey in destinationKeys }
                    if (alight == null) continue

                    val direct = directJourney(
                        request, origin, board, alight, destinationStops, serviceDate, updates,
                    )
                    if (direct != null) options += direct

                    if (maxTransfers >= 1) {
                        val onward = onwardJourney(
                            request, origin, board, alight, destinationStops, serviceDate, updates,
                        )
                        if (onward != null) options += onward
                    }
                }
            }
        }
        return options
    }

    /** One transit leg between a nearby origin stop and a nearby destination stop. */
    private fun directJourney(
        request: TripPlanRequest,
        origin: Stoped,
        board: StopTime,
        alight: StopTime,
        destinationStops: List<Stoped>,
        serviceDate: LocalDate,
        updates: List<TripUpdate>,
    ): JourneyOption? {
        val destination = destinationStops.firstOrNull { it.stop.key == alight.stopKey } ?: return null
        val transit = transitLeg(network.tripsByKey[board.tripKey] ?: return null, board, alight, serviceDate, updates)
        val walkOut = walkLeg(request.origin, origin.stop.location, transit.departure, request) ?: return null
        val walkIn = walkLeg(destination.stop.location, request.destination, transit.arrival, request) ?: return null
        return JourneyOption(
            id = "${board.tripKey.tripId}|direct",
            legs = listOf(walkOut, transit, walkIn),
            transfers = emptyList(),
            departure = walkOut.start,
            arrival = walkIn.end,
        )
    }

    /**
     * Two transit legs joined by a transfer connection. The connection carries
     * its own buffers, walking duration and confidence; the journey itself does
     * not.
     */
    private fun onwardJourney(
        request: TripPlanRequest,
        origin: Stoped,
        board: StopTime,
        firstAlight: StopTime,
        destinationStops: List<Stoped>,
        serviceDate: LocalDate,
        updates: List<TripUpdate>,
    ): JourneyOption? {
        val destinationKeys = destinationStops.map { it.stop.key }.toSet()
        val connection = earliestConnection(firstAlight, serviceDate, destinationKeys) ?: return null
        val (secondTrip, secondBoard) = connection
        val secondTimes = network.stopTimesByTrip[secondBoard.tripKey] ?: return null
        val boardIndex = secondTimes.indexOf(secondBoard)
        val finalAlight = secondTimes.drop(boardIndex + 1).firstOrNull { it.stopKey in destinationKeys } ?: return null
        val destination = destinationStops.firstOrNull { it.stop.key == finalAlight.stopKey } ?: return null

        val firstLeg = transitLeg(network.tripsByKey[board.tripKey] ?: return null, board, firstAlight, serviceDate, updates)
        val secondLeg = transitLeg(secondTrip, secondBoard, finalAlight, serviceDate, updates)

        val transferStop = network.stopsByKey[firstAlight.stopKey] ?: return null
        val scheduledBuffer = Duration.between(firstLeg.arrival, secondLeg.departure)
        val predictedBuffer = if (firstLeg.delaySeconds != null || secondLeg.delaySeconds != null) {
            Duration.between(
                firstLeg.arrival.plusSeconds((firstLeg.delaySeconds ?: 0).toLong()),
                secondLeg.departure.plusSeconds((secondLeg.delaySeconds ?: 0).toLong()),
            )
        } else {
            null
        }
        val walking = Duration.ZERO // same-stop transfers involve no walking
        val transfer = TransferConnection(
            arrivingLeg = firstLeg,
            departingLeg = secondLeg,
            transferStop = firstAlight.stopKey,
            scheduledBuffer = scheduledBuffer,
            predictedBuffer = predictedBuffer,
            walkingDuration = walking,
            confidence = TransferConfidence.SCHEDULED_UNKNOWN,
            rationale = "scheduled buffer from static timetable",
        )

        val walkOut = walkLeg(request.origin, origin.stop.location, firstLeg.departure, request) ?: return null
        val walkIn = walkLeg(destination.stop.location, request.destination, secondLeg.arrival, request) ?: return null
        return JourneyOption(
            id = "${board.tripKey.tripId}|${secondBoard.tripKey.tripId}|transfer",
            legs = listOf(walkOut, firstLeg, secondLeg, walkIn),
            transfers = listOf(transfer),
            departure = walkOut.start,
            arrival = walkIn.end,
        )
    }

    /** Finds the earliest onward trip that boards no earlier than the arrival. */
    private fun earliestConnection(
        firstAlight: StopTime,
        serviceDate: LocalDate,
        destinationKeys: Set<StopKey>,
    ): Pair<Trip, StopTime>? {
        val arrivalInstant = firstAlight.arrivalTime?.instantOn(serviceDate, agencyZone) ?: return null
        var best: Pair<Trip, StopTime>? = null
        var bestDeparture: Instant? = null
        for (trip in network.trips) {
            if (trip.key == firstAlight.tripKey) continue
            val times = network.stopTimesByTrip[trip.key] ?: continue
            val candidate = times.firstOrNull { time ->
                time.stopKey == firstAlight.stopKey &&
                    (time.departureTime?.instantOn(serviceDate, agencyZone) ?: Instant.MIN)
                        .isBefore(arrivalInstant).not()
            } ?: continue
            val departure = candidate.departureTime?.instantOn(serviceDate, agencyZone) ?: continue
            if (departure.isBefore(arrivalInstant)) continue
            if (bestDeparture == null || departure.isBefore(bestDeparture)) {
                bestDeparture = departure
                best = trip to candidate
            }
        }
        return best
    }

    private fun transitLeg(
        trip: Trip,
        board: StopTime,
        alight: StopTime,
        serviceDate: LocalDate,
        updates: List<TripUpdate>,
    ): Leg.Transit {
        val scheduledDeparture = board.departureTime?.instantOn(serviceDate, agencyZone)
        val scheduledArrival = alight.arrivalTime?.instantOn(serviceDate, agencyZone)
        val update = updates.firstOrNull { it.tripKey == trip.key }
        val boardUpdate = update?.stopTimeUpdates?.firstOrNull { it.stopKey == board.stopKey }
        val delay = boardUpdate?.departureDelaySeconds ?: boardUpdate?.arrivalDelaySeconds
        val status = when {
            update == null -> RealtimeStatus.NO_DATA
            update.scheduleRelationship == ScheduleRelationship.CANCELED -> RealtimeStatus.CANCELED
            update.scheduleRelationship == ScheduleRelationship.ADDED -> RealtimeStatus.SCHEDULED
            delay == null -> RealtimeStatus.NO_DATA
            delay > 180 -> RealtimeStatus.DELAYED
            delay < -180 -> RealtimeStatus.EARLY
            delay == 0 -> RealtimeStatus.ON_TIME
            else -> RealtimeStatus.DELAYED
        }
        val departure = scheduledDeparture ?: Instant.EPOCH
        val arrival = scheduledArrival ?: departure
        return Leg.Transit(
            agencyId = network.routesByKey[trip.routeKey]?.agencyId,
            routeKey = trip.routeKey,
            tripKey = trip.key,
            serviceDate = serviceDate,
            fromStopKey = board.stopKey,
            toStopKey = alight.stopKey,
            departure = departure,
            arrival = arrival,
            vehicleKey = update?.vehicleKey,
            realtimeStatus = status,
            delaySeconds = delay,
            scheduledDeparture = scheduledDeparture,
            scheduledArrival = scheduledArrival,
            duration = Duration.between(departure, arrival),
        )
    }

    private fun walkLeg(
        from: GeoPoint,
        to: GeoPoint,
        start: Instant,
        request: TripPlanRequest,
    ): Leg.WalkLeg? {
        val meters = haversineMeters(from, to)
        if (meters > request.maxWalkMeters) return null
        val seconds = (meters / request.walkSpeedMetersPerSecond).toLong().coerceAtLeast(0)
        return Leg.WalkLeg(from, to, start, Duration.ofSeconds(seconds), meters)
    }

    private data class Stoped(val stop: Stop, val meters: Double)

    private fun nearestStops(point: GeoPoint, maxMeters: Double): List<Stoped> =
        network.stops
            .map { Stoped(it, haversineMeters(point, it.location)) }
            .filter { it.meters <= maxMeters }
            .sortedBy { it.meters }
}

/**
 * Converts a GTFS service-day-relative time into an instant for a service date in
 * the agency timezone. Times past 24:00:00 correctly land on the following day.
 */
fun GtfsServiceTime.instantOn(date: LocalDate, zone: ZoneId = ZoneId.of("America/Chicago")): Instant? {
    val seconds = secondsSinceServiceDayStart
    if (seconds < 0) return null
    val startOfDay = date.atStartOfDay(zone).toEpochSecond()
    return Instant.ofEpochSecond(startOfDay + seconds)
}

fun haversineMeters(a: GeoPoint, b: GeoPoint): Double {
    val earthRadius = 6_371_000.0
    val dLat = Math.toRadians(b.lat - a.lat)
    val dLon = Math.toRadians(b.lon - a.lon)
    val h = sin(dLat / 2) * sin(dLat / 2) +
        cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2) * sin(dLon / 2)
    return 2 * earthRadius * asin(sqrt(h))
}

internal fun near(a: Double, b: Double, tolerance: Double = 1e-6): Boolean = abs(a - b) < tolerance
