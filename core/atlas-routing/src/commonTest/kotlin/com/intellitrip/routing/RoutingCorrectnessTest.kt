package com.intellitrip.routing

import com.intellitrip.contracts.ProviderResult
import com.intellitrip.domain.Frequency
import com.intellitrip.domain.GeoPoint
import com.intellitrip.domain.GtfsServiceTime
import com.intellitrip.domain.JourneyOption
import com.intellitrip.domain.Leg
import com.intellitrip.domain.Route
import com.intellitrip.domain.RouteKey
import com.intellitrip.domain.Stop
import com.intellitrip.domain.StopKey
import com.intellitrip.domain.StopTime
import com.intellitrip.domain.Transfer
import com.intellitrip.domain.TransferType
import com.intellitrip.domain.Trip
import com.intellitrip.domain.TripKey
import com.intellitrip.domain.TripPlanRequest
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Adversarial routing fixtures.
 *
 * The original fixtures put three stops within a 2 km walk of both endpoints,
 * so a "transfer" test could pass even when the planner only considered
 * destination-area alighting. These fixtures separate the transfer station
 * from both endpoints, and check that the access walk and the boarding stop
 * are the same place.
 */
class RoutingCorrectnessTest {

    private val feedId = RoutingFixtures.feedId
    private val zone = RoutingFixtures.zone
    private val serviceDate = RoutingFixtures.serviceDate
    private val routes = listOf(RoutingFixtures.routeA, RoutingFixtures.routeB, RoutingFixtures.routeC)

    private fun stop(id: String, lat: Double, lon: Double) = Stop(
        key = StopKey(feedId, id),
        code = null,
        name = id,
        description = null,
        location = GeoPoint(lat, lon),
        url = null,
        locationType = 0,
        parentStation = null,
        zoneId = null,
    )

    private fun trip(id: String, route: Route = RoutingFixtures.routeA) = Trip(
        key = TripKey(feedId, id),
        routeKey = route.key,
        serviceKey = RoutingFixtures.tripA1.serviceKey,
        headsign = null,
        directionId = 0,
        blockId = null,
        shapeKey = null,
        wheelchairAccessible = null,
    )

    private fun row(
        trip: Trip,
        stopId: String,
        sequence: Int,
        time: String,
        pickupType: Int? = null,
        dropOffType: Int? = null,
    ) = StopTime(
        tripKey = trip.key,
        stopKey = StopKey(feedId, stopId),
        stopSequence = sequence,
        arrivalTime = GtfsServiceTime.parse(time),
        departureTime = GtfsServiceTime.parse(time),
        pickupType = pickupType,
        dropOffType = dropOffType,
        timepoint = null,
        shapeDistTraveled = null,
    )

    private fun network(
        stops: List<Stop>,
        trips: List<Trip>,
        times: List<StopTime>,
        transfers: List<Transfer> = emptyList(),
        frequencies: List<Frequency> = emptyList(),
    ) = RoutingNetwork(
        feedId = feedId,
        metadata = RoutingFixtures.network.metadata,
        routes = routes,
        stops = stops,
        trips = trips,
        stopTimes = times,
        transfers = transfers,
        frequencies = frequencies,
    )

    private fun request(
        origin: GeoPoint,
        destination: GeoPoint,
        maxWalkMeters: Double = 300.0,
        maxTransfers: Int = 1,
        departAt: LocalDateTime = LocalDateTime(serviceDate.year, serviceDate.monthNumber, serviceDate.dayOfMonth, 7, 0),
    ) = TripPlanRequest(
        origin = origin,
        destination = destination,
        departAt = departAt.toInstant(zone),
        arriveBy = null,
        maxTransfers = maxTransfers,
        maxWalkMeters = maxWalkMeters,
    )

    private fun plan(net: RoutingNetwork, request: TripPlanRequest): List<JourneyOption> {
        val result = runBlocking { StaticNetworkPlanner(net, zone).plan(request) }
        assertIs<ProviderResult.Success<List<JourneyOption>>>(result)
        return result.data
    }

    @Test
    fun journeyIsFoundWhenFirstLegOnlyReachesARemoteTransferStation() {
        // A -> B is the only way out of the origin; B is 8 km from both the
        // origin and the destination. Requiring the first leg to alight near
        // the destination makes this journey undiscoverable.
        val a = stop("A", 44.980, -93.260)
        val b = stop("B", 44.980, -93.150)
        val c = stop("C", 44.995, -93.260)
        val t1 = trip("T1")
        val t2 = trip("T2", RoutingFixtures.routeB)
        val net = network(
            stops = listOf(a, b, c),
            trips = listOf(t1, t2),
            times = listOf(
                row(t1, "A", 1, "08:00:00"),
                row(t1, "B", 2, "08:20:00"),
                row(t2, "B", 1, "08:25:00"),
                row(t2, "C", 2, "08:45:00"),
            ),
        )

        val journeys = plan(net, request(a.location, c.location))

        assertTrue(journeys.isNotEmpty(), "transfer-only journey must be found")
        val journey = journeys.first()
        assertEquals(1, journey.transferCount)
        val transit = journey.legs.filterIsInstance<Leg.Transit>()
        assertEquals(2, transit.size)
        assertEquals(StopKey(feedId, "B"), transit.first().toStopKey)
        assertEquals(StopKey(feedId, "B"), transit[1].fromStopKey)
        assertEquals(StopKey(feedId, "C"), transit[1].toStopKey)
    }

