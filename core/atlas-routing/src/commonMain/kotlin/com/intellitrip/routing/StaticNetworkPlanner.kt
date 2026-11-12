package com.intellitrip.routing

import com.intellitrip.contracts.FreshnessMetadata
import com.intellitrip.contracts.ProviderResult
import com.intellitrip.contracts.RoutingProvider
import com.intellitrip.domain.Agency
import com.intellitrip.domain.AgencyId
import com.intellitrip.domain.Calendar
import com.intellitrip.domain.CalendarDate
import com.intellitrip.domain.FeedId
import com.intellitrip.domain.FeedMetadata
import com.intellitrip.domain.Frequency
import com.intellitrip.domain.GeoPoint
import com.intellitrip.domain.GeoMath
import com.intellitrip.domain.GtfsServiceTime
import com.intellitrip.domain.JourneyOption
import com.intellitrip.domain.Leg
import com.intellitrip.domain.RealtimeStatus
import com.intellitrip.domain.Route
import com.intellitrip.domain.RouteKey
import com.intellitrip.domain.ScheduleRelationship
import com.intellitrip.domain.ServiceCalendar
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
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Supplies a trip's ordered stop times; backed by memory or by the disk index. */
/**
 * Supplies a trip's ordered stop times; backed by memory or by the disk index.
 * Sources that can answer "which trips serve this stop" should override
 * [tripsServing] so a planner can skip irrelevant trips.
 */
interface StopTimeSource {
    fun stopTimesFor(trip: TripKey): List<StopTime>
    fun tripsServing(stop: StopKey): Set<TripKey> = emptySet()

    companion object {
        fun of(stopTimes: List<StopTime>): StopTimeSource =
            indexed(
                stopTimes.groupBy { it.tripKey }
                    .mapValues { (_, rows) -> rows.sortedBy { it.stopSequence } },
                stopTimes.groupBy { it.stopKey }
                    .mapValues { (_, rows) -> rows.map { it.tripKey }.toSet() },
            )

        fun indexed(
            stopTimesByTrip: Map<TripKey, List<StopTime>>,
            tripsByStop: Map<StopKey, Set<TripKey>>,
        ): StopTimeSource = IndexedStopTimeSource(stopTimesByTrip, tripsByStop)
    }
}

/** In-memory source that can also answer "which trips serve this stop". */
class IndexedStopTimeSource(
    private val byTrip: Map<TripKey, List<StopTime>>,
    private val byStop: Map<StopKey, Set<TripKey>>,
) : StopTimeSource {
    override fun stopTimesFor(trip: TripKey): List<StopTime> = byTrip[trip].orEmpty()
    override fun tripsServing(stop: StopKey): Set<TripKey> = byStop[stop].orEmpty()
}

