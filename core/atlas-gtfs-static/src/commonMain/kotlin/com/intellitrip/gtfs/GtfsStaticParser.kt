package com.intellitrip.gtfs

import com.intellitrip.domain.Agency
import com.intellitrip.domain.AgencyId
import com.intellitrip.domain.Calendar
import com.intellitrip.domain.CalendarDate
import com.intellitrip.domain.FeedId
import com.intellitrip.domain.FeedMetadata
import com.intellitrip.domain.Frequency
import com.intellitrip.domain.GeoPoint
import com.intellitrip.domain.GtfsServiceTime
import com.intellitrip.domain.Route
import com.intellitrip.domain.RouteKey
import com.intellitrip.domain.ServiceExceptionType
import com.intellitrip.domain.ServiceKey
import com.intellitrip.domain.Shape
import com.intellitrip.domain.ShapeKey
import com.intellitrip.domain.Stop
import com.intellitrip.domain.StopKey
import com.intellitrip.domain.StopTime
import com.intellitrip.domain.Transfer
import com.intellitrip.domain.TransferType
import com.intellitrip.domain.Trip
import com.intellitrip.domain.TripKey
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone

/** The complete normalized static network for a single published feed. */
data class StaticFeed(
    val feedId: FeedId,
    val metadata: FeedMetadata,
    val agencies: List<Agency>,
    val routes: List<Route>,
    val stops: List<Stop>,
    val trips: List<Trip>,
    val stopTimes: List<StopTime>,
    val calendars: List<Calendar>,
    val calendarDates: List<CalendarDate>,
    val shapes: List<Shape>,
    val frequencies: List<Frequency>,
    val transfers: List<Transfer>,
) {
    val routesByKey: Map<RouteKey, Route> by lazy { routes.associateBy { it.key } }
    val stopsByKey: Map<StopKey, Stop> by lazy { stops.associateBy { it.key } }
    val tripsByKey: Map<TripKey, Trip> by lazy { trips.associateBy { it.key } }
    val shapesByKey: Map<ShapeKey, Shape> by lazy { shapes.associateBy { it.key } }
    val stopTimesByTrip: Map<TripKey, List<StopTime>> by lazy { stopTimes.groupBy { it.tripKey } }

    fun routesForAgency(agencyId: AgencyId?): List<Route> =
        if (agencyId == null) routes else routes.filter { it.agencyId == agencyId }

    fun stopsForAgency(agencyId: AgencyId?): List<Stop> {
        if (agencyId == null) return stops
        val routeKeys = routesForAgency(agencyId).map { it.key }.toSet()
        val tripKeys = trips.filter { it.routeKey in routeKeys }.map { it.key }
        val stopKeys = stopTimes.filter { it.tripKey in tripKeys }.map { it.stopKey }.toSet()
        return stops.filter { it.key in stopKeys }
    }

    fun tripsForRoute(routeKey: RouteKey?): List<Trip> =
        if (routeKey == null) trips else trips.filter { it.routeKey == routeKey }
}

class GtfsValidationException(message: String) : Exception(message)

/**
 * Parses GTFS Schedule tables into a feed-scoped [StaticFeed].
 *
 * Each table is streamed row by row and its raw bytes are released
 * immediately afterwards. Required-field validation follows the GTFS
 * specification; optional files are tolerated when absent.
 */
/**
 * Receives stop-time rows as they stream out of the parser. Hosts that cannot
 * retain hundreds of thousands of rows (phone hosts) write them to disk
 * instead.
 */
fun interface StopTimeSink {
    fun accept(stopTime: StopTime)
}

private class CollectingStopTimeSink : StopTimeSink {
    val rows = mutableListOf<StopTime>()
    override fun accept(stopTime: StopTime) {
        rows += stopTime
    }
}

object GtfsStaticParser {

