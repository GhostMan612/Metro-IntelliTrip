package com.intellitrip.ui

import com.intellitrip.domain.GeoPoint
import com.intellitrip.domain.GtfsServiceTime
import com.intellitrip.domain.Route
import com.intellitrip.domain.Stop
import com.intellitrip.domain.StopKey
import com.intellitrip.domain.Trip
import kotlin.time.Duration.Companion.seconds
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.math.abs

/**
 * One upcoming departure at a nearby stop.
 *
 * Provenance is part of the value rather than decoration. The recovery plan is
 * explicit that a fabricated ETA is worse than no ETA, so every departure says
 * whether its time came from a vehicle report or from the printed timetable, and
 * [isLive] is the only thing allowed to drive the green "Live" badge.
 */
data class Departure(
    val stopName: String,
    /**
     * The exact stop this departure leaves from. Names are not unique in GTFS
     * (opposite-direction platforms and station complexes share one), so the
     * board and every tap-through resolve the stop by key.
     */
    val stopKey: StopKey,
    val routeShortName: String?,
    val routeLongName: String?,
    val headsign: String?,
    val routeColor: String?,
    val scheduledTime: Instant,
    val isLive: Boolean,
    val vehicleUpdatedAt: Instant?,
    /** Minutes until departure, computed once at build time and never negative. */
    val minutesUntil: Int,
    /** Headway in minutes when the trip is frequency-based, e.g. "every 10 min". */
    val headwayMinutes: Int? = null,
    /** Distance from the rider in meters, when known. */
    val distanceMeters: Double? = null,
    /** Straight-line walk time in minutes at 1.35 m/s, when distance is known. */
    val walkMinutes: Int? = null,
) {
    /** Present-tense wait text; "Due" when under a minute. */
    val wait: String
        get() = when {
            minutesUntil <= 0 -> "Due"
            else -> "$minutesUntil min"
        }

    /**
     * Screen-reader sentence.
     *
     * Names the route, the direction, the wait and the provenance, so a rider using
     * TalkBack gets the whole fact rather than the word "marker".
     */
    fun accessibilityLabel(): String {
        val route = routeShortName ?: routeLongName ?: "Bus"
        val where = headsign?.takeIf { it.isNotBlank() }?.let { " to $it" } ?: ""
        val kind = if (isLive) "live" else "scheduled"
        val freq = headwayMinutes?.let { ", every $it min" } ?: ""
        return "Route $route$where, $stopName, ${wait.lowercase()}, $kind$freq"
    }
}

/**
 * Nearby departures, computed from the schedule actually stored on the device.
 *
 * Two rules keep this honest:
 *
 * - Times come from the feed's stop_times via the disk index, interpreted against the
 *   feed's service day. GTFS allows hours above 24, so a trip at 25:30 belongs to the
 *   following morning and is not wrapped into "01:30 tonight".
 * - A time is only labeled live when a matching trip is reporting AND a stop-specific
 *   reported departure time exists. A vehicle position alone is not an ETA.
 */
object DepartureBoard {

    /**
     * Builds the board for stops near [origin].
     *
     * @param stops nearby stops, already sorted by distance.
     * @param departuresFor returns the scheduled departures at one stop.
     * @param liveTrips trip keys with a current vehicle report.
     * @param routesByKey and [tripsByKey] resolve labels.
     */
    fun build(
        now: Instant,
        origin: GeoPoint?,
        stops: List<Stop>,
        radiusMeters: Double,
        departuresFor: (Stop) -> List<ScheduledDeparture>,
        liveTrips: Set<String>,
        routesByKey: Map<String, Route>,
        tripsByKey: Map<String, Trip>,
        maxStops: Int = 6,
        perStop: Int = 3,
        // GTFS times are agency-local. Default to the feed's zone; callers with
        // a feed should pass the agency timezone explicitly.
        zone: TimeZone = TimeZone.of("America/Chicago"),
        headwayMinutesByTrip: Map<String, Int> = emptyMap(),
    ): List<Departure> {
        val nearby = origin?.let { point ->
            stops.filter { abs(metersBetween(point, it.location)) <= radiusMeters }
                .sortedBy { abs(metersBetween(point, it.location)) }
                .take(maxStops)
        } ?: stops.take(maxStops)

        return nearby.flatMap { stop ->
            departuresFor(stop).asSequence()
                // Compare the *effective* time before applying the per-stop cap.
                // An index's iteration order is not a departure-time ordering,
                // and a reported update may move a trip ahead of another.
                .map { scheduled ->
                    val predicted = if (scheduled.tripId in liveTrips) scheduled.reportedTime else null
                    scheduled to (predicted ?: scheduled.departure)
                }
                .filter { (_, departure) -> departure > now - 60.seconds }
                .sortedBy { (_, departure) -> departure }
                .take(perStop)
                .map { (scheduled, effectiveTime) ->
                    val trip = tripsByKey[scheduled.tripId]
                    val route = trip?.let { routesByKey[it.routeKey.routeId] }
                    val live = liveTrips.contains(scheduled.tripId) && scheduled.reportedTime != null
                    // A live vehicle replaces the timetable with what it reported.
                    val when_ = effectiveTime
                    val dist = origin?.let { metersBetween(it, stop.location) }
                    Departure(
                        stopName = stop.name,
                        stopKey = stop.key,
                        routeShortName = route?.shortName,
                        routeLongName = route?.longName,
                        headsign = trip?.headsign,
                        routeColor = route?.color,
                        scheduledTime = when_,
                        isLive = live,
                        // Never fabricate a vehicle timestamp from `now`: the caller
                        // supplies only a live-trip set, not vehicle reports, so the
                        // honest value is null until reportedTime is plumbed through.
                        vehicleUpdatedAt = scheduled.reportedTime,
                        minutesUntil = ((when_ - now).inWholeSeconds / 60).toInt(),
                        headwayMinutes = headwayMinutesByTrip[scheduled.tripId],
                        distanceMeters = dist,
                        walkMinutes = dist?.let { (it / 1.35 / 60).toInt().coerceAtLeast(1) },
                    )
                }
                .toList()
        }.sortedBy { it.scheduledTime }
    }