/** The static network a planner searches: routes, stops, trips and stop times. */
class RoutingNetwork(
    val feedId: FeedId,
    val metadata: FeedMetadata,
    val routes: List<Route>,
    val stops: List<Stop>,
    val trips: List<Trip>,
    stopTimes: List<StopTime> = emptyList(),
    private val stopTimeSource: StopTimeSource? = null,
    val transfers: List<Transfer> = emptyList(),
    val activeServiceDates: Set<LocalDate> = emptySet(),
    val calendars: List<Calendar> = emptyList(),
    val calendarDates: List<CalendarDate> = emptyList(),
    val agencies: List<Agency> = emptyList(),
    val frequencies: List<Frequency> = emptyList(),
) {
    val stopsByKey: Map<StopKey, Stop> = stops.associateBy { it.key }
    val tripsByKey: Map<TripKey, Trip> = trips.associateBy { it.key }
    val routesByKey: Map<RouteKey, Route> = routes.associateBy { it.key }
    val frequenciesByTrip: Map<TripKey, List<Frequency>> =
        frequencies.groupBy { it.tripKey }

    /** Stop times stay ordered so boarding/alighting sequences are stable. */
    val stopTimesByTrip: Map<TripKey, List<StopTime>> by lazy {
        if (stopTimeSource != null) {
            emptyMap()
        } else {
            stopTimes.groupBy { it.tripKey }.mapValues { (_, values) -> values.sortedBy { it.stopSequence } }
        }
    }

    // In-memory reverse index, used only when no source supplies one. Without it the
    // planner would consider every trip in the system for every nearby origin stop.
    private val inMemoryTripsByStop: Map<StopKey, Set<TripKey>> by lazy {
        if (stopTimeSource != null) emptyMap() else stopTimes.groupBy { it.stopKey }
            .mapValues { (_, rows) -> rows.mapTo(LinkedHashSet(rows.size)) { it.tripKey } }
    }

    /** Reads a trip's stop times from the disk index when configured. */
    fun stopTimesFor(trip: TripKey): List<StopTime> =
        stopTimeSource?.stopTimesFor(trip)
            ?: stopTimesByTrip[trip]
            ?: emptyList()

    /**
     * Trips that can be boarded at a stop. Uses the stop-time source's reverse
     * index when available so a large feed is not scanned trip by trip.
     */
    fun tripsServing(stop: StopKey): List<Trip> {
        val keys = stopTimeSource?.tripsServing(stop)
            ?: inMemoryTripsByStop[stop]
            ?: return trips
        return keys.mapNotNull { tripsByKey[it] }
    }

    /** Zone of the first agency that declares one, for service-date conversion. */
    fun agencyZone(fallback: TimeZone = TimeZone.of("America/Chicago")): TimeZone =
        agencies.firstOrNull { it.timezone != null }?.timezone ?: fallback

    /** Delegates to [ServiceCalendar] — the planner and board share one implementation. */
    fun isActive(serviceKey: ServiceKey, date: LocalDate): Boolean =
        ServiceCalendar(calendars, calendarDates, activeServiceDates).isActive(serviceKey, date)

    fun servicesOn(date: LocalDate): Set<ServiceKey> =
        ServiceCalendar(calendars, calendarDates, activeServiceDates)
            .servicesOn(date, trips.mapTo(mutableSetOf()) { it.serviceKey })
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
    private val agencyZone: TimeZone = TimeZone.of("America/Chicago"),
    private val maxJourneys: Int = 5,
    /**
     * How far ahead departures are considered. Journeys leaving hours later are not
     * useful next-departure results and expanding them is what made planning crawl.
     */
    private val departureWindowMinutes: Long = 180,
    /** Upper bound on boardings expanded into journeys, applied after the time filter. */
    private val maxCandidates: Int = 400,
    /**
     * Nearest stops considered at each end of the journey. Bounds how many trips
     * enter the search.
     *
     * Measured against the real Metro Transit feed (Minneapolis to St Paul), the
     * cap makes almost no difference to results because `maxCandidates` and the
     * departure window already bound the work:
     *
     * ```
     * cap=8   2.6s   5 journeys      cap=30   5.0s   5 journeys
     * cap=12  2.2s   5 journeys      cap=60   9.2s   5 journeys
     * cap=20  3.3s   5 journeys      cap=120 28.7s   5 journeys
     * ```
     *
     * The default favours latency; raising it costs seconds and buys nothing here.
     */
    private val maxStopsPerEnd: Int = 12,
    override val providerId: String = "static-network",
) : RoutingProvider {

    /** Feed truth wins: the network's agency timezone overrides the default. */
    private val effectiveZone: TimeZone get() = network.agencyZone(agencyZone)

    override suspend fun plan(request: TripPlanRequest): ProviderResult<List<JourneyOption>> =
        planWithRealtime(request, emptyList())

    fun planWithRealtime(
        request: TripPlanRequest,
        updates: List<TripUpdate>,
    ): ProviderResult<List<JourneyOption>> {
        val earliest = request.departAt ?: Clock.System.now() + 60.seconds
        val serviceDate = earliest.toLocalDateTime(effectiveZone).date

val originStops = nearestStops(request.origin, request.maxWalkMeters, maxStopsPerEnd)
        val destinationStops = nearestStops(request.destination, request.maxWalkMeters, maxStopsPerEnd)
        if (originStops.isEmpty() || destinationStops.isEmpty()) {
            return ProviderResult.PartialResult(
                data = emptyList(),
                missing = listOf("stops-within-walk-limit"),
                freshness = freshness(),
            )
        }

val options = buildOptions(
            request,
            originStops,
            destinationStops,
            serviceDate,
            earliest,
            earliest + (departureWindowMinutes * 60).seconds,
            updates,
        )
        val sorted = options.sortedBy { it.arrival }
        val arriveBy = request.arriveBy
        if (arriveBy != null) {
            val feasible = sorted.filter { it.arrival <= arriveBy }
            if (feasible.isEmpty() && sorted.isNotEmpty()) {
                return ProviderResult.PartialResult(
                    data = emptyList(),
                    missing = listOf("arrive-by-unachievable"),
                    freshness = freshness(),
                )
            }
            return ProviderResult.Success(feasible.take(maxJourneys), freshness())
        }
        return ProviderResult.Success(sorted.take(maxJourneys), freshness())
    }

    private fun freshness(): FreshnessMetadata = FreshnessMetadata(
        sourceTimestamp = network.metadata.fetchedAt,
        fetchedAt = Clock.System.now(),
        age = (Clock.System.now() - network.metadata.fetchedAt).let { if (it.isNegative()) Duration.ZERO else it },
        providerId = providerId,
    )

    private fun buildOptions(
        request: TripPlanRequest,
        originStops: List<Stoped>,
        destinationStops: List<Stoped>,
serviceDate: LocalDate,
        earliest: Instant,
        horizonEnd: Instant,
        updates: List<TripUpdate>,
): List<JourneyOption> {
        val destinationKeys = destinationStops.map { it.stop.key }.toSet()
        val options = mutableListOf<JourneyOption>()
        val maxTransfers = request.maxTransfers ?: 1

        // Candidate boardings, gathered across all origin stops and ordered by
        // departure so the search can stop once it has seen enough.
        val candidates = ArrayList<Candidate>()
        for (origin in originStops) {
            for (trip in network.tripsServing(origin.stop.key)) {
                if (!network.isActive(trip.serviceKey, serviceDate)) continue
                val times = network.stopTimesFor(trip.key)
                if (times.isEmpty()) continue
                val boardIndex = times.indexOfFirst { it.stopKey == origin.stop.key }
                if (boardIndex < 0) continue

                // Where this trip can drop off, found once per trip instead of
                // once per boarding.
                val alightIndexes = times.indices.drop(boardIndex + 1)
                    .filter { times[it].stopKey in destinationKeys }
                if (alightIndexes.isEmpty()) continue

                for (boardIndexOnTrip in boardIndex..times.size) {
                    if (boardIndexOnTrip >= times.size) break
                    val alightIndex = alightIndexes.firstOrNull { it > boardIndexOnTrip } ?: break
                    // Frequency trips repeat at headway intervals; expand each
                    // repetition as its own candidate (bounded, window-filtered).
                    for ((board, shiftedTimes) in expandedRuns(trip.key, times, boardIndexOnTrip)) {
                        val boardInstant =
                            board.departureTime?.instantOn(serviceDate, effectiveZone) ?: continue
                        if (boardInstant < earliest) continue
                        if (boardInstant > horizonEnd) break
                        candidates += Candidate(origin, board, alightIndex, shiftedTimes)
                    }
                }
            }
        }

        // Earliest departures first, then take only as many as we are prepared to
        // expand. This is what keeps a system-wide feed responsive.
        val chosen = candidates
            .sortedBy { it.board.departureTime?.instantOn(serviceDate, effectiveZone) }
            .take(maxCandidates)
        for (candidate in chosen) {
            val origin = candidate.origin
            val board = candidate.board
            val alight = candidate.times[candidate.alightIndex]
            if (maxTransfers >= 2) {
                val onward2 = doubleOnwardJourney(
                    request, origin, board, alight, destinationStops, serviceDate, updates,
                )
                if (onward2 != null) options += onward2
            }
            if (maxTransfers >= 1) {
                val onward = onwardJourney(
                    request, origin, board, alight, destinationStops, serviceDate, updates,
                )
                if (onward != null) options += onward
            }
            val direct = directJourney(
                request, origin, board, alight, destinationStops, serviceDate, updates,
            )
            if (direct != null) options += direct
        }
        return options
    }

    /** A boarding opportunity discovered while scanning candidate trips. */
    private class Candidate(
        val origin: Stoped,
        val board: StopTime,
        val alightIndex: Int,
        val times: List<StopTime>,
    )

    /**
     * Expands a template trip into its actual runs.
     *
     * Frequency-based trips repeat their template stop times every
     * `headwaySeconds` between `startTime` and `endTime`, anchored at the
     * template's first departure. Without frequencies this is the single
     * template run. Bounded to 300 repetitions per window for safety
     * (an all-day 5-min headway is 288 runs, so 300 keeps full service
     * without unbounded blowup). The departure board expands the same
     * frequencies differently (top ~20 departures inside a 2-hour horizon)
     * because it is a UI listing, not a search — planner completeness wins
     * there, board boundedness wins here. Both numbers are deliberate.
     */
    private fun expandedRuns(
        tripKey: TripKey,
        times: List<StopTime>,
        boardIndex: Int,
    ): List<Pair<StopTime, List<StopTime>>> {
        val freqs = network.frequenciesByTrip[tripKey]
        if (freqs.isNullOrEmpty()) return listOf(times[boardIndex] to times)
        val firstDepSec = times.firstOrNull()?.departureTime?.secondsSinceServiceDayStart ?: return listOf(times[boardIndex] to times)
        val out = mutableListOf<Pair<StopTime, List<StopTime>>>()
        for (freq in freqs) {
            val headway = freq.headwaySeconds
            if (headway <= 0) continue
            var runStart = freq.startTime.secondsSinceServiceDayStart
            var count = 0
            // All-day 5-min headway is 288 runs; 50 truncates legitimate service.
            while (runStart <= freq.endTime.secondsSinceServiceDayStart && count < 300) {
                val delta = runStart - firstDepSec
                val shifted = times.map { st ->
                    st.copy(
                        arrivalTime = st.arrivalTime?.let { GtfsServiceTime.ofSeconds(it.secondsSinceServiceDayStart + delta) },
                        departureTime = st.departureTime?.let { GtfsServiceTime.ofSeconds(it.secondsSinceServiceDayStart + delta) },
                    )
                }
                // Skip runs whose shifted times go negative (bad template alignment).
                if (shifted.any { (it.departureTime?.secondsSinceServiceDayStart ?: 0) < 0 }) {
                    runStart += headway; count++; continue
                }
                out += shifted[boardIndex] to shifted
                runStart += headway
                count++
            }
        }
        return out.ifEmpty { listOf(times[boardIndex] to times) }
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
        val walkOut = walkLeg(request.origin, origin.stop.location, transit.departure, request, anchorIsEnd = true) ?: return null
        val walkIn = walkLeg(destination.stop.location, request.destination, transit.arrival, request, anchorIsEnd = false) ?: return null
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
        val connection = earliestConnection(firstAlight, serviceDate, destinationKeys, request.walkSpeedMetersPerSecond) ?: return null
        val (secondTrip, secondBoard, walkingDuration, secondTimes) = connection
        val boardIndex = secondTimes.indexOfFirst { it.stopKey == secondBoard.stopKey && it.stopSequence == secondBoard.stopSequence }
        if (boardIndex < 0) return null
        val finalAlight = secondTimes.drop(boardIndex + 1).firstOrNull { it.stopKey in destinationKeys } ?: return null
        val destination = destinationStops.firstOrNull { it.stop.key == finalAlight.stopKey } ?: return null

        val firstLeg = transitLeg(network.tripsByKey[board.tripKey] ?: return null, board, firstAlight, serviceDate, updates)
        val secondLeg = transitLeg(secondTrip, secondBoard, finalAlight, serviceDate, updates)

        val transferStop = network.stopsByKey[firstAlight.stopKey] ?: return null
        val scheduledBuffer = secondLeg.departure - firstLeg.arrival
        val predictedBuffer = if (firstLeg.delaySeconds != null || secondLeg.delaySeconds != null) {
            (secondLeg.departure + (secondLeg.delaySeconds ?: 0).seconds) -
                (firstLeg.arrival + (firstLeg.delaySeconds ?: 0).seconds)
        } else {
            null
        }
        val transfer = TransferConnection(
            arrivingLeg = firstLeg,
            departingLeg = secondLeg,
            transferStop = firstAlight.stopKey,
            scheduledBuffer = scheduledBuffer,
            predictedBuffer = predictedBuffer,
            walkingDuration = walkingDuration,
            confidence = TransferConfidence.SCHEDULED_UNKNOWN,
            rationale = "scheduled buffer from static timetable",
        )

        val walkOut = walkLeg(request.origin, origin.stop.location, firstLeg.departure, request, anchorIsEnd = true) ?: return null
        val walkIn = walkLeg(destination.stop.location, request.destination, secondLeg.arrival, request, anchorIsEnd = false) ?: return null
        return JourneyOption(
            id = "${board.tripKey.tripId}|${secondBoard.tripKey.tripId}|transfer",
            legs = listOf(walkOut, firstLeg, secondLeg, walkIn),
            transfers = listOf(transfer),
            departure = walkOut.start,
            arrival = walkIn.end,
        )
    }

    /**
     * Three transit legs joined by two transfer connections.
     * Used when `maxTransfers >= 2`; picks the earliest arrival among all
     * intermediate alight stops on the second leg.
     */
    private fun doubleOnwardJourney(
        request: TripPlanRequest,
        origin: Stoped,
        board: StopTime,
        firstAlight: StopTime,
        destinationStops: List<Stoped>,
        serviceDate: LocalDate,
        updates: List<TripUpdate>,
    ): JourneyOption? {
        val destinationKeys = destinationStops.map { it.stop.key }.toSet()
        val conn1 = earliestConnection(firstAlight, serviceDate, destinationKeys, request.walkSpeedMetersPerSecond) ?: return null
        val (secondTrip, secondBoard, walking1, secondTimes) = conn1
        val boardIdx2 = secondTimes.indexOfFirst { it.stopKey == secondBoard.stopKey && it.stopSequence == secondBoard.stopSequence }
        if (boardIdx2 < 0) return null
        var best: JourneyOption? = null
        val firstLegTrip = network.tripsByKey[board.tripKey] ?: return null
        val firstLeg = transitLeg(firstLegTrip, board, firstAlight, serviceDate, updates)
        for (midIdx in boardIdx2 + 1 until secondTimes.size) {
            val midAlight = secondTimes[midIdx]
            val conn2 = earliestConnection(midAlight, serviceDate, destinationKeys, request.walkSpeedMetersPerSecond) ?: continue
            val (thirdTrip, thirdBoard, walking2, thirdTimes) = conn2
            if (thirdTrip.key == secondTrip.key) continue
            val boardIdx3 = thirdTimes.indexOfFirst { it.stopKey == thirdBoard.stopKey && it.stopSequence == thirdBoard.stopSequence }
            if (boardIdx3 < 0) continue
            val finalAlight = thirdTimes.drop(boardIdx3 + 1).firstOrNull { it.stopKey in destinationKeys } ?: continue
            val destination = destinationStops.firstOrNull { it.stop.key == finalAlight.stopKey } ?: continue
            val secondLeg = transitLeg(secondTrip, secondBoard, midAlight, serviceDate, updates)
            val thirdLeg = transitLeg(thirdTrip, thirdBoard, finalAlight, serviceDate, updates)
            val t1 = TransferConnection(
                arrivingLeg = firstLeg, departingLeg = secondLeg,
                transferStop = firstAlight.stopKey,
                scheduledBuffer = secondLeg.departure - firstLeg.arrival,
                predictedBuffer = null, walkingDuration = walking1,
                confidence = TransferConfidence.SCHEDULED_UNKNOWN,
                rationale = "scheduled buffer from static timetable",
            )
            val t2 = TransferConnection(
                arrivingLeg = secondLeg, departingLeg = thirdLeg,
                transferStop = midAlight.stopKey,
                scheduledBuffer = thirdLeg.departure - secondLeg.arrival,
                predictedBuffer = null, walkingDuration = walking2,
                confidence = TransferConfidence.SCHEDULED_UNKNOWN,
                rationale = "scheduled buffer from static timetable",
            )
            val walkOut = walkLeg(request.origin, origin.stop.location, firstLeg.departure, request, anchorIsEnd = true) ?: continue
            val walkIn = walkLeg(destination.stop.location, request.destination, thirdLeg.arrival, request, anchorIsEnd = false) ?: continue
            val journey = JourneyOption(
                id = "${board.tripKey.tripId}|${secondBoard.tripKey.tripId}|${thirdBoard.tripKey.tripId}|transfer2",
                legs = listOf(walkOut, firstLeg, secondLeg, thirdLeg, walkIn),
                transfers = listOf(t1, t2),
                departure = walkOut.start,
                arrival = walkIn.end,
            )
            if (best == null || journey.arrival < best.arrival) best = journey
        }
        return best
    }

    /**
     * Finds the earliest onward trip that boards no earlier than the arrival,
     * including walking transfers.
     *
     * Boarding stops are the arrival stop plus:
     * - stops with the same parent station (GTFS station complexes),
     * - `transfers.txt` pairs from the arrival stop (excluding NO_TRANSFER).
     *
     * A connection is only catchable if its buffer covers walking time plus
     * the feed's `min_transfer_time`. Walking speed comes from the request.
     */
    private fun earliestConnection(
        firstAlight: StopTime,
        serviceDate: LocalDate,
        destinationKeys: Set<StopKey>,
        walkSpeedMps: Double,
    ): Quad<Trip, StopTime, Duration, List<StopTime>>? {
        val arrivalInstant = firstAlight.arrivalTime?.instantOn(serviceDate, effectiveZone) ?: return null
        val fromStop = network.stopsByKey[firstAlight.stopKey]
        // Candidate boarding stops with their required pre-departure buffer.
        data class BoardingStop(val key: StopKey, val requiredSeconds: Long)
        val boardingStops = LinkedHashMap<StopKey, Long>()
        boardingStops[firstAlight.stopKey] = 0L
        // Same-station siblings: platforms within one station complex.
        val parent = fromStop?.parentStation
        if (parent != null) {
            for (stop in network.stops) {
                if (stop.key == firstAlight.stopKey) continue
                if (stop.parentStation != parent && stop.key != parent) continue
                val walking = if (fromStop != null) haversineMeters(fromStop.location, stop.location) else 0.0
                val secs = (walking / walkSpeedMps).toLong()
                boardingStops[stop.key] = minOf(boardingStops[stop.key] ?: Long.MAX_VALUE, secs)
            }
        }
        // Explicit transfers.txt pairs.
        for (transfer in network.transfers) {
            if (transfer.fromStopKey != firstAlight.stopKey) continue
            if (transfer.type == com.intellitrip.domain.TransferType.NO_TRANSFER) continue
            val toStop = network.stopsByKey[transfer.toStopKey] ?: continue
            val walking = if (fromStop != null) haversineMeters(fromStop.location, toStop.location) else 0.0
            val secs = (walking / walkSpeedMps).toLong() + (transfer.minTransferSeconds ?: 0)
            boardingStops[transfer.toStopKey] = minOf(boardingStops[transfer.toStopKey] ?: Long.MAX_VALUE, secs.toLong())
        }
        var best: Quad<Trip, StopTime, Duration, List<StopTime>>? = null
        var bestDeparture: Instant? = null
        for ((boardingKey, requiredSeconds) in boardingStops) {
            val earliestBoard = arrivalInstant + requiredSeconds.seconds
            for (trip in network.tripsServing(boardingKey)) {
                if (trip.key == firstAlight.tripKey) continue
                if (!network.isActive(trip.serviceKey, serviceDate)) continue
                val times = network.stopTimesFor(trip.key)
                if (times.isEmpty()) continue
                // Consider every stop at this boarding stop, expanded for frequencies.
                val boardIndexes = times.indices.filter { times[it].stopKey == boardingKey }
                for (bi in boardIndexes) {
                    for ((candidate, shifted) in expandedRuns(trip.key, times, bi)) {
                        val departure = candidate.departureTime?.instantOn(serviceDate, effectiveZone) ?: continue
                        if (departure < earliestBoard) continue
                        if (bestDeparture == null || departure < bestDeparture) {
                            bestDeparture = departure
                            best = Quad(trip, candidate, requiredSeconds.seconds, shifted)
                        }
                    }
                }
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
        val scheduledDeparture = board.departureTime?.instantOn(serviceDate, effectiveZone)
        val scheduledArrival = alight.arrivalTime?.instantOn(serviceDate, effectiveZone)
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
        val departure = scheduledDeparture ?: Instant.DISTANT_PAST
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
            duration = arrival - departure,
        )
    }

    /**
     * Builds a walk leg anchored at a transit instant.
     *
     * [anchorIsEnd] = true means the walk must *finish* at [anchor] (origin
     * walk): the leg's start is `anchor - duration`, so the journey is only
     * catchable. false means the walk *starts* at [anchor] (destination walk).
     */
    private fun walkLeg(
        from: GeoPoint,
        to: GeoPoint,
        anchor: Instant,
        request: TripPlanRequest,
        anchorIsEnd: Boolean = false,
    ): Leg.WalkLeg? {
        val meters = haversineMeters(from, to)
        if (meters > request.maxWalkMeters) return null
        val seconds = (meters / request.walkSpeedMetersPerSecond).toLong().coerceAtLeast(0)
        val start = if (anchorIsEnd) anchor - seconds.seconds else anchor
        return Leg.WalkLeg(from, to, start, seconds.seconds, meters)
    }

    private data class Stoped(val stop: Stop, val meters: Double)

/**
     * Stops within [maxMeters], nearest first, capped at [limit].
     *
     * The cap matters more than it looks: in a dense downtown a 2 km walk reaches
     * hundreds of stops, and every stop contributes its whole set of trips to the
     * search. Taking the closest few keeps the search tractable without changing
     * results in practice, since the nearest stops dominate the best journeys.
     */
private fun nearestStops(point: GeoPoint, maxMeters: Double, limit: Int): List<Stoped> =
        network.stops
            .asSequence()
            .map { Stoped(it, haversineMeters(point, it.location)) }
            .filter { it.meters <= maxMeters }
            .sortedBy { it.meters }
            .take(limit)
            .toList()
}

/**
 * Converts a GTFS service-day-relative time into an instant for a service date in
 * the agency timezone. Times past 24:00:00 correctly land on the following day.
 */
fun GtfsServiceTime.instantOn(date: LocalDate, zone: TimeZone = TimeZone.of("America/Chicago")): Instant? {
    val seconds = secondsSinceServiceDayStart
    if (seconds < 0) return null
    val startOfDay = date.atStartOfDayIn(zone)
    return startOfDay + seconds.seconds
}

fun haversineMeters(a: GeoPoint, b: GeoPoint): Double = GeoMath.distanceMeters(a, b)

internal fun near(a: Double, b: Double, tolerance: Double = 1e-6): Boolean = abs(a - b) < tolerance

private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