    /** Parses a GTFS `yyyyMMdd` date (formerly `BASIC_ISO_DATE`); also accepts ISO `yyyy-MM-dd`. */
    private fun parseGtfsDate(value: String): LocalDate {
        if (value.contains('-')) return LocalDate.parse(value)
        val year = value.substring(0, 4).toInt()
        val month = value.substring(4, 6).toInt()
        val day = value.substring(6, 8).toInt()
        return LocalDate(year, month, day)
    }

    private val DAY_FIELDS = listOf(
        "monday" to DayOfWeek.MONDAY,
        "tuesday" to DayOfWeek.TUESDAY,
        "wednesday" to DayOfWeek.WEDNESDAY,
        "thursday" to DayOfWeek.THURSDAY,
        "friday" to DayOfWeek.FRIDAY,
        "saturday" to DayOfWeek.SATURDAY,
        "sunday" to DayOfWeek.SUNDAY,
    )

    /** Convenience overload for tests and callers that already hold decoded text. */
    fun parse(feedId: FeedId, files: Map<String, String>, metadata: FeedMetadata): StaticFeed =
        parse(feedId, files.mapValues { it.value.encodeToByteArray() }.toMutableMap(), metadata)

    /**
     * Parses directly from a GTFS ZIP payload held as bytes. Entry bytes are
     * inflated one table at a time and released as soon as the table is
     * consumed, and rows inside a table stream row-by-row, so at most one
     * table's bytes are live alongside the payload itself (the ZIP bytes are
     * the caller's; the inflated table is dropped after its rows).
     */
    fun parseFromZip(
        feedId: FeedId,
        zipBytes: ByteArray,
        metadata: FeedMetadata,
        stopTimeSink: StopTimeSink?,
    ): StaticFeed {
        val zip = try {
            ZipReader(zipBytes)
        } catch (e: GtfsArchiveException) {
            throw e
        } catch (e: Exception) {
            throw GtfsArchiveException("Invalid GTFS ZIP payload", e)
        }
        fun entry(name: String): ByteArray? = zip.readByBasename(name)
        val required = listOf("routes.txt", "stops.txt", "trips.txt", "stop_times.txt")
        val missing = required.filter { !zip.hasBasename(it) }
        if (missing.isNotEmpty()) {
            throw GtfsValidationException("Missing required GTFS files: $missing")
        }
        val hasAgencyLike = listOf("agency.txt", "calendar.txt", "calendar_dates.txt").any { zip.hasBasename(it) }
        if (!hasAgencyLike) throw GtfsValidationException("Missing required GTFS files: agency.txt")

        // Agencies first (routes needs them), then the small tables. stop_times
        // stays last: it is streamed to the disk sink row-by-row and needs
        // trips+stops. Parsing is sequential in common code; the old JVM
        // parallel path produced identical rows with more scheduling surface,
        // and the big table was already sequential, so the win was small.
        val agencies = entry("agency.txt")?.let { parseAgencies(feedId, it) } ?: emptyList()
        val routes = entry("routes.txt")!!.let { parseRoutes(feedId, it, agencies) }
        val stops = entry("stops.txt")!!.let { parseStops(feedId, it) }
        val trips = entry("trips.txt")!!.let { parseTrips(feedId, it) }
        val calendars = entry("calendar.txt")?.let { parseCalendars(feedId, it) } ?: emptyList()
        val calendarDates = entry("calendar_dates.txt")?.let { parseCalendarDates(feedId, it) } ?: emptyList()
        val shapes = entry("shapes.txt")?.let { parseShapes(feedId, it) } ?: emptyList()
        val frequencies = entry("frequencies.txt")?.let { parseFrequencies(feedId, it) } ?: emptyList()
        val transfers = entry("transfers.txt")?.let { parseTransfers(feedId, it) } ?: emptyList()

        val collector = if (stopTimeSink == null) CollectingStopTimeSink() else null
        val stopTimes = entry("stop_times.txt")!!.let {
            parseStopTimes(feedId, it, trips, stops, collector, stopTimeSink)
        }
        // validateReferences uses in-memory stopTimes (empty when sunk to disk).
        val stopTimesForValidation = collector?.rows ?: stopTimes
        validateReferences(trips, stops, stopTimesForValidation, shapes, routes)
        return StaticFeed(
            feedId = feedId,
            metadata = metadata,
            agencies = agencies,
            routes = routes,
            stops = stops,
            trips = trips,
            stopTimes = stopTimesForValidation,
            calendars = calendars,
            calendarDates = calendarDates,
            shapes = shapes,
            frequencies = frequencies,
            transfers = transfers,
        )
    }

