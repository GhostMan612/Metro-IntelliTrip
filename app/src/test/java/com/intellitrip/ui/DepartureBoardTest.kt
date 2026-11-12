package com.intellitrip.ui

import com.intellitrip.domain.GeoPoint
import com.intellitrip.domain.GtfsServiceTime
import com.intellitrip.domain.Route
import com.intellitrip.domain.RouteKey
import com.intellitrip.domain.FeedId
import com.intellitrip.domain.Stop
import com.intellitrip.domain.StopKey
import com.intellitrip.domain.Trip
import com.intellitrip.domain.TripKey
import com.intellitrip.domain.ServiceKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

/**
 * The departure board is the first surface a rider reads, and the recovery plan is
 * blunt that a fabricated ETA is the least acceptable failure it can have.
 *
 * These tests hold that line: times come from the schedule, service days past
 * midnight are not wrapped into the wrong evening, and green "Live" appears only when
 * a vehicle is actually reporting.
 */
class DepartureBoardTest {

    private val zone: TimeZone = TimeZone.of("America/Chicago")
    private val feedId = FeedId("test")
    private val now: Instant = LocalDateTime(2026, 10, 5, 7, 0, 0).toInstant(zone)

    private fun stop(id: String, name: String, lat: Double, lon: Double) = Stop(
        key = StopKey(feedId, id),
        code = null,
        name = name,
        description = null,
        location = GeoPoint(lat, lon),
        url = null,
        locationType = null,
        parentStation = null,
        zoneId = null,
    )

    private fun route(id: String, short: String, color: String?) = Route(
        key = RouteKey(feedId, id),
        agencyId = null,
        shortName = short,
        longName = "Route $short",
        description = null,
        routeType = 0,
        url = null,
        color = color,
        textColor = null,
        sortOrder = null,
    )

    private fun trip(id: String, routeId: String, headsign: String) = Trip(
        key = TripKey(feedId, id),
        routeKey = RouteKey(feedId, routeId),
        serviceKey = ServiceKey(feedId, "SVC"),
        headsign = headsign,
        directionId = null,
        blockId = null,
        shapeKey = null,
        wheelchairAccessible = null,
    )

    private fun board(
        stops: List<Stop>,
        departures: Map<String, List<DepartureBoard.ScheduledDeparture>>,
        liveTrips: Set<String> = emptySet(),
        origin: GeoPoint? = stops.firstOrNull()?.location,
    ) = DepartureBoard.build(
        now = now,
        origin = origin,
        stops = stops,
        radiusMeters = 2_000.0,
        departuresFor = { departures[it.key.stopId] ?: emptyList() },
        liveTrips = liveTrips,
        routesByKey = listOf(route("R1", "A", "0055AA")).associate { it.key.routeId to it },
        tripsByKey = listOf(trip("T1", "R1", "Westbound")).associate { it.key.tripId to it },
        zone = zone,
    )

    @Test
    fun scheduleOnlyDepartureIsNotMarkedLive() {
        val stop = stop("S1", "First", 44.97, -93.26)
        val result = board(
            listOf(stop),
            mapOf("S1" to listOf(DepartureBoard.ScheduledDeparture("T1", now + 240.seconds))),
        )
        val departure = result.single()
        assertFalse("no vehicle is reporting, so this must not be Live", departure.isLive)
        assertEquals("4 min", departure.wait)
        assertEquals("Westbound", departure.headsign)
        assertEquals("A", departure.routeShortName)
    }

    @Test
    fun aStopPredictionIsLiveAndUsesItsReportedTime() {
        val stop = stop("S1", "First", 44.97, -93.26)
        val scheduled = now + 600.seconds
        val reported = now + 60.seconds
        val result = board(
            listOf(stop),
            mapOf(
                "S1" to listOf(
                    DepartureBoard.ScheduledDeparture(
                        "T1",
                        scheduled,
                        reportedTime = reported,
                        reportedAt = now - 10.seconds,
                    ),
                ),
            ),
            liveTrips = setOf("T1"),
        )
        val departure = result.single()
        assertTrue(departure.isLive)
        assertEquals(DepartureStatus.LIVE, departure.status)
        assertEquals(1, departure.minutesUntil)
        assertEquals(reported, departure.scheduledTime)
        assertEquals(10L, departure.predictionAgeSeconds)
    }

