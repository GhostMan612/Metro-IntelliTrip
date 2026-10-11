package com.intellitrip.map

import com.intellitrip.domain.FeedId
import com.intellitrip.domain.GeoPoint
import com.intellitrip.domain.JourneyOption
import com.intellitrip.domain.Leg
import com.intellitrip.domain.RealtimeStatus
import com.intellitrip.domain.RouteKey
import com.intellitrip.domain.ServiceKey
import com.intellitrip.domain.Shape
import com.intellitrip.domain.ShapeKey
import com.intellitrip.domain.Stop
import com.intellitrip.domain.StopKey
import com.intellitrip.domain.Trip
import com.intellitrip.domain.TripKey
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RouteGeometryTest {

    private val feedId = FeedId("test-feed")
    private val departure = Instant.parse("2026-10-05T13:00:00Z")

    private fun trip(shapeKey: ShapeKey?) = Trip(
        key = TripKey(feedId, "T1"),
        routeKey = RouteKey(feedId, "R1"),
        serviceKey = ServiceKey(feedId, "SVC"),
        headsign = null,
        directionId = 0,
        blockId = null,
        shapeKey = shapeKey,
        wheelchairAccessible = null,
    )

    private fun stop(id: String, lat: Double, lon: Double) = Stop(
        key = StopKey(feedId, id),
        code = null,
        name = id,
        description = null,
        location = GeoPoint(lat, lon),
        url = null,
        locationType = null,
        parentStation = null,
        zoneId = null,
    )

    private fun transitLeg(from: String, to: String) = Leg.Transit(
        agencyId = null,
        routeKey = RouteKey(feedId, "R1"),
        tripKey = TripKey(feedId, "T1"),
        serviceDate = null,
        fromStopKey = StopKey(feedId, from),
        toStopKey = StopKey(feedId, to),
        departure = departure,
        arrival = departure.plusSeconds(600),
        vehicleKey = null,
        realtimeStatus = RealtimeStatus.SCHEDULED,
        delaySeconds = null,
        scheduledDeparture = null,
        scheduledArrival = null,
        duration = Duration.ofSeconds(600),
    )

    private fun journey(vararg legs: Leg) = JourneyOption(
        id = "j",
        legs = legs.toList(),
        transfers = emptyList(),
        departure = departure,
        arrival = departure.plusSeconds(600),
    )

    private val shapePoints = listOf(
        GeoPoint(44.97, -93.26),
        GeoPoint(44.975, -93.262),
        GeoPoint(44.98, -93.264),
        GeoPoint(44.985, -93.265),
    )

    @Test
    fun transitLegFollowsShapeCoordinatesInGeoJsonOrder() {
        val lines = RouteGeometry.build(
            journey = journey(transitLeg("S1", "S4")),
            shapes = listOf(Shape(ShapeKey(feedId, "SH1"), shapePoints)),
            stops = listOf(
                stop("S1", 44.97, -93.26),
                stop("S4", 44.985, -93.265),
            ),
            trips = listOf(trip(ShapeKey(feedId, "SH1"))),
        )
        val line = lines.single()
        // The full shape, because the stops sit at its ends.
        assertEquals(shapePoints.size, line.coordinates.size)
        // GeoJSON is [lon, lat]; the domain is latitude-first. Getting this backwards
        // draws the line in the ocean, so assert it explicitly.
        assertEquals(-93.26, line.coordinates.first()[0], 1e-9)
        assertEquals(44.97, line.coordinates.first()[1], 1e-9)
    }

    @Test
    fun transitLegTrimsShapeToTheRiddenPortion() {
        val lines = RouteGeometry.build(
            journey = journey(transitLeg("S2", "S3")),
            shapes = listOf(Shape(ShapeKey(feedId, "SH1"), shapePoints)),
            stops = listOf(
                stop("S1", 44.97, -93.26),
                stop("S2", 44.975, -93.262),
                stop("S3", 44.98, -93.264),
                stop("S4", 44.985, -93.265),
            ),
            trips = listOf(trip(ShapeKey(feedId, "SH1"))),
        )
        val line = lines.single()
        // Stops at indices 1 and 2, so two positions, not the whole four-point shape.
        assertEquals(2, line.coordinates.size)
        assertEquals(44.975, line.coordinates.first()[1], 1e-9)
        assertEquals(44.98, line.coordinates.last()[1], 1e-9)
    }

    @Test
    fun trimsCorrectlyWhenAlightingPrecedesBoarding() {
        // Reverse-direction trips have toStop earlier in the polyline than fromStop.
        val trimmed = RouteGeometry.trimToSpan(
            shape = shapePoints,
            from = GeoPoint(44.98, -93.264),
            to = GeoPoint(44.975, -93.262),
        )
        assertEquals(2, trimmed.size)
        assertEquals(44.975, trimmed.first().lat, 1e-9)
        assertEquals(44.98, trimmed.last().lat, 1e-9)
    }

    @Test
    fun unknownEndpointsFallBackToTheWholeShape() {
        val trimmed = RouteGeometry.trimToSpan(shapePoints, from = null, to = GeoPoint(44.98, -93.264))
        assertEquals(shapePoints.size, trimmed.size)
    }

    @Test
    fun missingShapeFallsBackToAStraightConnector() {
        val lines = RouteGeometry.build(
            journey = journey(transitLeg("S1", "S2")),
            shapes = emptyList(),
            stops = listOf(stop("S1", 44.97, -93.26), stop("S2", 44.98, -93.264)),
            trips = listOf(trip(shapeKey = null)),
        )
        val line = lines.single()
        assertEquals(2, line.coordinates.size)
        assertEquals(44.97, line.coordinates.first()[1], 1e-9)
        assertEquals(44.98, line.coordinates.last()[1], 1e-9)
    }

    @Test
    fun walkLegsBecomeConnectors() {
        val walk = Leg.WalkLeg(
            from = GeoPoint(44.97, -93.26),
            to = GeoPoint(44.971, -93.261),
            start = departure,
            duration = Duration.ofSeconds(120),
            meters = 150.0,
        )
        val lines = RouteGeometry.build(journey(walk), emptyList(), emptyList(), emptyList())
        val line = lines.single()
        assertTrue(line.id.startsWith("walk-"))
        assertEquals(2, line.coordinates.size)
    }

    @Test
    fun zeroLengthLegsAreDroppedRatherThanEmittedAsInvalidGeometry() {
        val same = GeoPoint(44.97, -93.26)
        val walk = Leg.WalkLeg(same, same, departure, Duration.ZERO, 0.0)
        // GeoJSON rejects a LineString whose positions are all identical.
        assertTrue(RouteGeometry.build(journey(walk), emptyList(), emptyList(), emptyList()).isEmpty())
    }

    @Test
    fun unknownStopKeysDropTheLeg() {
        val lines = RouteGeometry.build(
            journey = journey(transitLeg("MISSING", "ALSOMISSING")),
            shapes = emptyList(),
            stops = emptyList(),
            trips = listOf(trip(shapeKey = null)),
        )
        assertNull(lines.firstOrNull())
    }

    @Test
    fun emptyJourneyProducesNoGeometry() {
        assertTrue(RouteGeometry.build(journey(), emptyList(), emptyList(), emptyList()).isEmpty())
    }

    @Test
    fun everyLegOfAMultiLegPlanIsDrawn() {
        val lines = RouteGeometry.build(
            journey = journey(
                Leg.WalkLeg(
                    GeoPoint(44.96, -93.25), GeoPoint(44.97, -93.26),
                    departure, Duration.ofSeconds(300), 400.0,
                ),
                transitLeg("S1", "S4"),
                Leg.WalkLeg(
                    GeoPoint(44.985, -93.265), GeoPoint(44.99, -93.27),
                    departure, Duration.ofSeconds(200), 300.0,
                ),
            ),
            shapes = listOf(Shape(ShapeKey(feedId, "SH1"), shapePoints)),
            stops = listOf(stop("S1", 44.97, -93.26), stop("S4", 44.985, -93.265)),
            trips = listOf(trip(ShapeKey(feedId, "SH1"))),
        )
        assertEquals(3, lines.size)
        // Ids must be distinct so lines cannot overwrite each other in one source.
        assertEquals(3, lines.map { it.id }.toSet().size)
    }
}