    /**
     * Parses from already-inflated table bytes (pairs with [parseFromZip]).
     *
     * The sink parameter has a default so the three-argument bytes call and
     * the four-argument (sinking) call share one overload; that also keeps
     * the JVM signatures distinct (the two three-argument overloads would
     * otherwise erase together on the JVM).
     */
    fun parse(
        feedId: FeedId,
        files: MutableMap<String, ByteArray>,
        metadata: FeedMetadata,
        stopTimeSink: StopTimeSink? = null,
    ): StaticFeed {
        val required = listOf("routes.txt", "stops.txt", "trips.txt", "stop_times.txt")
        val missing = required.filterNot { files.containsKey(it) }
        if (missing.isNotEmpty()) {
            throw GtfsValidationException("Missing required GTFS files: $missing")
        }
        if (!files.containsKey("agency.txt") &&
            !files.containsKey("calendar.txt") &&
            !files.containsKey("calendar_dates.txt")
        ) {
            throw GtfsValidationException("Missing required GTFS files: agency.txt")
        }

        val agencies = files.consume("agency.txt") { parseAgencies(feedId, it) } ?: emptyList()
        val routes = files.consume("routes.txt") { parseRoutes(feedId, it, agencies) } ?: emptyList()
        val stops = files.consume("stops.txt") { parseStops(feedId, it) } ?: emptyList()
        val trips = files.consume("trips.txt") { parseTrips(feedId, it) } ?: emptyList()
        val collector = if (stopTimeSink == null) CollectingStopTimeSink() else null
        val stopTimes = files.consume("stop_times.txt") {
            parseStopTimes(feedId, it, trips, stops, collector, stopTimeSink)
        } ?: emptyList()
        val calendars = files.consume("calendar.txt") { parseCalendars(feedId, it) } ?: emptyList()
        val calendarDates = files.consume("calendar_dates.txt") { parseCalendarDates(feedId, it) } ?: emptyList()
        val shapes = files.consume("shapes.txt") { parseShapes(feedId, it) } ?: emptyList()
        val frequencies = files.consume("frequencies.txt") { parseFrequencies(feedId, it) } ?: emptyList()
        val transfers = files.consume("transfers.txt") { parseTransfers(feedId, it) } ?: emptyList()

        validateReferences(trips, stops, stopTimes, shapes, routes)

        return StaticFeed(
            feedId = feedId,
            metadata = metadata,
            agencies = agencies,
            routes = routes,
            stops = stops,
            trips = trips,
            stopTimes = stopTimes,
            calendars = calendars,
            calendarDates = calendarDates,
            shapes = shapes,
            frequencies = frequencies,
            transfers = transfers,
        )
    }

    /**
     * Streams a single table and releases its bytes afterwards so large
     * tables do not accumulate in memory.
     */
    private inline fun <T> MutableMap<String, ByteArray>.consume(
        name: String,
        parse: (ByteArray) -> T,
    ): T? {
        val bytes = remove(name) ?: return null
        return try {
            parse(bytes)
        } finally {
            // Help the collector reclaim the largest buffers promptly.
            @Suppress("UNUSED_EXPRESSION")
            bytes
        }
    }

