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
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter

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
    val stopTimesByTrip: Map<TripKey, List<StopTime>> by lazy {
        stopTimes.groupBy { it.tripKey }
    }

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
 * Parses GTFS Schedule tables into a feed-scoped [StaticFeed]. Required-field
 * validation follows the GTFS specification; optional files are tolerated.
 */
object GtfsStaticParser {

    private val dateFormat: DateTimeFormatter = DateTimeFormatter.BASIC_ISO_DATE

    fun parse(feedId: FeedId, files: Map<String, String>, metadata: FeedMetadata): StaticFeed {
        val required = listOf("routes.txt", "stops.txt", "trips.txt", "stop_times.txt")
        val missingRequired = required.filterNot { files.containsKey(it) }
        if (missingRequired.isNotEmpty()) {
            throw GtfsValidationException("Missing required GTFS files: $missingRequired")
        }
        if (!files.containsKey("agency.txt") &&
            !files.containsKey("calendar.txt") &&
            !files.containsKey("calendar_dates.txt")
        ) {
            throw GtfsValidationException("Missing required GTFS files: agency.txt")
        }

        val agencies = parseAgencies(feedId, files["agency.txt"])
        val routes = parseRoutes(feedId, files.getValue("routes.txt"), agencies)
        val stops = parseStops(feedId, files.getValue("stops.txt"))
        val trips = parseTrips(feedId, files.getValue("trips.txt"))
        val stopTimes = parseStopTimes(feedId, files.getValue("stop_times.txt"), trips, stops)
        val calendars = files["calendar.txt"]?.let { parseCalendars(feedId, it) } ?: emptyList()
        val calendarDates = files["calendar_dates.txt"]?.let { parseCalendarDates(feedId, it) } ?: emptyList()
        val shapes = files["shapes.txt"]?.let { parseShapes(feedId, it) } ?: emptyList()
        val frequencies = files["frequencies.txt"]?.let { parseFrequencies(feedId, it) } ?: emptyList()
        val transfers = files["transfers.txt"]?.let { parseTransfers(feedId, it) } ?: emptyList()

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

    private fun parseAgencies(feedId: FeedId, content: String?): List<Agency> {
        if (content == null) return emptyList()
        return GtfsCsv.parse(content).rows.mapIndexedNotNull { index, row ->
            val rawId = GtfsCsv.read(row, "agency_id")
            val name = GtfsCsv.read(row, "agency_name")
            if (name == null) {
                throw GtfsValidationException("agency.txt row ${index + 2} missing agency_name")
            }
            Agency(
                id = AgencyId(agencyIdentity(feedId, rawId)),
                feedId = feedId,
                name = name,
                url = GtfsCsv.read(row, "agency_url"),
                timezone = GtfsCsv.read(row, "agency_timezone")?.let { java.time.ZoneId.of(it) },
                lang = GtfsCsv.read(row, "agency_lang"),
                phone = GtfsCsv.read(row, "agency_phone"),
                fareUrl = GtfsCsv.read(row, "agency_fare_url"),
                email = GtfsCsv.read(row, "agency_email"),
            )
        }
    }

    /**
     * GTFS allows a feed to omit `agency_id` on single-agency feeds. Raw agency
     * identifiers are feed-scoped and may be absent, so Atlas agency identities
     * are synthesized from the feed namespace.
     */
    private fun agencyIdentity(feedId: FeedId, rawAgencyId: String?): String =
        "${feedId.value}/${rawAgencyId?.takeIf { it.isNotEmpty() } ?: "default"}"

    private fun parseRoutes(feedId: FeedId, content: String, agencies: List<Agency>): List<Route> {
        val table = GtfsCsv.parse(content)
        val singleAgency = agencies.singleOrNull()
        return table.rows.mapIndexed { index, row ->
            val routeId = GtfsCsv.read(row, "route_id")
                ?: throw GtfsValidationException("routes.txt row ${index + 2} missing route_id")
            val routeType = GtfsCsv.readInt(row, "route_type")
                ?: throw GtfsValidationException("routes.txt row ${index + 2} missing route_type")
            val agencyId = agencies
                .firstOrNull { it.id.value == agencyIdentity(feedId, GtfsCsv.read(row, "agency_id")) }
                ?.id
                ?: singleAgency?.id
            Route(
                key = RouteKey(feedId, routeId),
                agencyId = agencyId,
                shortName = GtfsCsv.read(row, "route_short_name"),
                longName = GtfsCsv.read(row, "route_long_name"),
                description = GtfsCsv.read(row, "route_desc"),
                routeType = routeType,
                url = GtfsCsv.read(row, "route_url"),
                color = GtfsCsv.read(row, "route_color"),
                textColor = GtfsCsv.read(row, "route_text_color"),
                sortOrder = GtfsCsv.readInt(row, "route_sort_order"),
            )
        }
    }

    private fun parseStops(feedId: FeedId, content: String): List<Stop> {
        return GtfsCsv.parse(content).rows.mapIndexed { index, row ->
            val stopId = GtfsCsv.read(row, "stop_id")
                ?: throw GtfsValidationException("stops.txt row ${index + 2} missing stop_id")
            val lat = GtfsCsv.readDouble(row, "stop_lat")
            val lon = GtfsCsv.readDouble(row, "stop_lon")
            if (lat == null || lon == null) {
                throw GtfsValidationException("stops.txt row ${index + 2} missing coordinates")
            }
            Stop(
                key = StopKey(feedId, stopId),
                code = GtfsCsv.read(row, "stop_code"),
                name = GtfsCsv.read(row, "stop_name") ?: stopId,
                description = GtfsCsv.read(row, "stop_desc"),
                location = GeoPoint(lat, lon),
                url = GtfsCsv.read(row, "stop_url"),
                locationType = GtfsCsv.readInt(row, "location_type"),
                parentStation = GtfsCsv.read(row, "parent_station")?.let { StopKey(feedId, it) },
                zoneId = GtfsCsv.read(row, "zone_id"),
            )
        }
    }

    private fun parseTrips(feedId: FeedId, content: String): List<Trip> {
        return GtfsCsv.parse(content).rows.mapIndexed { index, row ->
            val tripId = GtfsCsv.read(row, "trip_id")
                ?: throw GtfsValidationException("trips.txt row ${index + 2} missing trip_id")
            val routeId = GtfsCsv.read(row, "route_id")
                ?: throw GtfsValidationException("trips.txt row ${index + 2} missing route_id")
            val serviceId = GtfsCsv.read(row, "service_id")
                ?: throw GtfsValidationException("trips.txt row ${index + 2} missing service_id")
            Trip(
                key = TripKey(feedId, tripId),
                routeKey = RouteKey(feedId, routeId),
                serviceKey = ServiceKey(feedId, serviceId),
                headsign = GtfsCsv.read(row, "trip_headsign"),
                directionId = GtfsCsv.readInt(row, "direction_id"),
                blockId = GtfsCsv.read(row, "block_id"),
                shapeKey = GtfsCsv.read(row, "shape_id")?.let { ShapeKey(feedId, it) },
                wheelchairAccessible = GtfsCsv.readInt(row, "wheelchair_accessible"),
            )
        }
    }

    private fun parseStopTimes(
        feedId: FeedId,
        content: String,
        trips: List<Trip>,
        stops: List<Stop>,
    ): List<StopTime> {
        val tripIds = trips.map { it.key.tripId }.toSet()
        val stopIds = stops.map { it.key.stopId }.toSet()
        return GtfsCsv.parse(content).rows.mapIndexed { index, row ->
            val tripId = GtfsCsv.read(row, "trip_id")
                ?: throw GtfsValidationException("stop_times.txt row ${index + 2} missing trip_id")
            val stopId = GtfsCsv.read(row, "stop_id")
                ?: throw GtfsValidationException("stop_times.txt row ${index + 2} missing stop_id")
            val stopSequence = GtfsCsv.readInt(row, "stop_sequence")
                ?: throw GtfsValidationException("stop_times.txt row ${index + 2} missing stop_sequence")
            if (tripId !in tripIds) {
                throw GtfsValidationException("stop_times.txt row ${index + 2} references unknown trip $tripId")
            }
            if (stopId !in stopIds) {
                throw GtfsValidationException("stop_times.txt row ${index + 2} references unknown stop $stopId")
            }
            StopTime(
                tripKey = TripKey(feedId, tripId),
                stopKey = StopKey(feedId, stopId),
                stopSequence = stopSequence,
                arrivalTime = GtfsCsv.read(row, "arrival_time")?.let { GtfsServiceTime.parse(it) },
                departureTime = GtfsCsv.read(row, "departure_time")?.let { GtfsServiceTime.parse(it) },
                pickupType = GtfsCsv.readInt(row, "pickup_type"),
                dropOffType = GtfsCsv.readInt(row, "drop_off_type"),
                timepoint = GtfsCsv.readInt(row, "timepoint"),
                shapeDistTraveled = GtfsCsv.readDouble(row, "shape_dist_traveled"),
            )
        }.sortedWith(compareBy({ it.tripKey.tripId }, { it.stopSequence }))
    }

    private fun parseCalendars(feedId: FeedId, content: String): List<Calendar> {
        return GtfsCsv.parse(content).rows.mapIndexed { index, row ->
            val serviceId = GtfsCsv.read(row, "service_id")
                ?: throw GtfsValidationException("calendar.txt row ${index + 2} missing service_id")
            val startDate = GtfsCsv.read(row, "start_date")
                ?: throw GtfsValidationException("calendar.txt row ${index + 2} missing start_date")
            val endDate = GtfsCsv.read(row, "end_date")
                ?: throw GtfsValidationException("calendar.txt row ${index + 2} missing end_date")
            Calendar(
                serviceKey = ServiceKey(feedId, serviceId),
                daysOfWeek = DAY_FIELDS.mapNotNull { (field, day) ->
                    if (GtfsCsv.readInt(row, field) == 1) day else null
                }.toSet(),
                startDate = LocalDate.parse(startDate, dateFormat),
                endDate = LocalDate.parse(endDate, dateFormat),
            )
        }
    }

    private fun parseCalendarDates(feedId: FeedId, content: String): List<CalendarDate> {
        return GtfsCsv.parse(content).rows.mapIndexed { index, row ->
            val serviceId = GtfsCsv.read(row, "service_id")
                ?: throw GtfsValidationException("calendar_dates.txt row ${index + 2} missing service_id")
            val date = GtfsCsv.read(row, "date")
                ?: throw GtfsValidationException("calendar_dates.txt row ${index + 2} missing date")
            val exception = GtfsCsv.readInt(row, "exception_type")
                ?: throw GtfsValidationException("calendar_dates.txt row ${index + 2} missing exception_type")
            CalendarDate(
                serviceKey = ServiceKey(feedId, serviceId),
                date = LocalDate.parse(date, dateFormat),
                exceptionType = if (exception == 2) ServiceExceptionType.REMOVED else ServiceExceptionType.ADDED,
            )
        }
    }

    private fun parseShapes(feedId: FeedId, content: String): List<Shape> {
        val points = LinkedHashMap<String, MutableList<Pair<Int, GeoPoint>>>()
        GtfsCsv.parse(content).rows.forEachIndexed { index, row ->
            val shapeId = GtfsCsv.read(row, "shape_id")
                ?: throw GtfsValidationException("shapes.txt row ${index + 2} missing shape_id")
            val lat = GtfsCsv.readDouble(row, "shape_pt_lat")
            val lon = GtfsCsv.readDouble(row, "shape_pt_lon")
            val sequence = GtfsCsv.readInt(row, "shape_pt_sequence")
            if (lat == null || lon == null || sequence == null) {
                throw GtfsValidationException("shapes.txt row ${index + 2} incomplete shape point")
            }
            points.getOrPut(shapeId) { mutableListOf() }.add(sequence to GeoPoint(lat, lon))
        }
        return points.map { (shapeId, entries) ->
            Shape(
                key = ShapeKey(feedId, shapeId),
                points = entries.sortedBy { it.first }.map { it.second },
            )
        }
    }

    private fun parseFrequencies(feedId: FeedId, content: String): List<Frequency> {
        return GtfsCsv.parse(content).rows.mapIndexed { index, row ->
            val tripId = GtfsCsv.read(row, "trip_id")
                ?: throw GtfsValidationException("frequencies.txt row ${index + 2} missing trip_id")
            val startTime = GtfsCsv.read(row, "start_time")
                ?: throw GtfsValidationException("frequencies.txt row ${index + 2} missing start_time")
            val endTime = GtfsCsv.read(row, "end_time")
                ?: throw GtfsValidationException("frequencies.txt row ${index + 2} missing end_time")
            val headway = GtfsCsv.readInt(row, "headway_secs")
                ?: throw GtfsValidationException("frequencies.txt row ${index + 2} missing headway_secs")
            Frequency(
                tripKey = TripKey(feedId, tripId),
                startTime = GtfsServiceTime.parse(startTime),
                endTime = GtfsServiceTime.parse(endTime),
                headwaySeconds = headway,
                exactTimes = GtfsCsv.readInt(row, "exact_times"),
            )
        }
    }

    private fun parseTransfers(feedId: FeedId, content: String): List<Transfer> {
        val typeMap = mapOf(
            0 to TransferType.RECOMMENDED,
            1 to TransferType.TIMED,
            2 to TransferType.TRANSFER,
            3 to TransferType.NO_TRANSFER,
            4 to TransferType.RECOMMENDED,
            5 to TransferType.TIMED,
        )
        return GtfsCsv.parse(content).rows.mapNotNull { row ->
            val fromStop = GtfsCsv.read(row, "from_stop_id") ?: return@mapNotNull null
            val toStop = GtfsCsv.read(row, "to_stop_id") ?: return@mapNotNull null
            Transfer(
                fromStopKey = StopKey(feedId, fromStop),
                toStopKey = StopKey(feedId, toStop),
                type = typeMap[GtfsCsv.readInt(row, "transfer_type")] ?: TransferType.RECOMMENDED,
                minTransferSeconds = GtfsCsv.readInt(row, "min_transfer_time"),
            )
        }
    }

    private fun validateReferences(
        trips: List<Trip>,
        stops: List<Stop>,
        stopTimes: List<StopTime>,
        shapes: List<Shape>,
        routes: List<Route>,
    ) {
        val routeKeys = routes.map { it.key }.toSet()
        val stopKeys = stops.map { it.key }.toSet()
        val shapeKeys = shapes.map { it.key }.toSet()
        trips.forEach { trip ->
            if (trip.routeKey !in routeKeys) {
                throw GtfsValidationException("trip ${trip.key.tripId} references unknown route ${trip.routeKey.routeId}")
            }
            val shapeKey = trip.shapeKey
            if (shapeKey != null && shapeKeys.isNotEmpty() && shapeKey !in shapeKeys) {
                throw GtfsValidationException("trip ${trip.key.tripId} references unknown shape ${shapeKey.shapeId}")
            }
        }
        stopTimes.forEach { stopTime ->
            if (stopTime.stopKey !in stopKeys) {
                throw GtfsValidationException("stop_time references unknown stop ${stopTime.stopKey.stopId}")
            }
        }
    }

    private val DAY_FIELDS: List<Pair<String, DayOfWeek>> = listOf(
        "monday" to DayOfWeek.MONDAY,
        "tuesday" to DayOfWeek.TUESDAY,
        "wednesday" to DayOfWeek.WEDNESDAY,
        "thursday" to DayOfWeek.THURSDAY,
        "friday" to DayOfWeek.FRIDAY,
        "saturday" to DayOfWeek.SATURDAY,
        "sunday" to DayOfWeek.SUNDAY,
    )
}