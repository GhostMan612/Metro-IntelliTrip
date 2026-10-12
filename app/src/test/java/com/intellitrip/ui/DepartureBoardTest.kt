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
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The departure board is the first surface a rider reads, and the recovery plan is
 * blunt that a fabricated ETA is the least acceptable failure it can have.
 *
 * These tests hold that line: times come from the schedule, service days past
 * midnight are not wrapped into the wrong evening, and green "Live" appears only when
 * a vehicle is actually reporting.
 */
class DepartureBoardTest {

    private val zone: ZoneId = ZoneId.of("America/Chicago")
    private val feedId = FeedId("test")
    private val now: Instant = ZonedDateTime.of(2026, 10, 5, 7, 0, 0, 0, zone).toInstant()

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
            mapOf("S1" to listOf(DepartureBoard.ScheduledDeparture("T1", now.plusSeconds(240)))),
        )
        val departure = result.single()
        assertFalse("no vehicle is reporting, so this must not be Live", departure.isLive)
        assertEquals("4 min", departure.wait)
        assertEquals("Westbound", departure.headsign)
        assertEquals("A", departure.routeShortName)
    }

    @Test
    fun aReportingTripIsLiveAndUsesItsReportedTime() {
        val stop = stop("S1", "First", 44.97, -93.26)
        val scheduled = now.plusSeconds(600)
        val reported = now.plusSeconds(60)
        val result = board(
            listOf(stop),
            mapOf("S1" to listOf(DepartureBoard.ScheduledDeparture("T1", scheduled, reportedTime = reported))),
            liveTrips = setOf("T1"),
        )
        val departure = result.single()
        assertTrue(departure.isLive)
        assertEquals(1, departure.minutesUntil)
        assertEquals(reported, departure.scheduledTime)
    }

    @Test
    fun aDepartedServiceIsNotOffered() {
        val stop = stop("S1", "First", 44.97, -93.26)
        val result = board(
            listOf(stop),
            mapOf("S1" to listOf(DepartureBoard.ScheduledDeparture("T1", now.minusSeconds(600)))),
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
                    DepartureBoard.ScheduledDeparture("T1", now.plusSeconds(1800)),
                    DepartureBoard.ScheduledDeparture("T1", now.plusSeconds(300)),
                    DepartureBoard.ScheduledDeparture("T1", now.plusSeconds(900)),
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
            departuresFor = { listOf(DepartureBoard.ScheduledDeparture("T1", now.plusSeconds(300))) },
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
            late.isAfter(now.plusSeconds(60)),
        )
        val hour = late.atZone(zone).hour
        assertEquals(1, hour)
        assertEquals(6, late.atZone(zone).dayOfMonth)
    }

    @Test
    fun serviceDayStartingOnThePreviousDateResolvesForward() {
        // A 03:00 service on a feed whose service day began "yesterday" is still a
        // trip the rider can board.
        val early = ZonedDateTime.of(2026, 10, 5, 2, 0, 0, 0, zone).toInstant()
        val resolved = DepartureBoard.resolveServiceTime(GtfsServiceTime.parse("03:00:00"), early, zone)
        assertTrue(resolved.isAfter(early))
        assertEquals(3, resolved.atZone(zone).hour)
    }

    @Test
    fun accessibilityLabelCarriesRouteDirectionAndProvenance() {
        val stop = stop("S1", "First & Main", 44.97, -93.26)
        val live = board(
            listOf(stop),
            mapOf("S1" to listOf(DepartureBoard.ScheduledDeparture("T1", now.plusSeconds(240)))),
            liveTrips = setOf("T1"),
        ).single()
        val spoken = live.accessibilityLabel()
        assertTrue(spoken, spoken.contains("Route A"))
        assertTrue(spoken, spoken.contains("Westbound"))
        assertTrue(spoken, spoken.contains("First & Main"))
        assertTrue(spoken, spoken.contains("live"))

        val scheduled = board(
            listOf(stop),
            mapOf("S1" to listOf(DepartureBoard.ScheduledDeparture("T1", now.plusSeconds(240)))),
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
    fun dueIsUsedForASubMinuteWait() {
        val stop = stop("S1", "First", 44.97, -93.26)
        val result = board(
            listOf(stop),
            mapOf("S1" to listOf(DepartureBoard.ScheduledDeparture("T1", now.plusSeconds(20)))),
        )
        assertEquals("Due", result.single().wait)
    }
}