    private fun parseAgencies(feedId: FeedId, bytes: ByteArray): List<Agency> {
        val result = mutableListOf<Agency>()
        GtfsCsv.forEachRow(
            bytes = bytes,
            onHeader = {},
            onRow = { columns, values ->
                val name = GtfsCsv.value(values, columns, "agency_name")
                    ?: throw GtfsValidationException("agency.txt row is missing agency_name")
                result += Agency(
                    id = AgencyId(agencyIdentity(feedId, GtfsCsv.value(values, columns, "agency_id"))),
                    feedId = feedId,
                    name = name,
                    url = GtfsCsv.value(values, columns, "agency_url"),
                    timezone = GtfsCsv.value(values, columns, "agency_timezone")?.let {
                        runCatching { TimeZone.of(it) }.getOrNull()
                    },
                    lang = GtfsCsv.value(values, columns, "agency_lang"),
                    phone = GtfsCsv.value(values, columns, "agency_phone"),
                    fareUrl = GtfsCsv.value(values, columns, "agency_fare_url"),
                    email = GtfsCsv.value(values, columns, "agency_email"),
                )
            },
        )
        return result
    }

    /** GTFS allows single-agency feeds to omit `agency_id`. */
    private fun agencyIdentity(feedId: FeedId, rawAgencyId: String?): String =
        "${feedId.value}/${rawAgencyId?.takeIf { it.isNotEmpty() } ?: "default"}"

    private fun parseRoutes(feedId: FeedId, bytes: ByteArray, agencies: List<Agency>): List<Route> {
        val singleAgency = agencies.singleOrNull()
        val result = mutableListOf<Route>()
        GtfsCsv.forEachRow(bytes, onHeader = {}, onRow = { columns, values ->
            val routeId = GtfsCsv.value(values, columns, "route_id")
                ?: throw GtfsValidationException("routes.txt row is missing route_id")
            val routeType = GtfsCsv.int(values, columns, "route_type")
                ?: throw GtfsValidationException("routes.txt row is missing route_type")
            val agencyId = agencies
                .firstOrNull { it.id.value == agencyIdentity(feedId, GtfsCsv.value(values, columns, "agency_id")) }
                ?.id
                ?: singleAgency?.id
            result += Route(
                key = RouteKey(feedId, routeId),
                agencyId = agencyId,
                shortName = GtfsCsv.value(values, columns, "route_short_name"),
                longName = GtfsCsv.value(values, columns, "route_long_name"),
                description = GtfsCsv.value(values, columns, "route_desc"),
                routeType = routeType,
                url = GtfsCsv.value(values, columns, "route_url"),
                color = GtfsCsv.value(values, columns, "route_color"),
                textColor = GtfsCsv.value(values, columns, "route_text_color"),
                sortOrder = GtfsCsv.int(values, columns, "route_sort_order"),
            )
        })
        return result
    }

    private fun parseStops(feedId: FeedId, bytes: ByteArray): List<Stop> {
        val result = mutableListOf<Stop>()
        GtfsCsv.forEachRow(bytes, onHeader = {}, onRow = { columns, values ->
            val stopId = GtfsCsv.value(values, columns, "stop_id")
                ?: throw GtfsValidationException("stops.txt row is missing stop_id")
            val lat = GtfsCsv.double(values, columns, "stop_lat")
            val lon = GtfsCsv.double(values, columns, "stop_lon")
            if (lat == null || lon == null) {
                throw GtfsValidationException("stops.txt row is missing coordinates")
            }
            result += Stop(
                key = StopKey(feedId, stopId),
                code = GtfsCsv.value(values, columns, "stop_code"),
                name = GtfsCsv.value(values, columns, "stop_name") ?: stopId,
                description = GtfsCsv.value(values, columns, "stop_desc"),
                location = GeoPoint(lat, lon),
                url = GtfsCsv.value(values, columns, "stop_url"),
                locationType = GtfsCsv.int(values, columns, "location_type"),
                parentStation = GtfsCsv.value(values, columns, "parent_station")?.let { StopKey(feedId, it) },
                zoneId = GtfsCsv.value(values, columns, "zone_id"),
            )
        })
        return result
    }

