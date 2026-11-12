package com.intellitrip.ui

import com.intellitrip.domain.Frequency
import com.intellitrip.domain.GeoPoint
import com.intellitrip.domain.GeoMath
import com.intellitrip.domain.GtfsServiceTime
import com.intellitrip.domain.Route
import com.intellitrip.domain.Stop
import com.intellitrip.domain.Trip
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.math.abs

/** What the displayed time actually proves. */
enum class DepartureStatus {
    /** Printed timetable only. */
    SCHEDULED,

    /** A fresh, stop-specific GTFS-Realtime prediction. */
    LIVE,

    /** The provider says this trip will not serve the stop. */
    SKIPPED,

    /** The provider cancelled the trip. */
    CANCELED,
}

/**
 * One upcoming departure at a nearby stop.
 *
 * Provenance is part of the value rather than decoration. The recovery plan is
 * explicit that a fabricated ETA is worse than no ETA, so every departure says
 * whether its time came from a stop-specific prediction or from the printed
 * timetable, and [isLive] is the only thing allowed to drive the green "Live"
 * badge. A vehicle position alone is [vehicleReporting], not a prediction.
 */
data class Departure(
    val stopName: String,
    val routeShortName: String?,
    val routeLongName: String?,
    val headsign: String?,
    val routeColor: String?,
    val scheduledTime: Instant,
    val isLive: Boolean,
    /** A vehicle is reporting this trip, even when it supplied no stop ETA. */
    val vehicleReporting: Boolean = false,
    /** Timestamp of the stop prediction that justified the displayed time. */
    val vehicleUpdatedAt: Instant?,
    val status: DepartureStatus = DepartureStatus.SCHEDULED,
    /** Age of the prediction in seconds at board build time. */
    val predictionAgeSeconds: Long? = null,
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
        val kind = when (status) {
            DepartureStatus.LIVE -> "live"
            DepartureStatus.CANCELED -> "canceled"
            DepartureStatus.SKIPPED -> "skipped"
            DepartureStatus.SCHEDULED ->
                if (vehicleReporting) "scheduled, vehicle reporting" else "scheduled"
        }
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
 * - A time is only labeled live when a realtime vehicle is actually reporting that
 *   trip. Everything else is "Schedule". Where the feed sends a real prediction delay
 *   this is the place it would be applied; until then the scheduled time stands.
 */
object DepartureBoard {

    /**
     * Bound for frequency expansion. A 2h board horizon cannot legitimately
     * contain more than 120 one-minute runs; the larger cap protects against
     * malformed data without changing real results.
     */
    private const val MAX_FREQUENCY_RUNS = 300

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
                .map { scheduled ->
                    val trip = tripsByKey[scheduled.tripId]
                    val route = trip?.let { routesByKey[it.routeKey.routeId] }
                    // Provenance is explicit: a vehicle location is not a
                    // stop time. Only a matching, fresh stop update replaces
                    // the timetable.
                    val status = when {
                        scheduled.status == DepartureStatus.CANCELED -> DepartureStatus.CANCELED
                        scheduled.status == DepartureStatus.SKIPPED -> DepartureStatus.SKIPPED
                        scheduled.reportedTime != null -> DepartureStatus.LIVE
                        else -> DepartureStatus.SCHEDULED
                    }
                    val when_ = if (status == DepartureStatus.LIVE) {
                        scheduled.reportedTime ?: scheduled.departure
                    } else {
                        scheduled.departure
                    }
                    val dist = origin?.let { metersBetween(it, stop.location) }
                    Departure(
                        stopName = stop.name,
                        routeShortName = route?.shortName,
                        routeLongName = route?.longName,
                        headsign = trip?.headsign,
                        routeColor = route?.color,
                        scheduledTime = when_,
                        isLive = status == DepartureStatus.LIVE,
                        vehicleReporting = liveTrips.contains(scheduled.tripId),
                        vehicleUpdatedAt = scheduled.reportedAt,
                        status = status,
                        predictionAgeSeconds = scheduled.reportedAt?.let {
                            (now - it).inWholeSeconds.coerceAtLeast(0)
                        },
                        minutesUntil = ((when_ - now).inWholeSeconds / 60).toInt(),
                        headwayMinutes = headwayMinutesByTrip[scheduled.tripId],
                        distanceMeters = dist,
                        walkMinutes = dist?.let { com.intellitrip.domain.GeoMath.walkMinutes(it) },
                    )
                }
                // Only what a rider could still board. Filter on the displayed
                // time, so a prediction that has already gone is not shown as
                // if it were still ahead.
                .filter { it.scheduledTime > now - 60.seconds }
                .sortedBy { it.scheduledTime }
                .take(perStop)
                .toList()
        }.sortedBy { it.scheduledTime }
    }

    /** One scheduled departure as stored in the feed, resolved to absolute time. */
    data class ScheduledDeparture(
        val tripId: String,
        val departure: Instant,
        /** Stop-specific realtime prediction, never a bare vehicle presence. */
        val reportedTime: Instant? = null,
        /** Provider timestamp of [reportedTime]. */
        val reportedAt: Instant? = null,
        /** Explicit skipped/canceled state supplied by the provider. */
        val status: DepartureStatus = DepartureStatus.SCHEDULED,
        /** The GTFS service date this occurrence belongs to. */
        val serviceDate: LocalDate? = null,
        /** The stop this occurrence serves. */
        val stopId: String? = null,
        /** Frequency run start, used to match a stop update to its run. */
        val frequencyRunStartSeconds: Int? = null,
    )

    /**
     * Expands one stop-time row into the actual boardable occurrences near
     * [now], binding every occurrence to the service date that made it valid.
     *
     * The old board tested "is any candidate date active?" and then resolved a
     * separate wall-clock time. That could advertise a trip on a date it does
     * not run. Here the date is chosen first, and only that date's occurrence
     * is constructed.
     */
    fun occurrences(
        tripId: String,
        stopId: String,
        serviceTime: GtfsServiceTime,
        activeDates: List<LocalDate>,
        zone: TimeZone,
        now: Instant,
        frequencies: List<Frequency> = emptyList(),
        firstDepartureSeconds: Int? = null,
        horizon: Duration = 2.hours,
    ): List<ScheduledDeparture> {
        val earliest = now - 60.seconds
        val latest = now + horizon
        val out = mutableListOf<ScheduledDeparture>()

        if (frequencies.isEmpty()) {
            activeDates.forEach { date ->
                val instant = onServiceDate(serviceTime, date, zone)
                if (instant >= earliest && instant <= latest) {
                    out += ScheduledDeparture(
                        tripId = tripId,
                        departure = instant,
                        serviceDate = date,
                        stopId = stopId,
                    )
                }
            }
            return out
        }

        // Expand each frequency window on its own service date. The template
        // stop time is shifted by the offset from the trip's first departure,
        // exactly as the planner does.
        val first = firstDepartureSeconds ?: serviceTime.secondsSinceServiceDayStart
        for (date in activeDates) {
            for (frequency in frequencies) {
                if (frequency.headwaySeconds <= 0) continue
                var runStart = frequency.startTime.secondsSinceServiceDayStart
                var count = 0
                while (runStart <= frequency.endTime.secondsSinceServiceDayStart && count < MAX_FREQUENCY_RUNS) {
                    val seconds = serviceTime.secondsSinceServiceDayStart + runStart - first
                    if (seconds >= 0) {
                        val instant = date.atStartOfDayIn(zone) + seconds.seconds
                        if (instant > latest) break
                        if (instant >= earliest) {
                            out += ScheduledDeparture(
                                tripId = tripId,
                                departure = instant,
                                serviceDate = date,
                                stopId = stopId,
                                frequencyRunStartSeconds = runStart,
                            )
                        }
                    }
                    runStart += frequency.headwaySeconds
                    count++
                }
            }
        }
        // A frequency template and adjacent service dates must never double-list
        // the same physical departure.
        return out.distinctBy { Triple(it.tripId, it.serviceDate, it.departure) }
    }

    /** Constructs one occurrence on an explicit service date, preserving >24h times. */
    fun onServiceDate(serviceTime: GtfsServiceTime, serviceDate: LocalDate, zone: TimeZone): Instant =
        serviceDate.atStartOfDayIn(zone) + serviceTime.secondsSinceServiceDayStart.toLong().seconds

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
    fun metersBetween(a: GeoPoint, b: GeoPoint): Double = GeoMath.distanceMeters(a, b)
}