    @Test
    fun boardingStopMatchesTheAccessWalk() {
        // O2 and O3 are further than the walk limit from the origin. The old
        // expansion boarded there while still computing the walk to O1, which
        // tells the rider to board at a stop they were never routed to.
        val o1 = stop("O1", 44.980, -93.260)
        val o2 = stop("O2", 44.990, -93.260)
        val o3 = stop("O3", 45.000, -93.260)
        val d = stop("D", 45.010, -93.260)
        val t1 = trip("T1")
        val net = network(
            stops = listOf(o1, o2, o3, d),
            trips = listOf(t1),
            times = listOf(
                row(t1, "O1", 1, "08:00:00"),
                row(t1, "O2", 2, "08:10:00"),
                row(t1, "O3", 3, "08:20:00"),
                row(t1, "D", 4, "08:30:00"),
            ),
        )

        val journeys = plan(net, request(o1.location, d.location))

        assertTrue(journeys.isNotEmpty())
        for (journey in journeys) {
            val walkOut = journey.legs.first() as Leg.WalkLeg
            val firstTransit = journey.legs.filterIsInstance<Leg.Transit>().first()
            assertEquals(
                StopKey(feedId, "O1"),
                firstTransit.fromStopKey,
                "journey ${journey.id} boards somewhere the access walk does not reach",
            )
            assertEquals(firstTransit.departure, walkOut.end)
        }
    }

    @Test
    fun pickupProhibitedStopIsNeverOfferedForBoarding() {
        // T1 leaves from the origin stop itself but prohibits pickup (as an
        // express service would). Only T2, one block away, may be boarded.
        val o1 = stop("O1", 44.980, -93.260)
        val o2 = stop("O2", 44.9805, -93.260)
        val d = stop("D", 45.000, -93.260)
        val express = trip("EXPRESS")
        val local = trip("LOCAL", RoutingFixtures.routeB)
        val net = network(
            stops = listOf(o1, o2, d),
            trips = listOf(express, local),
            times = listOf(
                row(express, "O1", 1, "08:00:00", pickupType = 1),
                row(express, "D", 2, "08:20:00"),
                row(local, "O2", 1, "08:05:00"),
                row(local, "D", 2, "08:25:00"),
            ),
        )

        val journeys = plan(net, request(o1.location, d.location))

        assertTrue(journeys.isNotEmpty(), "the legal local trip must still be planned")
        for (journey in journeys) {
            val firstTransit = journey.legs.filterIsInstance<Leg.Transit>().first()
            assertEquals(StopKey(feedId, "O2"), firstTransit.fromStopKey)
        }
    }

    @Test
    fun dropOffProhibitedStopIsNotUsedAsAlightingPoint() {
        // Both stops are inside the destination walk area, but T2 may not set
        // anyone down at D1 (terminal/flag-stop pattern).
        val o = stop("O", 44.980, -93.260)
        val d1 = stop("D1", 45.000, -93.260)
        val d2 = stop("D2", 45.001, -93.260)
        val t1 = trip("T1")
        val t2 = trip("T2", RoutingFixtures.routeB)
        val net = network(
            stops = listOf(o, d1, d2),
            trips = listOf(t1, t2),
            times = listOf(
                row(t1, "O", 1, "08:00:00"),
                row(t1, "D1", 2, "08:20:00", dropOffType = 1),
                row(t1, "D2", 3, "08:22:00"),
                row(t2, "O", 1, "08:05:00"),
                row(t2, "D1", 2, "08:25:00", dropOffType = 1),
                row(t2, "D2", 3, "08:27:00"),
            ),
        )

        val journeys = plan(net, request(o.location, d1.location))

        assertTrue(journeys.isNotEmpty())
        for (journey in journeys) {
            val finalTransit = journey.legs.filterIsInstance<Leg.Transit>().last()
            assertFalse(
                finalTransit.toStopKey == StopKey(feedId, "D1"),
                "journey ${journey.id} alights where drop-off is prohibited",
            )
            assertEquals(StopKey(feedId, "D2"), finalTransit.toStopKey)
        }
    }