    private fun parseTrips(feedId: FeedId, bytes: ByteArray): List<Trip> {
        val result = mutableListOf<Trip>()
        GtfsCsv.forEachRow(bytes, onHeader = {}, onRow = { columns, values ->
            val tripId = GtfsCsv.value(values, columns, "trip_id")
                ?: throw GtfsValidationException("trips.txt row is missing trip_id")
            val routeId = GtfsCsv.value(values, columns, "route_id")
                ?: throw GtfsValidationException("trips.txt row is missing route_id")
            val serviceId = GtfsCsv.value(values, columns, "service_id")
                ?: throw GtfsValidationException("trips.txt row is missing service_id")
            result += Trip(
                key = TripKey(feedId, tripId),
                routeKey = RouteKey(feedId, routeId),
                serviceKey = ServiceKey(feedId, serviceId),
                headsign = GtfsCsv.value(values, columns, "trip_headsign"),
                directionId = GtfsCsv.int(values, columns, "direction_id"),
                blockId = GtfsCsv.value(values, columns, "block_id"),
                shapeKey = GtfsCsv.value(values, columns, "shape_id")?.let { ShapeKey(feedId, it) },
                wheelchairAccessible = GtfsCsv.int(values, columns, "wheelchair_accessible"),
            )
        })
        return result
    }

    private fun parseStopTimes(
        feedId: FeedId,
        bytes: ByteArray,
        trips: List<Trip>,
        stops: List<Stop>,
        collector: CollectingStopTimeSink?,
        sink: StopTimeSink?,
    ): List<StopTime> {
        // Key interning: stop_times is by far the largest table (hundreds of
        // thousands of rows) and every row repeats trip/stop ids. Allocating a
        // fresh key per row exhausted a phone heap, so keys are canonicalized
        // against the already-parsed trips and stops.
        val tripKeyById = HashMap<String, TripKey>(trips.size * 2)
        trips.forEach { tripKeyById[it.key.tripId] = it.key }
        val stopKeyById = HashMap<String, StopKey>(stops.size * 2)
        stops.forEach { stopKeyById[it.key.stopId] = it.key }

        // When the host supplies a sink, rows go straight to it (off-heap). When
        // it does not, they are collected so callers get an in-memory feed.
        val target: StopTimeSink = sink ?: collector ?: StopTimeSink { }
        var iTrip = -1; var iStop = -1; var iSeq = -1; var iArr = -1; var iDep = -1
        var iPickup = -1; var iDrop = -1; var iTp = -1; var iDist = -1
        GtfsCsv.forEachRow(bytes, onHeader = {
            iTrip = it.indexOf("trip_id"); iStop = it.indexOf("stop_id"); iSeq = it.indexOf("stop_sequence")
            iArr = it.indexOf("arrival_time"); iDep = it.indexOf("departure_time")
            iPickup = it.indexOf("pickup_type"); iDrop = it.indexOf("drop_off_type")
            iTp = it.indexOf("timepoint"); iDist = it.indexOf("shape_dist_traveled")
        }, onRow = { _, values ->
            fun field(idx: Int): String? = values.getOrNull(idx)?.trim()?.takeIf { it.isNotEmpty() }
            val tripId = field(iTrip)
                ?: throw GtfsValidationException("stop_times.txt row is missing trip_id")
            val stopId = field(iStop)
                ?: throw GtfsValidationException("stop_times.txt row is missing stop_id")
            val stopSequence = field(iSeq)?.toIntOrNull()
                ?: throw GtfsValidationException("stop_times.txt row is missing stop_sequence")
            val tripKey = tripKeyById[tripId]
                ?: throw GtfsValidationException("stop_times.txt references unknown trip $tripId")
            val stopKey = stopKeyById[stopId]
                ?: throw GtfsValidationException("stop_times.txt references unknown stop $stopId")
            target.accept(
                StopTime(
                    tripKey = tripKey,
                    stopKey = stopKey,
                    stopSequence = stopSequence,
                    arrivalTime = field(iArr)?.let { GtfsServiceTime.parse(it) },
                    departureTime = field(iDep)?.let { GtfsServiceTime.parse(it) },
                    pickupType = field(iPickup)?.toIntOrNull(),
                    dropOffType = field(iDrop)?.toIntOrNull(),
                    timepoint = field(iTp)?.toIntOrNull(),
                    shapeDistTraveled = field(iDist)?.toDoubleOrNull(),
                )
            )
        })
        return collector?.rows?.sortedWith(STOP_TIME_ORDER) ?: emptyList()
    }