    @Test
    fun vehicleOnlyReportMustNotMarkAScheduledDepartureLive() {
        val stop = stop("S1", "First", 44.97, -93.26)
        val scheduled = now + 240.seconds
        val departure = board(
            listOf(stop),
            mapOf("S1" to listOf(DepartureBoard.ScheduledDeparture("T1", scheduled))),
            liveTrips = setOf("T1"),
        ).single()
        assertFalse("a vehicle location is not a stop ETA", departure.isLive)
        assertEquals(DepartureStatus.SCHEDULED, departure.status)
        assertTrue(departure.vehicleReporting)
        assertEquals(scheduled, departure.scheduledTime)
        assertTrue(departure.accessibilityLabel().contains("scheduled"))
        assertTrue(departure.accessibilityLabel().contains("vehicle reporting"))
    }

    @Test
    fun canceledAndSkippedPredictionsAreExplicitStates() {
        val stop = stop("S1", "First", 44.97, -93.26)
        val canceled = board(
            listOf(stop),
            mapOf(
                "S1" to listOf(
                    DepartureBoard.ScheduledDeparture(
                        "T1",
                        now + 300.seconds,
                        status = DepartureStatus.CANCELED,
                        reportedAt = now - 5.seconds,
                    ),
                ),
            ),
        ).single()
        assertEquals(DepartureStatus.CANCELED, canceled.status)
        assertFalse(canceled.isLive)
        assertTrue(canceled.accessibilityLabel().contains("canceled"))

        val skipped = board(
            listOf(stop),
            mapOf(
                "S1" to listOf(
                    DepartureBoard.ScheduledDeparture(
                        "T1",
                        now + 300.seconds,
                        status = DepartureStatus.SKIPPED,
                        reportedAt = now - 5.seconds,
                    ),
                ),
            ),
        ).single()
        assertEquals(DepartureStatus.SKIPPED, skipped.status)
        assertFalse(skipped.isLive)
    }

    @Test
    fun aDepartedServiceIsNotOffered() {
        val stop = stop("S1", "First", 44.97, -93.26)
        val result = board(
            listOf(stop),
            mapOf("S1" to listOf(DepartureBoard.ScheduledDeparture("T1", now - 600.seconds))),
        )
        assertTrue("a service that left ten minutes ago is not boardable", result.isEmpty())
    }

    @Test
    fun resultsAreOrderedByDepartureTime() {
        val stop = stop("S1", "First", 44.97, -93.26)
        val result = board(
            listOf(stop),
            mapOf(
                "S1" to listOf(
                    DepartureBoard.ScheduledDeparture("T1", now + 1800.seconds),
                    DepartureBoard.ScheduledDeparture("T1", now + 300.seconds),
                    DepartureBoard.ScheduledDeparture("T1", now + 900.seconds),
                ),
            ),
        )
        assertEquals(listOf(5, 15, 30), result.map { it.minutesUntil })
    }

    @Test
    fun stopsOutsideTheRadiusAreExcluded() {
        val near = stop("S1", "Near", 44.970, -93.260)
        val far = stop("S2", "Far", 45.500, -93.900)
        val result = DepartureBoard.build(
            now = now,
            origin = near.location,
            stops = listOf(near, far),
            radiusMeters = 500.0,
            departuresFor = { listOf(DepartureBoard.ScheduledDeparture("T1", now + 300.seconds)) },
            liveTrips = emptySet(),
            routesByKey = emptyMap(),
            tripsByKey = emptyMap(),
            zone = zone,
        )
        assertTrue(result.all { it.stopName == "Near" })
    }