    @Test
    fun frequencyRunsOfTheSameTripHaveDistinctJourneyIds() {
        val a = stop("A", 44.980, -93.260)
        val d = stop("D", 45.000, -93.260)
        val t1 = trip("T1")
        val net = network(
            stops = listOf(a, d),
            trips = listOf(t1),
            times = listOf(
                row(t1, "A", 1, "07:55:00"),
                row(t1, "D", 2, "08:20:00"),
            ),
            frequencies = listOf(
                Frequency(
                    tripKey = t1.key,
                    startTime = GtfsServiceTime.parse("08:00:00"),
                    endTime = GtfsServiceTime.parse("09:00:00"),
                    headwaySeconds = 600,
                    exactTimes = 1,
                ),
            ),
        )

        val journeys = plan(
            net,
            request(
                a.location,
                d.location,
                maxTransfers = 0,
                departAt = LocalDateTime(serviceDate.year, serviceDate.monthNumber, serviceDate.dayOfMonth, 7, 50),
            ),
        )

        assertTrue(journeys.size >= 2, "expected several frequency departures")
        assertEquals(
            journeys.size,
            journeys.map { it.id }.distinct().size,
            "frequency runs shared an id: ${journeys.map { it.id }}",
        )
        val departures = journeys
            .map { it.legs.filterIsInstance<Leg.Transit>().single().departure }
        assertEquals(departures.size, departures.distinct().size)
        assertTrue(journeys.all { it.id.endsWith("direct") })
    }

    @Test
    fun transferJourneyIdRecordsTheTransferStop() {
        val a = stop("A", 44.980, -93.260)
        val b = stop("B", 44.980, -93.150)
        val c = stop("C", 44.995, -93.260)
        val t1 = trip("T1")
        val t2 = trip("T2", RoutingFixtures.routeB)
        val net = network(
            stops = listOf(a, b, c),
            trips = listOf(t1, t2),
            times = listOf(
                row(t1, "A", 1, "08:00:00"),
                row(t1, "B", 2, "08:20:00"),
                row(t2, "B", 1, "08:25:00"),
                row(t2, "C", 2, "08:45:00"),
            ),
        )

        val journey = plan(net, request(a.location, c.location)).firstOrNull { it.transferCount == 1 }
        assertNotNull(journey)
        assertTrue(journey.id.endsWith("transfer"))
        assertTrue(journey.id.contains("via:B"), "hub stop must be part of the identity: ${journey.id}")
    }

    @Test
    fun explicitTransferPairStillLinksDistantPlatforms() {
        // The first leg alights at S2, which this trip alone serves; only the
        // transfers.txt pair to S4 makes the onward trip reachable.
        val s2 = stop("S2", 44.9860, -93.2650)
        val s4 = stop("S4", 44.9870, -93.2650)
        val s1 = stop("S1", 44.9778, -93.2650)
        val s3 = stop("S3", 44.9950, -93.2650)
        val t1 = trip("T1")
        val t2 = trip("T2", RoutingFixtures.routeB)
        val net = network(
            stops = listOf(s1, s2, s4, s3),
            trips = listOf(t1, t2),
            times = listOf(
                row(t1, "S1", 1, "08:00:00"),
                row(t1, "S2", 2, "08:10:00"),
                row(t2, "S4", 1, "08:12:00"),
                row(t2, "S3", 2, "08:25:00"),
            ),
            transfers = listOf(
                Transfer(
                    fromStopKey = StopKey(feedId, "S2"),
                    toStopKey = StopKey(feedId, "S4"),
                    type = TransferType.TRANSFER,
                    minTransferSeconds = 0,
                ),
            ),
        )

        val journeys = plan(net, request(s1.location, s3.location, maxWalkMeters = 400.0))

        val journey = journeys.firstOrNull { it.transferCount == 1 }
        assertNotNull(journey, "transfers.txt pair must still be followed, got ${journeys.map { it.id }}")
        assertEquals(StopKey(feedId, "S4"), journey.legs.filterIsInstance<Leg.Transit>()[1].fromStopKey)
    }

    @Test
    fun routeKeyIsNotUsedAsJourneyIdentity() {
        // Two different routes, same trip id string is impossible in GTFS, but
        // two frequency runs of one trip used to collide. This guards the
        // composite identity against a regression to trip-id-only.
        val a = stop("A", 44.980, -93.260)
        val d = stop("D", 45.000, -93.260)
        val t1 = trip("T1", RoutingFixtures.routeA)
        val t2 = trip("T1-DUP", RoutingFixtures.routeC)
        val net = network(
            stops = listOf(a, d),
            trips = listOf(t1, t2),
            times = listOf(
                row(t1, "A", 1, "08:00:00"),
                row(t1, "D", 2, "08:20:00"),
                row(t2, "A", 1, "08:10:00"),
                row(t2, "D", 2, "08:30:00"),
            ),
        )

        val journeys = plan(net, request(a.location, d.location, maxTransfers = 0))
        assertEquals(2, journeys.size)
        assertEquals(2, journeys.map { it.id }.distinct().size)
    }
}
