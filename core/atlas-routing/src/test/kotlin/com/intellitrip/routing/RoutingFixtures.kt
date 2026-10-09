package com.intellitrip.routing

import com.intellitrip.domain.Agency
import com.intellitrip.domain.AgencyId
import com.intellitrip.domain.FeedId
import com.intellitrip.domain.FeedMetadata
import com.intellitrip.domain.GeoPoint
import com.intellitrip.domain.GtfsServiceTime
import com.intellitrip.domain.RealtimeStatus
import com.intellitrip.domain.Route
import com.intellitrip.domain.RouteKey
import com.intellitrip.domain.ServiceKey
import com.intellitrip.domain.Stop
import com.intellitrip.domain.StopKey
import com.intellitrip.domain.StopTime
import com.intellitrip.domain.Trip
import com.intellitrip.domain.TripKey
import com.intellitrip.domain.TripPlanRequest
import com.intellitrip.domain.TripUpdate
import com.intellitrip.domain.StopTimeUpdate
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

internal object RoutingFixtures {

    val feedId = FeedId("metro-transit-regional")
    val zone: ZoneId = ZoneId.of("America/Chicago")
    val serviceDate: LocalDate = LocalDate.of(2026, 10, 5)

    val agency = Agency(
        id = AgencyId("$feedId/1"),
        feedId = feedId,
        name = "Metro Transit",
        url = null,
        timezone = zone,
        lang = null,
        phone = null,
        fareUrl = null,
        email = null,
    )

    val routeA = Route(RouteKey(feedId, "901"), agency.id, "A", "Blue Line", null, 0, null, null, null, null)
    val routeB = Route(RouteKey(feedId, "902"), agency.id, "B", "Green Line", null, 0, null, null, null, null)
    val routeC = Route(RouteKey(feedId, "903"), agency.id, "C", "Red Line", null, 0, null, null, null, null)

    val stops = listOf(
        Stop(StopKey(feedId, "S1"), "S1", "First", null, GeoPoint(44.9778, -93.2650), null, 0, null, null),
        Stop(StopKey(feedId, "S2"), "S2", "Second", null, GeoPoint(44.9860, -93.2650), null, 0, null, null),
        Stop(StopKey(feedId, "S3"), "S3", "Third", null, GeoPoint(44.9950, -93.2650), null, 0, null, null),
    )

    private fun trip(id: String, route: Route, shape: String) = Trip(
        key = TripKey(feedId, id),
        routeKey = route.key,
        serviceKey = ServiceKey(feedId, "SVC"),
        headsign = null,
        directionId = 0,
        blockId = null,
        shapeKey = com.intellitrip.domain.ShapeKey(feedId, shape),
        wheelchairAccessible = null,
    )

    val tripA1 = trip("T1", routeA, "SH1")
    val tripB1 = trip("T2", routeB, "SH2")
    val tripC1 = trip("T3", routeC, "SH3")

    private data class StopTimeSpec(val stop: String, val arrival: String, val departure: String)

    private fun times(tripKey: TripKey, spec: List<StopTimeSpec>) =
        spec.mapIndexed { index, entry ->
            StopTime(
                tripKey = tripKey,
                stopKey = StopKey(feedId, entry.stop),
                stopSequence = index + 1,
                arrivalTime = GtfsServiceTime.parse(entry.arrival),
                departureTime = GtfsServiceTime.parse(entry.departure),
                pickupType = null,
                dropOffType = null,
                timepoint = null,
                shapeDistTraveled = null,
            )
        }

    /** T1 boards at S1 and ends at S2; the journey to S3 requires transferring to T2. */
    val stopTimes = times(tripA1.key, listOf(
        StopTimeSpec("S1", "08:00:00", "08:00:00"),
        StopTimeSpec("S2", "08:10:00", "08:10:00"),
    )) + times(tripB1.key, listOf(
        StopTimeSpec("S2", "08:12:00", "08:12:00"),
        StopTimeSpec("S3", "08:25:00", "08:25:00"),
    )) + times(tripC1.key, listOf(
        StopTimeSpec("S1", "08:02:00", "08:02:00"),
        StopTimeSpec("S3", "08:30:00", "08:30:00"),
    ))

    val network = RoutingNetwork(
        feedId = feedId,
        metadata = FeedMetadata(feedId, null, null, null, "1", "hash", Instant.EPOCH, null, null, null),
        routes = listOf(routeA, routeB, routeC),
        stops = stops,
        trips = listOf(tripA1, tripB1, tripC1),
        stopTimes = stopTimes,
    )

    fun request(departAt: Instant = serviceDate.atTime(7, 0).toInstant(java.time.ZoneOffset.ofHours(-5))): TripPlanRequest =
        TripPlanRequest(
            origin = GeoPoint(44.9778, -93.2650),
            destination = GeoPoint(44.9930, -93.2650),
            departAt = departAt,
            arriveBy = null,
            maxTransfers = 1,
        )
}