    @Test
    fun serviceDayPastMidnightIsNotWrappedIntoTheSameEvening() {
        // 25:30 is a valid GTFS time and belongs to the following morning. Wrapping it
        // into 01:30 tonight would put a departure eight hours before the rider is
        // standing there, which is the exact class of error the plan forbids.
        val late = DepartureBoard.resolveServiceTime(GtfsServiceTime.parse("25:30:00"), now, zone)
        assertTrue(
            "expected the next day's 01:30, got $late",
            late > now + 60.seconds,
        )
        val hour = late.toLocalDateTime(zone).hour
        assertEquals(1, hour)
        assertEquals(6, late.toLocalDateTime(zone).dayOfMonth)
    }

    @Test
    fun serviceDayStartingOnThePreviousDateResolvesForward() {
        // A 03:00 service on a feed whose service day began "yesterday" is still a
        // trip the rider can board.
        val early = LocalDateTime(2026, 10, 5, 2, 0, 0).toInstant(zone)
        val resolved = DepartureBoard.resolveServiceTime(GtfsServiceTime.parse("03:00:00"), early, zone)
        assertTrue(resolved > early)
        assertEquals(3, resolved.toLocalDateTime(zone).hour)
    }

    @Test
    fun accessibilityLabelCarriesRouteDirectionAndProvenance() {
        val stop = stop("S1", "First & Main", 44.97, -93.26)
        val live = board(
            listOf(stop),
            mapOf(
                "S1" to listOf(
                    DepartureBoard.ScheduledDeparture(
                        "T1",
                        now + 240.seconds,
                        reportedTime = now + 120.seconds,
                        reportedAt = now - 5.seconds,
                    ),
                ),
            ),
            liveTrips = setOf("T1"),
        ).single()
        val spoken = live.accessibilityLabel()
        assertTrue(spoken, spoken.contains("Route A"))
        assertTrue(spoken, spoken.contains("Westbound"))
        assertTrue(spoken, spoken.contains("First & Main"))
        assertTrue(spoken, spoken.contains("live"))

        val scheduled = board(
            listOf(stop),
            mapOf("S1" to listOf(DepartureBoard.ScheduledDeparture("T1", now + 240.seconds))),
        ).single()
        assertTrue(
            scheduled.accessibilityLabel(),
            scheduled.accessibilityLabel().contains("scheduled"),
        )
    }

    @Test
    fun distanceIsSymmetricAndNonZero() {
        val a = GeoPoint(44.97, -93.26)
        val b = GeoPoint(44.98, -93.26)
        val d = DepartureBoard.metersBetween(a, b)
        assertTrue("expected roughly 1.1 km, got $d", d in 1_000.0..1_300.0)
        assertEquals(d, DepartureBoard.metersBetween(b, a), 0.001)
        assertEquals(0.0, DepartureBoard.metersBetween(a, a), 0.001)
    }

