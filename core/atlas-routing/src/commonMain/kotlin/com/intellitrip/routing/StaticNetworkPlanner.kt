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
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive
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

    /** Transfers leaving each stop. Indexed because the transfer search is a hot path. */
    val transfersByFromStop: Map<StopKey, List<Transfer>> by lazy {
        transfers.groupBy { it.fromStopKey }
    }

    /** Platforms grouped by parent station, for same-complex transfers. */
    val stopsByParentStation: Map<StopKey, List<Stop>> by lazy {
        stops.filter { it.parentStation != null }.groupBy { it.parentStation!! }
    }

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

    override suspend fun plan(request: TripPlanRequest): ProviderResult<List<JourneyOption>> {
        val callerContext = coroutineContext
        callerContext.ensureActive()
        return planWithRealtime(request, emptyList(), callerContext::ensureActive)
    }

    fun planWithRealtime(
        request: TripPlanRequest,
        updates: List<TripUpdate>,
    ): ProviderResult<List<JourneyOption>> = planWithRealtime(request, updates) {}

    /**
     * Plans a journey using static schedules plus optional [updates], polling
     * [checkCancelled] at cooperative checkpoints across stop scanning, candidate
     * expansion, and transfer searches.
     */
    fun planWithRealtime(
        request: TripPlanRequest,
        updates: List<TripUpdate>,
        checkCancelled: () -> Unit,
    ): ProviderResult<List<JourneyOption>> {
        checkCancelled()
        val earliest = request.departAt ?: Clock.System.now() + 60.seconds
        val serviceDate = earliest.toLocalDateTime(effectiveZone).date

        val originStops = nearestStops(request.origin, request.maxWalkMeters, maxStopsPerEnd, checkCancelled)
        val destinationStops = nearestStops(request.destination, request.maxWalkMeters, maxStopsPerEnd, checkCancelled)
        if (originStops.isEmpty() || destinationStops.isEmpty()) {
            val missing = buildList {
                add("stops-within-walk-limit")
                if (originStops.isEmpty()) add("no-origin-stops-within-walk-limit")
                if (destinationStops.isEmpty()) add("no-destination-stops-within-walk-limit")
            }
            return ProviderResult.PartialResult(
                data = emptyList(),
                missing = missing,
                freshness = freshness(),
            )
        }

        checkCancelled()
        val options = buildOptions(
            request,
            originStops,
            destinationStops,
            serviceDate,
            earliest,
            earliest + (departureWindowMinutes * 60).seconds,
            updates,
            checkCancelled,
        )
        checkCancelled()
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
        checkCancelled: () -> Unit,
    ): List<JourneyOption> {
        checkCancelled()
        val destinationKeys = destinationStops.map { it.stop.key }.toSet()
        val options = mutableListOf<JourneyOption>()
        val maxTransfers = request.maxTransfers ?: 1
        val context = SearchContext(serviceDate, destinationKeys, checkCancelled)

        // Candidate boardings, gathered across all origin stops and ordered by
        // departure so the search can stop once it has seen enough.
        //
        // A rider walks to one stop, so the access walk and the boarding stop
        // must be the same place. Downstream stops on the same trip are *not*
        // interchangeable: boarding there would need a different walk that the
        // journey never accounts for.
        val candidates = ArrayList<Candidate>()
        for (origin in originStops) {
            checkCancelled()
            for (trip in context.tripsAt(origin.stop.key)) {
                checkCancelled()
                if (!context.isServiceActive(trip.serviceKey)) continue
                val times = network.stopTimesFor(trip.key)
                if (times.isEmpty()) continue
                val boardIndex = times.indexOfFirst { it.stopKey == origin.stop.key }
                if (boardIndex < 0) continue
                if (!allowsBoarding(times[boardIndex])) continue

                // Frequency trips repeat at headway intervals; expand each
                // repetition as its own candidate (bounded, window-filtered).
                for ((board, shiftedTimes) in expandedRuns(trip.key, times, boardIndex, checkCancelled)) {
                    checkCancelled()
                    val boardInstant =
                        board.departureTime?.instantOn(serviceDate, effectiveZone) ?: continue
                    if (boardInstant < earliest) continue
                    if (boardInstant > horizonEnd) continue
                    candidates += Candidate(origin, boardIndex, board, shiftedTimes)
                }
            }
        }

        // Earliest departures first, then take only as many as we are prepared to
        // expand. Two-transfer search branches quadratically across intermediate
        // stops, so its candidate pool is bounded tighter than single-transfer.
        val candidateLimit = if (maxTransfers >= 2) {
            minOf(maxCandidates, MAX_TWO_TRANSFER_CANDIDATES)
        } else {
            maxCandidates
        }
        val chosen = candidates
            .sortedBy { it.board.departureTime?.instantOn(serviceDate, effectiveZone) }
            .take(candidateLimit)

        // A first leg may alight anywhere the agency allows, not only inside
        // the destination walk area: a transfer station is usually not near
        // the destination at all.
        //
        // Before paying for a timed connection search, require that some trip
        // at the alighting stop can actually get the rider closer: reach the
        // destination (one transfer) or reach a stop that can (two transfers).
        // This is an existence check, not a timetable check; the search below
        // still enforces times and restrictions.
        // Top-N cutoff: once enough journeys are known, a later alighting stop
        // (which arrives no earlier) cannot improve the result set. This keeps
        // the transfer scan from re-searching the whole route after the answer
        // is already on the table.
        fun journeyCutoff(): Instant? {
            if (options.size < maxJourneys) return null
            val arrivals = options.map { it.arrival }.sorted()
            return arrivals[maxJourneys - 1]
        }

        val arriveBy = request.arriveBy
        for (candidate in chosen) {
            checkCancelled()
            val origin = candidate.origin
            val board = candidate.board
            val boardInstant = board.departureTime?.instantOn(serviceDate, effectiveZone)
            // Once departures pass arriveBy and at least one option is recorded
            // (either feasible or proof of arrive-by-unachievable), later departures
            // can never arrive in time.
            if (arriveBy != null && boardInstant != null && boardInstant >= arriveBy && options.isNotEmpty()) {
                break
            }
            val times = candidate.times
            val firstAlightIndex = candidate.boardIndex + 1
            val routeKey = network.tripsByKey[board.tripKey]?.routeKey
            val lastStopKey = times.lastOrNull()?.stopKey

            // Direct: the first downstream stop inside the destination area
            // that permits alighting.
            val directIndex = (firstAlightIndex until times.size).firstOrNull { index ->
                checkCancelled()
                times[index].stopKey in destinationKeys && allowsAlighting(times[index])
            }
            if (directIndex != null) {
                val direct = directJourney(
                    request, origin, board, times[directIndex], destinationStops, serviceDate, updates,
                )
                if (direct != null) options += direct
            }

            if (maxTransfers < 1) continue
            val cutoff = journeyCutoff()
            val visitedTransferStops = HashSet<StopKey>()
            for (index in firstAlightIndex until times.size) {
                checkCancelled()
                val alight = times[index]
                if (!allowsAlighting(alight)) continue
                // A stop inside the destination area can still be a transfer
                // point (downtown platforms are both); the direct journey and
                // the onward search are alternatives, not substitutes.
                // Loop routes revisit stops; one connection attempt per stop.
                if (!visitedTransferStops.add(alight.stopKey)) continue

                val alightArrival = alight.arrivalTime?.instantOn(serviceDate, effectiveZone)
                val loopCutoff = minInstant(cutoff, if (options.isNotEmpty()) arriveBy else null)
                if (loopCutoff != null && alightArrival != null && alightArrival >= loopCutoff) break

                val reachesDestination = context.destinationServiceAt(alight.stopKey)
                val reachesAnotherTransfer = maxTransfers >= 2 &&
                    (arriveBy == null || alightArrival == null || alightArrival < arriveBy) &&
                    context.transferServiceAt(
                        alight.stopKey,
                        excludingRoute = routeKey,
                        // A trip that ends here needs another trip of its own
                        // route to continue; that is a real transfer.
                        allowSameRoute = alight.stopKey == lastStopKey,
                    )
                if (!reachesDestination && !reachesAnotherTransfer) continue

                if (reachesAnotherTransfer) {
                    val onward2 = doubleOnwardJourney(
                        request,
                        origin,
                        board,
                        alight,
                        destinationStops,
                        serviceDate,
                        updates,
                        context,
                        cutoff,
                        checkCancelled,
                    )
                    if (onward2 != null) options += onward2
                }
                if (reachesDestination) {
                    val onward = onwardJourney(
                        request,
                        origin,
                        board,
                        alight,
                        destinationStops,
                        serviceDate,
                        updates,
                        context,
                        checkCancelled,
                    )
                    if (onward != null) options += onward
                }
            }
        }
        return options
    }

    /** A boarding opportunity discovered while scanning candidate trips. */
    private class Candidate(
        val origin: Stoped,
        val boardIndex: Int,
        val board: StopTime,
        /** The trip's times for the run this boarding belongs to. */
        val times: List<StopTime>,
    )

    /** Memo key for route-aware transfer pruning. */
    private data class TransferServiceKey(
        val stop: StopKey,
        val excludingRoute: RouteKey?,
        val allowSameRoute: Boolean,
    )

    /**
     * Per-plan reachability caches.
     *
     * A stop is worth a timed connection search only if some trip there can
     * reach the destination (one transfer) or reach a stop that can (two
     * transfers). These checks deliberately ignore the timetable: they are
     * necessary conditions used to prune, never a substitute for the search.
     *
     * The sets come from reverse indexes at the destination stops, not from a
     * scan of every trip in the feed. Scanning the feed per plan cost several
     * seconds of stop-time parsing on the real Metro Transit data.
     */
    private inner class SearchContext(
        val serviceDate: LocalDate,
        val destinationKeys: Set<StopKey>,
        val checkCancelled: () -> Unit,
    ) {
        private val serviceCalendar = ServiceCalendar(
            network.calendars,
            network.calendarDates,
            network.activeServiceDates,
        )
        private val activeServiceMemo = HashMap<ServiceKey, Boolean>()
        private val tripsAtMemo = HashMap<StopKey, List<Trip>>()
        private val destinationServiceMemo = HashMap<StopKey, Boolean>()
        private val transferServiceMemo = HashMap<TransferServiceKey, Boolean>()

        /** Memoized service-calendar check for [serviceDate]. */
        fun isServiceActive(serviceKey: ServiceKey): Boolean =
            activeServiceMemo.getOrPut(serviceKey) {
                serviceCalendar.isActive(serviceKey, serviceDate)
            }

        /** Trips that have a downstream destination stop allowing alighting. */
        private val destinationTrips: Set<TripKey> by lazy {
            val result = HashSet<TripKey>()
            for (stop in destinationKeys) {
                checkCancelled()
                for (trip in tripsAt(stop)) {
                    checkCancelled()
                    if (trip.key in result) continue
                    if (!isServiceActive(trip.serviceKey)) continue
                    if (network.stopTimesFor(trip.key).any { row ->
                            row.stopKey in destinationKeys && allowsAlighting(row)
                        }
                    ) {
                        result += trip.key
                    }
                }
            }
            result
        }

        /**
         * Stops served by a destination-reaching trip: anywhere a second leg
         * could hand the rider to a third leg that finishes the journey.
         */
        private val transferHubStops: Set<StopKey> by lazy {
            val result = HashSet<StopKey>()
            for (trip in destinationTrips) {
                checkCancelled()
                for (row in network.stopTimesFor(trip)) result += row.stopKey
            }
            result
        }

        /**
         * Trips that pass through a transfer hub. A superset of "reaches a hub
         * after boarding", which is all pruning needs and far cheaper: it is
         * built from the reverse stop index without reading stop times.
         */
        private val transferTrips: Set<TripKey> by lazy {
            val result = HashSet<TripKey>()
            for (stop in transferHubStops) {
                checkCancelled()
                for (trip in tripsAt(stop)) {
                    checkCancelled()
                    if (isServiceActive(trip.serviceKey)) result += trip.key
                }
            }
            result
        }

        /** Trips serving a stop; memoized because the search asks repeatedly. */
        fun tripsAt(stop: StopKey): List<Trip> {
            checkCancelled()
            return tripsAtMemo.getOrPut(stop) { network.tripsServing(stop) }
        }

        /** True when some trip serving [stop] can reach the destination. */
        fun destinationServiceAt(stop: StopKey): Boolean =
            destinationServiceMemo.getOrPut(stop) {
                directDestinationServiceAt(stop) ||
                    linkedBoardingStops(stop).any { directDestinationServiceAt(it) }
            }

        /**
         * True when some trip serving [stop] can reach a destination-serving
         * stop. Stops where only the rider's current route continues are not
         * real interchanges: staying on the vehicle is at least as good,
         * unless this trip terminates here (a short-turn needs its own route
         * to continue).
         */
        fun transferServiceAt(
            stop: StopKey,
            excludingRoute: RouteKey? = null,
            allowSameRoute: Boolean = true,
        ): Boolean = transferServiceMemo.getOrPut(
            TransferServiceKey(stop, excludingRoute, allowSameRoute),
        ) {
            directTransferServiceAt(stop, excludingRoute, allowSameRoute) ||
                linkedBoardingStops(stop).any {
                    directTransferServiceAt(it, excludingRoute, allowSameRoute)
                }
        }

        private fun directDestinationServiceAt(stop: StopKey): Boolean =
            tripsAt(stop).any { it.key in destinationTrips }

        private fun directTransferServiceAt(
            stop: StopKey,
            excludingRoute: RouteKey?,
            allowSameRoute: Boolean,
        ): Boolean = tripsAt(stop).any { trip ->
            trip.key in transferTrips &&
                (allowSameRoute || excludingRoute == null || trip.routeKey != excludingRoute)
        }

        /**
         * Stops a rider may board without a journey leg: platforms sharing a
         * parent station, and `transfers.txt` pairs. Kept in step with
         * [earliestConnection] so pruning never rejects a stop the timed
         * search would have accepted.
         */
        private fun linkedBoardingStops(stop: StopKey): List<StopKey> {
            val linked = LinkedHashSet<StopKey>()
            val parent = network.stopsByKey[stop]?.parentStation
            if (parent != null) {
                linked += parent
                network.stopsByParentStation[parent].orEmpty().forEach { linked += it.key }
            }
            network.transfersByFromStop[stop].orEmpty().forEach { transfer ->
                if (transfer.type != com.intellitrip.domain.TransferType.NO_TRANSFER) {
                    linked += transfer.toStopKey
                }
            }
            linked.remove(stop)
            return linked.toList()
        }

        private val destinationConnectionMemo = HashMap<StopKey, List<Trip>>()
        private val transferConnectionMemo = HashMap<StopKey, List<Trip>>()

        /**
         * Active trips that can carry the rider onward from [stop]. Memoized per
         * plan because transfer searches revisit busy hubs across many first legs.
         */
        fun connectionTripsAt(
            stop: StopKey,
            allowTransfer: Boolean,
        ): List<Trip> {
            val memo = if (allowTransfer) transferConnectionMemo else destinationConnectionMemo
            return memo.getOrPut(stop) {
                tripsAt(stop).filter { trip ->
                    isServiceActive(trip.serviceKey) &&
                        (trip.key in destinationTrips || (allowTransfer && trip.key in transferTrips))
                }
            }
        }
    }

    /** GTFS `pickup_type` 1 means boarding is not allowed at this stop. */
    private fun allowsBoarding(stopTime: StopTime): Boolean = stopTime.pickupType != 1

    /** GTFS `drop_off_type` 1 means alighting is not allowed at this stop. */
    private fun allowsAlighting(stopTime: StopTime): Boolean = stopTime.dropOffType != 1

    /**
     * Stable identity for a journey.
     *
     * Two departures must never share an id: the app selects the active
     * journey by id, so a collision can highlight a different departure. The
     * service date, trip, boarding stop and boarding time separate frequency
     * runs of the same template trip, and the transfer stops record the
     * sequence the journey takes.
     */
    private fun journeyId(
        serviceDate: LocalDate,
        board: StopTime,
        kind: String,
        transferStops: List<StopKey> = emptyList(),
    ): String {
        val departureSeconds = board.departureTime?.secondsSinceServiceDayStart ?: -1
        return buildString {
            append(board.tripKey.feedId.value).append('|')
            append(serviceDate).append('|')
            append(board.tripKey.tripId).append('|')
            append(board.stopKey.stopId).append('@').append(departureSeconds)
            transferStops.forEach { stop -> append("|via:").append(stop.stopId) }
            append('|').append(kind)
        }
    }

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
        checkCancelled: () -> Unit,
    ): List<Pair<StopTime, List<StopTime>>> {
        checkCancelled()
        val freqs = network.frequenciesByTrip[tripKey]
        if (freqs.isNullOrEmpty()) return listOf(times[boardIndex] to times)
        val firstDepSec = times.firstOrNull()?.departureTime?.secondsSinceServiceDayStart ?: return listOf(times[boardIndex] to times)
        val out = mutableListOf<Pair<StopTime, List<StopTime>>>()
        for (freq in freqs) {
            checkCancelled()
            val headway = freq.headwaySeconds
            if (headway <= 0) continue
            var runStart = freq.startTime.secondsSinceServiceDayStart
            var count = 0
            // All-day 5-min headway is 288 runs; 50 truncates legitimate service.
            while (runStart <= freq.endTime.secondsSinceServiceDayStart && count < 300) {
                checkCancelled()
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
            id = journeyId(serviceDate, board, "direct"),
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
        context: SearchContext,
        checkCancelled: () -> Unit,
    ): JourneyOption? {
        checkCancelled()
        val destinationKeys = context.destinationKeys
        val connection = earliestConnection(
            firstAlight,
            context,
            allowTransfer = false,
            walkSpeedMps = request.walkSpeedMetersPerSecond,
            checkCancelled = checkCancelled,
        ) ?: return null
        val (secondTrip, secondBoard, walkingDuration, secondTimes) = connection
        val boardIndex = secondTimes.indexOfFirst { it.stopKey == secondBoard.stopKey && it.stopSequence == secondBoard.stopSequence }
        if (boardIndex < 0) return null
        // Alighting at the final stop must be permitted, not merely nearby.
        val finalAlight = secondTimes.drop(boardIndex + 1)
            .firstOrNull { it.stopKey in destinationKeys && allowsAlighting(it) } ?: return null
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
            id = journeyId(serviceDate, board, "transfer", listOf(firstAlight.stopKey)),
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
        context: SearchContext,
        cutoff: Instant?,
        checkCancelled: () -> Unit,
    ): JourneyOption? {
        checkCancelled()
        val destinationKeys = context.destinationKeys
        val conn1 = earliestConnection(
            firstAlight,
            context,
            allowTransfer = true,
            walkSpeedMps = request.walkSpeedMetersPerSecond,
            checkCancelled = checkCancelled,
        ) ?: return null
        val (secondTrip, secondBoard, walking1, secondTimes) = conn1
        val boardIdx2 = secondTimes.indexOfFirst { it.stopKey == secondBoard.stopKey && it.stopSequence == secondBoard.stopSequence }
        if (boardIdx2 < 0) return null
        val initialCutoff = minInstant(cutoff, request.arriveBy)
        val secondBoardDep = secondBoard.departureTime?.instantOn(serviceDate, effectiveZone)
        if (initialCutoff != null && secondBoardDep != null && secondBoardDep >= initialCutoff) return null
        var best: JourneyOption? = null
        val firstLegTrip = network.tripsByKey[board.tripKey] ?: return null
        val firstLeg = transitLeg(firstLegTrip, board, firstAlight, serviceDate, updates)
        val visitedMidStops = HashSet<StopKey>()
        for (midIdx in boardIdx2 + 1 until secondTimes.size) {
            checkCancelled()
            val midAlight = secondTimes[midIdx]
            if (!allowsAlighting(midAlight)) continue
            if (!visitedMidStops.add(midAlight.stopKey)) continue
            // Stops arrive in order, so once this leg reaches the current
            // top-N cutoff, best arrival found so far, or arriveBy deadline,
            // no later mid stop can produce a better journey.
            val midArrival = midAlight.arrivalTime?.instantOn(serviceDate, effectiveZone)
            val loopCutoff = minInstant(cutoff, best?.arrival, request.arriveBy)
            if (loopCutoff != null && midArrival != null && midArrival >= loopCutoff) break
            // The third leg must be boardable here and able to reach the
            // destination; otherwise the timed search below is wasted work.
            if (!context.destinationServiceAt(midAlight.stopKey)) continue
            val conn2 = earliestConnection(
                midAlight,
                context,
                allowTransfer = false,
                walkSpeedMps = request.walkSpeedMetersPerSecond,
                checkCancelled = checkCancelled,
            ) ?: continue
            val (thirdTrip, thirdBoard, walking2, thirdTimes) = conn2
            if (thirdTrip.key == secondTrip.key) continue
            val boardIdx3 = thirdTimes.indexOfFirst { it.stopKey == thirdBoard.stopKey && it.stopSequence == thirdBoard.stopSequence }
            if (boardIdx3 < 0) continue
            val finalAlight = thirdTimes.drop(boardIdx3 + 1)
                .firstOrNull { it.stopKey in destinationKeys && allowsAlighting(it) } ?: continue
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
                id = journeyId(
                    serviceDate,
                    board,
                    "transfer2",
                    listOf(firstAlight.stopKey, midAlight.stopKey),
                ),
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
        context: SearchContext,
        allowTransfer: Boolean,
        walkSpeedMps: Double,
        checkCancelled: () -> Unit,
    ): Quad<Trip, StopTime, Duration, List<StopTime>>? {
        checkCancelled()
        val arrivalSeconds =
            firstAlight.arrivalTime?.secondsSinceServiceDayStart ?: return null
        val fromStop = network.stopsByKey[firstAlight.stopKey]
        val fromLocation = fromStop?.location
        // Candidate boarding stops with their required pre-departure buffer.
        val boardingStops = LinkedHashMap<StopKey, Long>()
        boardingStops[firstAlight.stopKey] = 0L
        // Same-station siblings: platforms within one station complex. The
        // sibling list is indexed on the network; scanning every stop here was
        // an O(stops) cost inside the hottest loop in the search.
        val parent = fromStop?.parentStation
        if (parent != null && fromLocation != null) {
            val siblings = buildList {
                addAll(network.stopsByParentStation[parent].orEmpty())
                network.stopsByKey[parent]?.let { add(it) }
            }
            for (stop in siblings) {
                checkCancelled()
                if (stop.key == firstAlight.stopKey) continue
                val walking = haversineMeters(fromLocation, stop.location)
                val secs = (walking / walkSpeedMps).toLong()
                boardingStops[stop.key] = minOf(boardingStops[stop.key] ?: Long.MAX_VALUE, secs)
            }
        }
        // Explicit transfers.txt pairs.
        for (transfer in network.transfersByFromStop[firstAlight.stopKey].orEmpty()) {
            checkCancelled()
            if (transfer.type == com.intellitrip.domain.TransferType.NO_TRANSFER) continue
            val toStop = network.stopsByKey[transfer.toStopKey] ?: continue
            val walking = if (fromLocation != null) haversineMeters(fromLocation, toStop.location) else 0.0
            val secs = (walking / walkSpeedMps).toLong() + (transfer.minTransferSeconds ?: 0)
            boardingStops[transfer.toStopKey] = minOf(boardingStops[transfer.toStopKey] ?: Long.MAX_VALUE, secs.toLong())
        }
        var best: Quad<Trip, StopTime, Duration, List<StopTime>>? = null
        var bestDepartureSeconds: Long? = null
        for ((boardingKey, requiredSeconds) in boardingStops) {
            checkCancelled()
            val earliestBoardSeconds = arrivalSeconds + requiredSeconds
            // Only trips that can actually reach the destination (or, for a
            // middle leg, another transfer) are candidates at all.
            val onwardTrips = context.connectionTripsAt(boardingKey, allowTransfer)
            for (trip in onwardTrips) {
                checkCancelled()
                if (trip.key == firstAlight.tripKey) continue
                val times = network.stopTimesFor(trip.key)
                if (times.isEmpty()) continue
                // Consider every stop at this boarding stop, expanded for frequencies.
                // A stop that prohibits pickup can never be a boarding point.
                for (bi in times.indices) {
                    val stopTime = times[bi]
                    if (stopTime.stopKey != boardingKey || !allowsBoarding(stopTime)) continue
                    checkCancelled()
                    val (candidate, shifted) =
                        earliestCatchableRun(trip.key, times, bi, earliestBoardSeconds, checkCancelled) ?: continue
                    val departureSeconds =
                        candidate.departureTime?.secondsSinceServiceDayStart?.toLong() ?: continue
                    if (bestDepartureSeconds == null || departureSeconds < bestDepartureSeconds) {
                        bestDepartureSeconds = departureSeconds
                        best = Quad(trip, candidate, requiredSeconds.seconds, shifted)
                    }
                }
            }
        }
        return best
    }

    /**
     * The earliest run of [tripKey] that departs [boardIndex] at or after
     * [earliestDepartureSeconds], with only that run materialized.
     *
     * A frequency trip repeats its template up to hundreds of times. Copying
     * every repetition for every candidate connection is what made the
     * transfer search unusable on a real feed; the earliest catchable run is a
     * closed-form calculation, and later runs on the same template can only
     * arrive later, so the winner is the only run the search needs here.
     */
    private fun earliestCatchableRun(
        tripKey: TripKey,
        times: List<StopTime>,
        boardIndex: Int,
        earliestDepartureSeconds: Long,
        checkCancelled: () -> Unit,
    ): Pair<StopTime, List<StopTime>>? {
        checkCancelled()
        val templateBoard = times[boardIndex]
        val templateDeparture =
            templateBoard.departureTime?.secondsSinceServiceDayStart?.toLong() ?: return null
        val freqs = network.frequenciesByTrip[tripKey]
        if (freqs.isNullOrEmpty()) {
            return if (templateDeparture >= earliestDepartureSeconds) templateBoard to times else null
        }
        val firstDeparture =
            times.firstOrNull()?.departureTime?.secondsSinceServiceDayStart?.toLong() ?: return null
        val minDeparture = times.mapNotNull { it.departureTime?.secondsSinceServiceDayStart }.minOrNull() ?: 0
        var bestDelta: Long? = null
        for (freq in freqs) {
            checkCancelled()
            val headway = freq.headwaySeconds.toLong()
            if (headway <= 0) continue
            val windowStart = freq.startTime.secondsSinceServiceDayStart.toLong()
            val windowEnd = freq.endTime.secondsSinceServiceDayStart.toLong()
            val base = windowStart - firstDeparture
            // Smallest shift satisfying the requested departure, the run
            // window, and the non-negative-time guard.
            val lowerBound = maxOf(
                base,
                earliestDepartureSeconds - templateDeparture,
                -minDeparture.toLong(),
            )
            var steps = ceilDiv(lowerBound - base, headway)
            if (steps < 0) steps = 0
            val delta = base + steps * headway
            if (firstDeparture + delta > windowEnd) continue
            if (bestDelta == null || delta < bestDelta!!) bestDelta = delta
        }
        val delta = bestDelta ?: return null
        val shift = delta.toInt()
        val shifted = times.map { row ->
            row.copy(
                arrivalTime = row.arrivalTime?.let {
                    GtfsServiceTime.ofSeconds(it.secondsSinceServiceDayStart + shift)
                },
                departureTime = row.departureTime?.let {
                    GtfsServiceTime.ofSeconds(it.secondsSinceServiceDayStart + shift)
                },
            )
        }
        return shifted[boardIndex] to shifted
    }

    /** Ceiling division for a positive divisor, valid for negative dividends. */
    private fun ceilDiv(value: Long, divisor: Long): Long {
        val quotient = value / divisor
        return if (value < 0 || quotient * divisor == value) quotient else quotient + 1
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
    private fun nearestStops(
        point: GeoPoint,
        maxMeters: Double,
        limit: Int,
        checkCancelled: () -> Unit,
    ): List<Stoped> {
        checkCancelled()
        val matches = ArrayList<Stoped>()
        for (stop in network.stops) {
            checkCancelled()
            val meters = haversineMeters(point, stop.location)
            if (meters <= maxMeters) {
                matches += Stoped(stop, meters)
            }
        }
        matches.sortBy { it.meters }
        return if (matches.size <= limit) matches else matches.subList(0, limit).toList()
    }

    companion object {
        private const val MAX_TWO_TRANSFER_CANDIDATES = 120

        private fun minInstant(a: Instant?, b: Instant?, c: Instant? = null): Instant? {
            var best = a
            if (b != null && (best == null || b < best)) best = b
            if (c != null && (best == null || c < best)) best = c
            return best
        }
    }
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
