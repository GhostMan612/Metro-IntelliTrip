package com.intellitrip.ui

import com.intellitrip.domain.GeoPoint
import com.intellitrip.domain.GtfsServiceTime
import com.intellitrip.domain.Route
import com.intellitrip.domain.Stop
import com.intellitrip.domain.Trip
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
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
    val routeShortName: String?,
    val routeLongName: String?,
    val headsign: String?,
    val routeColor: String?,
    val scheduledTime: Instant,
    val isLive: Boolean,
    val vehicleUpdatedAt: Instant?,
    /** Minutes until departure, computed once at build time and never negative. */
    val minutesUntil: Int,
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
        return "Route $route$where, $stopName, ${wait.lowercase()}, $kind"
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
 * - A time is only labeled live when a realtime vehicle is actually reporting that
 *   trip. Everything else is "Schedule". Where the feed sends a real prediction delay
 *   this is the place it would be applied; until then the scheduled time stands.
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
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<Departure> {
        val nearby = origin?.let { point ->
            stops.filter { abs(metersBetween(point, it.location)) <= radiusMeters }
                .sortedBy { abs(metersBetween(point, it.location)) }
                .take(maxStops)
        } ?: stops.take(maxStops)

        return nearby.flatMap { stop ->
            departuresFor(stop).asSequence()
                // Only what a rider could still board.
                .filter { it.departure.isAfter(now.minusSeconds(60)) }
                .take(perStop)
                .map { scheduled ->
                    val trip = tripsByKey[scheduled.tripId]
                    val route = trip?.let { routesByKey[it.routeKey.routeId] }
                    val live = liveTrips.contains(scheduled.tripId)
                    // A live vehicle replaces the timetable with what it reported.
                    val when_ = if (live && scheduled.reportedTime != null) {
                        scheduled.reportedTime
                    } else {
                        scheduled.departure
                    }
                    Departure(
                        stopName = stop.name,
                        routeShortName = route?.shortName,
                        routeLongName = route?.longName,
                        headsign = trip?.headsign,
                        routeColor = route?.color,
                        scheduledTime = when_,
                        isLive = live,
                        vehicleUpdatedAt = if (live) now else null,
                        minutesUntil = (Duration.between(now, when_).seconds / 60).toInt(),
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
        zone: ZoneId = ZoneId.systemDefault(),
    ): Instant {
        val seconds = serviceTime.secondsSinceServiceDayStart
        val today = LocalDate.ofInstant(now, zone)
        // Service days can start on the previous date, so yesterday's service day is a
        // real candidate. Only future occurrences are considered, and the earliest of
        // those wins.
        //
        // Choosing the *closest* candidate instead, without requiring it to be ahead,
        // resolved 25:30 at 07:00 to 01:30 the same morning: five and a half hours in
        // the past, and eight and a half hours away from the occurrence the rider can
        // actually board. Distance is the wrong tiebreak when one side is unusable.
        return (-1L..1L)
            .map { offset -> today.plusDays(offset) }
            .map { date -> date.atStartOfDay(zone).plusSeconds(seconds.toLong()).toInstant() }
            .filter { it.isAfter(now) }
            .minByOrNull { Duration.between(now, it).seconds }
            ?: today.atStartOfDay(zone).plusSeconds(seconds.toLong()).toInstant()
    }

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