    @Test
    fun weekdayServiceIsInactiveOnSaturday() {
        val svc = ServiceKey(feedId, "WEEKDAY")
        val calendars = listOf(
            com.intellitrip.domain.Calendar(
                serviceKey = svc,
                daysOfWeek = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY),
                startDate = LocalDate(2026, 1, 1),
                endDate = LocalDate(2026, 12, 31),
            ),
        )
        // 2026-10-03 is a Saturday, 2026-10-05 is a Monday.
        assertFalse(DepartureBoard.isServiceActive(calendars, emptyList(), svc, LocalDate(2026, 10, 3)))
        assertTrue(DepartureBoard.isServiceActive(calendars, emptyList(), svc, LocalDate(2026, 10, 5)))
        val removed = listOf(
            com.intellitrip.domain.CalendarDate(svc, LocalDate(2026, 10, 5), com.intellitrip.domain.ServiceExceptionType.REMOVED),
        )
        assertFalse(DepartureBoard.isServiceActive(calendars, removed, svc, LocalDate(2026, 10, 5)))
        assertTrue(DepartureBoard.isServiceActive(emptyList(), emptyList(), svc, LocalDate(2026, 10, 3)))
    }

    @Test
    fun liveDepartureCarriesThePredictionTimestampNotTheClock() {
        val stop = stop("S1", "First", 44.97, -93.26)
        val reported = now + 60.seconds
        val providerTimestamp = now - 15.seconds
        val result = board(
            listOf(stop),
            mapOf(
                "S1" to listOf(
                    DepartureBoard.ScheduledDeparture(
                        "T1",
                        now + 600.seconds,
                        reportedTime = reported,
                        reportedAt = providerTimestamp,
                    ),
                ),
            ),
            liveTrips = setOf("T1"),
        ).single()
        assertEquals(providerTimestamp, result.vehicleUpdatedAt)
        assertEquals(15L, result.predictionAgeSeconds)
    }

    @Test
    fun dueIsUsedForASubMinuteWait() {
        val stop = stop("S1", "First", 44.97, -93.26)
        val result = board(
            listOf(stop),
            mapOf("S1" to listOf(DepartureBoard.ScheduledDeparture("T1", now + 20.seconds))),
        )
        assertEquals("Due", result.single().wait)
    }

    @Test
    fun occurrenceKeepsTheServiceDateThatMadeItValid() {
        val monday = LocalDate(2026, 10, 5)
        val saturday = LocalDate(2026, 10, 3)
        val time = GtfsServiceTime.parse("07:30:00")
        val onMonday = DepartureBoard.occurrences(
            tripId = "T1",
            stopId = "S1",
            serviceTime = time,
            activeDates = listOf(monday),
            zone = zone,
            now = now,
        ).single()
        assertEquals(monday, onMonday.serviceDate)
        assertEquals(LocalDateTime(2026, 10, 5, 7, 30).toInstant(zone), onMonday.departure)

        // The same trip is not active on Saturday, so no occurrence may be
        // constructed for it even though a wall-clock resolver could find one.
        assertTrue(
            DepartureBoard.occurrences(
                tripId = "T1",
                stopId = "S1",
                serviceTime = time,
                activeDates = emptyList(),
                zone = zone,
                now = now,
            ).isEmpty(),
        )
        assertTrue(
            DepartureBoard.occurrences(
                tripId = "T1",
                stopId = "S1",
                serviceTime = time,
                activeDates = listOf(saturday),
                zone = zone,
                now = now,
            ).isEmpty(),
        )
    }

    @Test
    fun lateNightTimeKeepsItsOriginatingServiceDate() {
        val sunday = LocalDate(2026, 10, 4)
        val lateNow = LocalDateTime(2026, 10, 4, 23, 59).toInstant(zone)
        val occurrence = DepartureBoard.occurrences(
            tripId = "T1",
            stopId = "S1",
            serviceTime = GtfsServiceTime.parse("25:30:00"),
            activeDates = listOf(sunday),
            zone = zone,
            now = lateNow,
        ).single()
        assertEquals(sunday, occurrence.serviceDate)
        assertEquals(LocalDateTime(2026, 10, 5, 1, 30).toInstant(zone), occurrence.departure)
    }

    @Test
    fun frequencyRunsUseTheirOwnServiceDateAndHeadway() {
        val monday = LocalDate(2026, 10, 5)
        val frequency = com.intellitrip.domain.Frequency(
            tripKey = TripKey(feedId, "T1"),
            startTime = GtfsServiceTime.parse("06:00:00"),
            endTime = GtfsServiceTime.parse("08:00:00"),
            headwaySeconds = 1_800,
            exactTimes = null,
        )
        val occurrences = DepartureBoard.occurrences(
            tripId = "T1",
            stopId = "S1",
            serviceTime = GtfsServiceTime.parse("06:10:00"),
            activeDates = listOf(monday),
            zone = zone,
            now = now,
            frequencies = listOf(frequency),
            firstDepartureSeconds = 6 * 3_600,
        )
        assertEquals(
            listOf(
                LocalDateTime(2026, 10, 5, 7, 10).toInstant(zone),
                LocalDateTime(2026, 10, 5, 7, 40).toInstant(zone),
                LocalDateTime(2026, 10, 5, 8, 10).toInstant(zone),
            ),
            occurrences.map { it.departure },
        )
        assertTrue(occurrences.all { it.serviceDate == monday })
        assertTrue(occurrences.all { it.frequencyRunStartSeconds != null })
    }
}