    /** One scheduled departure as stored in the feed, resolved to absolute time. */
    data class ScheduledDeparture(
        val tripId: String,
        val departure: Instant,
        /** Realtime-reported departure, when a vehicle supplied one. */
        val reportedTime: Instant? = null,
    )

    /**
     * The rows a rider can actually board at [stopKey].
     *
     * The stop-time index is keyed by trip, so asking it for the trips that
     * serve a stop returns every stop on each of those trips. A board must use
     * only the row at the stop it is labeling, or a downstream departure is
     * shown as if it left from the stop near the rider.
     */
    fun boardableRowsAt(
        rows: List<com.intellitrip.domain.StopTime>,
        stopKey: StopKey,
    ): List<com.intellitrip.domain.StopTime> = rows.filter { row ->
        row.stopKey == stopKey &&
            // GTFS pickup_type 1 means no boarding at this stop.
            row.pickupType != 1
    }

    /**
     * Resolves a GTFS service time to an absolute instant near [now].
     *
     * GTFS times are offsets from the start of the service day and may exceed 24h. A
     * service day can also begin on the previous calendar date, so the candidate is
     * chosen from today and the surrounding days rather than by truncating to a
     * LocalTime, which would silently move a 25:30 arrival to the wrong evening.
     */
    fun resolveServiceTime(
        serviceTime: GtfsServiceTime,
        now: Instant,
        zone: TimeZone = TimeZone.of("America/Chicago"),
    ): Instant {
        val seconds = serviceTime.secondsSinceServiceDayStart
        val today = now.toLocalDateTime(zone).date
        // Service days can start on the previous date, so yesterday's service day is a
        // real candidate. Only future occurrences are considered, and the earliest of
        // those wins.
        //
        // Choosing the *closest* candidate instead, without requiring it to be ahead,
        // resolved 25:30 at 07:00 to 01:30 the same morning: five and a half hours in
        // the past, and eight and a half hours away from the occurrence the rider can
        // actually board. Distance is the wrong tiebreak when one side is unusable.
        return (-1..1)
            .map { offset -> today.plus(offset, DateTimeUnit.DAY) }
            .map { date -> date.atStartOfDayIn(zone) + seconds.toLong().seconds }
            .filter { it > now }
            .minByOrNull { (it - now).inWholeSeconds }
            ?: today.atStartOfDayIn(zone) + seconds.toLong().seconds
    }

    /**
     * Whether [serviceKey] has service on [date]. Delegates to the shared
     * [com.intellitrip.domain.ServiceCalendar] so board and planner agree.
     */
    fun isServiceActive(
        calendars: List<com.intellitrip.domain.Calendar>,
        calendarDates: List<com.intellitrip.domain.CalendarDate>,
        serviceKey: com.intellitrip.domain.ServiceKey,
        date: LocalDate,
    ): Boolean = com.intellitrip.domain.ServiceCalendar(calendars, calendarDates)
        .isActive(serviceKey, date)

    /** Great-circle meters between two points. */
    fun metersBetween(a: GeoPoint, b: GeoPoint): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val lat1 = Math.toRadians(a.lat)
        val lat2 = Math.toRadians(b.lat)
        val h = kotlin.math.sin(dLat / 2) * kotlin.math.sin(dLat / 2) +
            kotlin.math.sin(dLon / 2) * kotlin.math.sin(dLon / 2) * kotlin.math.cos(lat1) * kotlin.math.cos(lat2)
        return 2 * r * kotlin.math.asin(kotlin.math.sqrt(h.coerceIn(0.0, 1.0)))
    }
}