    private val STOP_TIME_ORDER = compareBy<StopTime>({ it.tripKey.tripId }, { it.stopSequence })

    private fun parseCalendars(feedId: FeedId, bytes: ByteArray): List<Calendar> {
        val result = mutableListOf<Calendar>()
        GtfsCsv.forEachRow(bytes, onHeader = {}, onRow = { columns, values ->
            val serviceId = GtfsCsv.value(values, columns, "service_id")
                ?: throw GtfsValidationException("calendar.txt row is missing service_id")
            val startDate = GtfsCsv.value(values, columns, "start_date")
                ?: throw GtfsValidationException("calendar.txt row is missing start_date")
            val endDate = GtfsCsv.value(values, columns, "end_date")
                ?: throw GtfsValidationException("calendar.txt row is missing end_date")
            result += Calendar(
                serviceKey = ServiceKey(feedId, serviceId),
                daysOfWeek = DAY_FIELDS.mapNotNull { (field, day) ->
                    if (GtfsCsv.int(values, columns, field) == 1) day else null
                }.toSet(),
                startDate = parseGtfsDate(startDate),
                endDate = parseGtfsDate(endDate),
            )
        })
        return result
    }

    private fun parseCalendarDates(feedId: FeedId, bytes: ByteArray): List<CalendarDate> {
        val result = mutableListOf<CalendarDate>()
        GtfsCsv.forEachRow(bytes, onHeader = {}, onRow = { columns, values ->
            val serviceId = GtfsCsv.value(values, columns, "service_id")
                ?: throw GtfsValidationException("calendar_dates.txt row is missing service_id")
            val date = GtfsCsv.value(values, columns, "date")
                ?: throw GtfsValidationException("calendar_dates.txt row is missing date")
            val exception = GtfsCsv.int(values, columns, "exception_type")
                ?: throw GtfsValidationException("calendar_dates.txt row is missing exception_type")
            result += CalendarDate(
                serviceKey = ServiceKey(feedId, serviceId),
                date = parseGtfsDate(date),
                exceptionType = if (exception == 2) ServiceExceptionType.REMOVED else ServiceExceptionType.ADDED,
            )
        })
        return result
    }

    private fun parseShapes(feedId: FeedId, bytes: ByteArray): List<Shape> {
        val points = HashMap<String, MutableList<Pair<Int, GeoPoint>>>()
        GtfsCsv.forEachRow(bytes, onHeader = {}, onRow = { columns, values ->
            val shapeId = GtfsCsv.value(values, columns, "shape_id")
                ?: throw GtfsValidationException("shapes.txt row is missing shape_id")
            val lat = GtfsCsv.double(values, columns, "shape_pt_lat")
            val lon = GtfsCsv.double(values, columns, "shape_pt_lon")
            val sequence = GtfsCsv.int(values, columns, "shape_pt_sequence")
            if (lat == null || lon == null || sequence == null) {
                throw GtfsValidationException("shapes.txt row has an incomplete shape point")
            }
            points.getOrPut(shapeId) { mutableListOf() }.add(sequence to GeoPoint(lat, lon))
        })
        return points.map { (shapeId, entries) ->
            // Most feeds write shapes in sequence order; skip the sort when so.
            val sorted = if (entries.zipWithNext().all { (a, b) -> a.first <= b.first }) {
                entries
            } else {
                entries.sortedBy { it.first }
            }
            Shape(
                key = ShapeKey(feedId, shapeId),
                points = downsample(sorted.map { it.second }),
            )
        }
    }

    /**
     * Caps points per shape at parse time. A system-wide feed carries hundreds
     * of thousands of shape points; retaining them all exhausted a phone heap.
     */
    private fun downsample(points: List<GeoPoint>, maxPoints: Int = MAX_SHAPE_POINTS_PER_SHAPE): List<GeoPoint> {
        if (points.size <= maxPoints) return points
        val step = (points.size / maxPoints).coerceAtLeast(1)
        val reduced = ArrayList<GeoPoint>(maxPoints + 1)
        var index = 0
        while (index < points.size) {
            reduced += points[index]
            index += step
        }
        if (reduced.last() != points.last()) reduced += points.last()
        return reduced
    }

    internal const val MAX_SHAPE_POINTS_PER_SHAPE = 1500

    private fun parseFrequencies(feedId: FeedId, bytes: ByteArray): List<Frequency> {
        val result = mutableListOf<Frequency>()
        GtfsCsv.forEachRow(bytes, onHeader = {}, onRow = { columns, values ->
            val tripId = GtfsCsv.value(values, columns, "trip_id")
                ?: throw GtfsValidationException("frequencies.txt row is missing trip_id")
            val startTime = GtfsCsv.value(values, columns, "start_time")
                ?: throw GtfsValidationException("frequencies.txt row is missing start_time")
            val endTime = GtfsCsv.value(values, columns, "end_time")
                ?: throw GtfsValidationException("frequencies.txt row is missing end_time")
            val headway = GtfsCsv.int(values, columns, "headway_secs")
                ?: throw GtfsValidationException("frequencies.txt row is missing headway_secs")
            result += Frequency(
                tripKey = TripKey(feedId, tripId),
                startTime = GtfsServiceTime.parse(startTime),
                endTime = GtfsServiceTime.parse(endTime),
                headwaySeconds = headway,
                exactTimes = GtfsCsv.int(values, columns, "exact_times"),
            )
        })
        return result
    }

    private fun parseTransfers(feedId: FeedId, bytes: ByteArray): List<Transfer> {
        val typeMap = mapOf(
            0 to TransferType.RECOMMENDED,
            1 to TransferType.TIMED,
            2 to TransferType.TRANSFER,
            3 to TransferType.NO_TRANSFER,
            4 to TransferType.RECOMMENDED,
            5 to TransferType.TIMED,
        )
        val result = mutableListOf<Transfer>()
        GtfsCsv.forEachRow(bytes, onHeader = {}, onRow = { columns, values ->
            val fromStop = GtfsCsv.value(values, columns, "from_stop_id") ?: return@forEachRow
            val toStop = GtfsCsv.value(values, columns, "to_stop_id") ?: return@forEachRow
            result += Transfer(
                fromStopKey = StopKey(feedId, fromStop),
                toStopKey = StopKey(feedId, toStop),
                type = typeMap[GtfsCsv.int(values, columns, "transfer_type")] ?: TransferType.RECOMMENDED,
                minTransferSeconds = GtfsCsv.int(values, columns, "min_transfer_time"),
            )
        })
        return result
    }

    private fun validateReferences(
        trips: List<Trip>,
        stops: List<Stop>,
        stopTimes: List<StopTime>,
        shapes: List<Shape>,
        routes: List<Route>,
    ) {
        val routeKeys = routes.mapTo(HashSet(routes.size * 2)) { it.key }
        val stopKeys = stops.mapTo(HashSet(stops.size * 2)) { it.key }
        val shapeKeys = shapes.mapTo(HashSet(shapes.size * 2)) { it.key }
        trips.forEach { trip ->
            if (!routeKeys.contains(trip.routeKey)) {
                throw GtfsValidationException("trip ${trip.key.tripId} references unknown route ${trip.routeKey.routeId}")
            }
            val shapeKey = trip.shapeKey
            if (shapeKey != null && shapeKeys.isNotEmpty() && !shapeKeys.contains(shapeKey)) {
                throw GtfsValidationException("trip ${trip.key.tripId} references unknown shape ${shapeKey.shapeId}")
            }
        }
        stopTimes.forEach { stopTime ->
            if (!stopKeys.contains(stopTime.stopKey)) {
                throw GtfsValidationException("stop_time references unknown stop ${stopTime.stopKey.stopId}")
            }
        }
    }
}
