package com.intellitrip.routing

import com.intellitrip.contracts.ProviderResult
import com.intellitrip.domain.GeoPoint
import com.intellitrip.domain.Leg
import com.intellitrip.domain.RealtimeStatus
import com.intellitrip.domain.StopKey
import com.intellitrip.domain.StopTimeUpdate
import com.intellitrip.domain.TripKey
import com.intellitrip.domain.TripUpdate
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class StaticNetworkPlannerTest {

    private val planner = StaticNetworkPlanner(RoutingFixtures.network, RoutingFixtures.zone)

    private fun success(): List<com.intellitrip.domain.JourneyOption> = runBlocking {
        val result = planner.plan(RoutingFixtures.request())
        assertIs<ProviderResult.Success<List<com.intellitrip.domain.JourneyOption>>>(result)
        result.data
    }

    @Test
    fun producesMultimodalJourney() {
        val journeys = success()
        assertTrue(journeys.isNotEmpty())
        val journey = journeys.first()
        assertTrue(journey.legs.first() is Leg.WalkLeg)
        assertTrue(journey.legs.any { it is Leg.Transit })
        assertTrue(journey.legs.last() is Leg.WalkLeg)
    }

    @Test
    fun transitLegCarriesIdentityAndSchedule() {
        val journey = success().first { j -> j.legs.any { it is Leg.Transit && it.tripKey == RoutingFixtures.tripA1.key } }
        val transit = journey.legs.filterIsInstance<Leg.Transit>().first { it.tripKey == RoutingFixtures.tripA1.key }
        assertEquals(RoutingFixtures.routeA.key, transit.routeKey)
        assertEquals(StopKey(RoutingFixtures.feedId, "S1"), transit.fromStopKey)
        assertEquals(StopKey(RoutingFixtures.feedId, "S2"), transit.toStopKey)
        assertNotNull(transit.scheduledDeparture)
        assertEquals(Duration.ofMinutes(10), transit.duration)
    }

    @Test
    fun journeyTimesAreConsistent() {
        val journey = success().first()
        assertTrue(journey.departure <= journey.arrival)
        assertEquals(Duration.between(journey.departure, journey.arrival), journey.duration)
    }

    @Test
    fun directJourneyHasNoTransfers() {
        val journey = success().first { it.id.endsWith("direct") }
        assertEquals(0, journey.transferCount)
    }

    @Test
    fun transferJourneyCarriesTransferConnection() {
        val journey = success().firstOrNull { it.id.endsWith("transfer") }
        assertNotNull(journey, "expected a transfer journey")
        assertEquals(1, journey.transferCount)
        val transfer = journey.transfers.single()
        assertEquals(StopKey(RoutingFixtures.feedId, "S2"), transfer.transferStop)
        assertNotNull(transfer.scheduledBuffer)
        // journey itself carries no confidence; the connection does
        assertEquals(0, journey.legs.count { it is Leg.WalkLeg } - 2)
    }

    @Test
    fun transferConnectionLinksArrivingAndDepartingLegs() {
        val journey = success().first { it.transfers.isNotEmpty() }
        val transfer = journey.transfers.single()
        val transitLegs = journey.legs.filterIsInstance<Leg.Transit>()
        assertTrue(transitLegs.contains(transfer.arrivingLeg))
        assertTrue(transitLegs.contains(transfer.departingLeg))
    }

    @Test
    fun realtimeUpdatesAnnotateJourney() {
        val delayed = TripUpdate(
            tripKey = RoutingFixtures.tripA1.key,
            routeKey = RoutingFixtures.routeA.key,
            vehicleKey = null,
            directionId = 0,
            startDate = null,
            startTime = null,
            timestamp = Instant.EPOCH,
            scheduleRelationship = com.intellitrip.domain.ScheduleRelationship.SCHEDULED,
            stopTimeUpdates = listOf(
                StopTimeUpdate(StopKey(RoutingFixtures.feedId, "S1"), null, 600),
            ),
        )
        val result = planner.planWithRealtime(RoutingFixtures.request(), listOf(delayed))
        assertIs<ProviderResult.Success<List<com.intellitrip.domain.JourneyOption>>>(result)
        val transit = result.data
            .flatMap { it.legs }
            .filterIsInstance<Leg.Transit>()
            .first { it.tripKey == RoutingFixtures.tripA1.key }
        assertEquals(600, transit.delaySeconds)
        assertEquals(RealtimeStatus.DELAYED, transit.realtimeStatus)
        // realtime never rewrites the schedule
        assertNotNull(transit.scheduledDeparture)
    }

    @Test
    fun noRealtimeLeavesStatusUnknown() {
        val transit = success().flatMap { it.legs }.filterIsInstance<Leg.Transit>().first()
        assertEquals(RealtimeStatus.NO_DATA, transit.realtimeStatus)
        assertEquals(null, transit.delaySeconds)
    }

    @Test
    fun unreachableDestinationReturnsPartialResult() {
        val request = RoutingFixtures.request().copy(destination = GeoPoint(45.50, -94.00))
        val result = runBlocking { planner.plan(request) }
        assertIs<ProviderResult.PartialResult<List<com.intellitrip.domain.JourneyOption>>>(result)
        assertTrue(result.missing.isNotEmpty())
        assertNotNull(result.freshness)
    }

    @Test
    fun serviceTimesConvertAcrossMidnight() {
        val late = com.intellitrip.domain.GtfsServiceTime.parse("25:30:00")
        val instant = late.instantOn(RoutingFixtures.serviceDate, RoutingFixtures.zone)
        assertNotNull(instant)
        val local = instant.atZone(RoutingFixtures.zone)
        // 25:30 service time lands on 01:30 the following calendar day
        assertEquals(1, local.hour)
        assertEquals(30, local.minute)
        assertEquals(RoutingFixtures.serviceDate.plusDays(1), local.toLocalDate())
    }

    @Test
    fun walkOutEndsAtBoarding() {
        val journey = success().first { it.id.endsWith("direct") }
        val walkOut = journey.legs.first() as Leg.WalkLeg
        val transit = journey.legs.filterIsInstance<Leg.Transit>().first()
        // The origin walk must finish at boarding, not start there: a rider who
        // walks for 20 minutes cannot board the instant the walk begins.
        assertEquals(transit.departure, walkOut.end)
        assertTrue(!walkOut.start.isAfter(transit.departure))
        assertTrue(journey.departure == walkOut.start)
    }

    @Test
    fun walkOutStartsBeforeBoardingWhenWalkingIsNeeded() {
        // Origin is ~110 m south of S1, so the walk takes ~80 s and must start
        // before the bus leaves.
        val req = RoutingFixtures.request().copy(origin = GeoPoint(44.9768, -93.2650))
        val result = kotlinx.coroutines.runBlocking { planner.plan(req) }
        assertIs<ProviderResult.Success<List<com.intellitrip.domain.JourneyOption>>>(result)
        val journey = result.data.first { it.id.endsWith("direct") }
        val walkOut = journey.legs.first() as Leg.WalkLeg
        val transit = journey.legs.filterIsInstance<Leg.Transit>().first()
        assertTrue(walkOut.meters > 0)
        assertEquals(transit.departure, walkOut.end)
        assertTrue(walkOut.start.isBefore(transit.departure))
    }

    @Test
    fun walkingTransferIsCatchableOnlyWithEnoughBuffer() {
        // S4 is ~110 m north of S2. T2 boards at S4, not S2, so the only way
        // to S3 is via transfers.txt S2->S4. Departure at 08:12 is 120 s after
        // arrival at 08:10; walking takes ~80 s, so it is catchable.
        val s4 = com.intellitrip.domain.Stop(
            com.intellitrip.domain.StopKey(RoutingFixtures.feedId, "S4"), "S4", "Fourth", null,
            GeoPoint(44.9870, -93.2650), null, 0, null, null,
        )
        val stops = RoutingFixtures.network.stops + s4
        val times = RoutingFixtures.stopTimes.filter { it.tripKey != RoutingFixtures.tripB1.key } +
            listOf(
                com.intellitrip.domain.StopTime(
                    tripKey = RoutingFixtures.tripB1.key,
                    stopKey = com.intellitrip.domain.StopKey(RoutingFixtures.feedId, "S4"),
                    stopSequence = 1,
                    arrivalTime = com.intellitrip.domain.GtfsServiceTime.parse("08:12:00"),
                    departureTime = com.intellitrip.domain.GtfsServiceTime.parse("08:12:00"),
                    pickupType = null, dropOffType = null, timepoint = null, shapeDistTraveled = null,
                ),
                com.intellitrip.domain.StopTime(
                    tripKey = RoutingFixtures.tripB1.key,
                    stopKey = com.intellitrip.domain.StopKey(RoutingFixtures.feedId, "S3"),
                    stopSequence = 2,
                    arrivalTime = com.intellitrip.domain.GtfsServiceTime.parse("08:25:00"),
                    departureTime = com.intellitrip.domain.GtfsServiceTime.parse("08:25:00"),
                    pickupType = null, dropOffType = null, timepoint = null, shapeDistTraveled = null,
                ),
            )
        val net = RoutingNetwork(
            feedId = RoutingFixtures.feedId,
            metadata = RoutingFixtures.network.metadata,
            routes = RoutingFixtures.network.routes,
            stops = stops,
            trips = listOf(RoutingFixtures.tripA1, RoutingFixtures.tripB1, RoutingFixtures.tripC1),
            stopTimes = times,
            transfers = listOf(
                com.intellitrip.domain.Transfer(
                    fromStopKey = com.intellitrip.domain.StopKey(RoutingFixtures.feedId, "S2"),
                    toStopKey = com.intellitrip.domain.StopKey(RoutingFixtures.feedId, "S4"),
                    type = com.intellitrip.domain.TransferType.TRANSFER,
                    minTransferSeconds = 0,
                ),
            ),
        )
        val p = StaticNetworkPlanner(net, RoutingFixtures.zone)
        val result = kotlinx.coroutines.runBlocking { p.plan(RoutingFixtures.request()) }
        assertIs<ProviderResult.Success<List<com.intellitrip.domain.JourneyOption>>>(result)
        val journey = result.data.firstOrNull { it.transfers.isNotEmpty() }
        assertNotNull(journey, "expected a walking-transfer journey, got ${result.data.map { it.id }}")
        val walking = journey.transfers.single().walkingDuration
        assertNotNull(walking)
        assertTrue(walking.seconds > 0, "walking transfer must carry walking time, got $walking")
    }

    @Test
    fun arriveByFiltersLateJourneys() {
        val req = RoutingFixtures.request().copy(
            arriveBy = RoutingFixtures.serviceDate.atTime(8, 35, 0)
                .toInstant(java.time.ZoneOffset.ofHours(-5)),
        )
        val result = kotlinx.coroutines.runBlocking { planner.plan(req) }
        assertIs<ProviderResult.Success<List<com.intellitrip.domain.JourneyOption>>>(result)
        assertTrue(result.data.isNotEmpty())
        assertTrue(result.data.all { !it.arrival.isAfter(req.arriveBy!!) })
    }

    @Test
    fun arriveByUnachievableReturnsPartialResult() {
        val req = RoutingFixtures.request().copy(
            arriveBy = RoutingFixtures.serviceDate.atTime(7, 30, 0)
                .toInstant(java.time.ZoneOffset.ofHours(-5)),
        )
        val result = kotlinx.coroutines.runBlocking { planner.plan(req) }
        assertIs<ProviderResult.PartialResult<List<com.intellitrip.domain.JourneyOption>>>(result)
        assertTrue(result.missing.contains("arrive-by-unachievable"))
    }

    @Test
    fun twoTransferJourneyIsFoundWhenRequested() {
        // Chain: T1 S1->S2 (08:00-08:10), T2 S2->S4 (08:12-08:20), T-new S4->S3
        // (08:22-08:30). Only reachable with 2 transfers to S3 via S4.
        val s4 = com.intellitrip.domain.Stop(
            com.intellitrip.domain.StopKey(RoutingFixtures.feedId, "S4"), "S4", "Fourth", null,
            GeoPoint(44.9890, -93.2650), null, 0, null, null,
        )
        val tripD = com.intellitrip.domain.Trip(
            key = com.intellitrip.domain.TripKey(RoutingFixtures.feedId, "T4"),
            routeKey = RoutingFixtures.routeA.key,
            serviceKey = RoutingFixtures.tripA1.serviceKey,
            headsign = null, directionId = 0, blockId = null, shapeKey = null, wheelchairAccessible = null,
        )
        val stops = RoutingFixtures.network.stops + s4
        val times = RoutingFixtures.stopTimes.filter { it.tripKey != RoutingFixtures.tripB1.key } +
            listOf(
                com.intellitrip.domain.StopTime(tripKey = RoutingFixtures.tripB1.key, stopKey = com.intellitrip.domain.StopKey(RoutingFixtures.feedId, "S2"), stopSequence = 1, arrivalTime = com.intellitrip.domain.GtfsServiceTime.parse("08:12:00"), departureTime = com.intellitrip.domain.GtfsServiceTime.parse("08:12:00"), pickupType = null, dropOffType = null, timepoint = null, shapeDistTraveled = null),
                com.intellitrip.domain.StopTime(tripKey = RoutingFixtures.tripB1.key, stopKey = com.intellitrip.domain.StopKey(RoutingFixtures.feedId, "S4"), stopSequence = 2, arrivalTime = com.intellitrip.domain.GtfsServiceTime.parse("08:20:00"), departureTime = com.intellitrip.domain.GtfsServiceTime.parse("08:20:00"), pickupType = null, dropOffType = null, timepoint = null, shapeDistTraveled = null),
                com.intellitrip.domain.StopTime(tripKey = tripD.key, stopKey = com.intellitrip.domain.StopKey(RoutingFixtures.feedId, "S4"), stopSequence = 1, arrivalTime = com.intellitrip.domain.GtfsServiceTime.parse("08:22:00"), departureTime = com.intellitrip.domain.GtfsServiceTime.parse("08:22:00"), pickupType = null, dropOffType = null, timepoint = null, shapeDistTraveled = null),
                com.intellitrip.domain.StopTime(tripKey = tripD.key, stopKey = com.intellitrip.domain.StopKey(RoutingFixtures.feedId, "S3"), stopSequence = 2, arrivalTime = com.intellitrip.domain.GtfsServiceTime.parse("08:30:00"), departureTime = com.intellitrip.domain.GtfsServiceTime.parse("08:30:00"), pickupType = null, dropOffType = null, timepoint = null, shapeDistTraveled = null),
            )
        val net = RoutingNetwork(
            feedId = RoutingFixtures.feedId,
            metadata = RoutingFixtures.network.metadata,
            routes = RoutingFixtures.network.routes,
            stops = stops,
            trips = listOf(RoutingFixtures.tripA1, RoutingFixtures.tripB1, tripD),
            stopTimes = times,
        )
        val p = StaticNetworkPlanner(net, RoutingFixtures.zone)
        val req = RoutingFixtures.request().copy(maxTransfers = 2)
        val result = kotlinx.coroutines.runBlocking { p.plan(req) }
        assertIs<ProviderResult.Success<List<com.intellitrip.domain.JourneyOption>>>(result)
        val journey = result.data.firstOrNull { it.transferCount == 2 }
        assertNotNull(journey, "expected 2-transfer journey, got ${result.data.map { it.id + ':' + it.transferCount }}")
        assertEquals(3, journey.legs.filterIsInstance<Leg.Transit>().size)
    }

    @Test
    fun frequencyTripRepeatsAtHeadway() {
        // T3 template departs S1 at 08:02. Frequency window 08:00-09:00 every
        // 10 min means runs at 08:00, 08:10, 08:20 ... Boarding at 08:07 must
        // get the 08:10 run, not miss the 08:02 template run.
        val net = RoutingNetwork(
            feedId = RoutingFixtures.feedId,
            metadata = RoutingFixtures.network.metadata,
            routes = RoutingFixtures.network.routes,
            stops = RoutingFixtures.network.stops,
            trips = listOf(RoutingFixtures.tripA1, RoutingFixtures.tripB1, RoutingFixtures.tripC1),
            stopTimes = RoutingFixtures.stopTimes,
            frequencies = listOf(
                com.intellitrip.domain.Frequency(
                    tripKey = RoutingFixtures.tripC1.key,
                    startTime = com.intellitrip.domain.GtfsServiceTime.parse("08:00:00"),
                    endTime = com.intellitrip.domain.GtfsServiceTime.parse("09:00:00"),
                    headwaySeconds = 600,
                    exactTimes = 1,
                ),
            ),
        )
        val p = StaticNetworkPlanner(net, RoutingFixtures.zone)
        val depart = RoutingFixtures.serviceDate.atTime(8, 7, 0)
            .toInstant(java.time.ZoneOffset.ofHours(-5))
        val req = RoutingFixtures.request(departAt = depart)
        val result = kotlinx.coroutines.runBlocking { p.plan(req) }
        assertIs<ProviderResult.Success<List<com.intellitrip.domain.JourneyOption>>>(result)
        val journey = result.data.first { j ->
            j.legs.filterIsInstance<Leg.Transit>().any { it.tripKey == RoutingFixtures.tripC1.key }
        }
        val transit = journey.legs.filterIsInstance<Leg.Transit>().first { it.tripKey == RoutingFixtures.tripC1.key }
        val dep = transit.departure.atZone(RoutingFixtures.zone)
        assertEquals(8, dep.hour)
        assertEquals(10, dep.minute)
        // Alighting must shift with the same run (28 min duration preserved).
        assertEquals(38, transit.arrival.atZone(RoutingFixtures.zone).minute)
    }

    @Test
    fun calendarFiltersSaturdayService() {
        val weekdaySvc = com.intellitrip.domain.ServiceKey(RoutingFixtures.feedId, "WEEKDAY")
        val weekdayTrip = RoutingFixtures.tripA1.copy(serviceKey = weekdaySvc)
        val net = RoutingNetwork(
            feedId = RoutingFixtures.feedId,
            metadata = RoutingFixtures.network.metadata,
            routes = RoutingFixtures.network.routes,
            stops = RoutingFixtures.network.stops,
            trips = listOf(weekdayTrip, RoutingFixtures.tripB1, RoutingFixtures.tripC1),
            stopTimes = RoutingFixtures.stopTimes,
            calendars = listOf(
                com.intellitrip.domain.Calendar(
                    serviceKey = weekdaySvc,
                    daysOfWeek = setOf(java.time.DayOfWeek.MONDAY, java.time.DayOfWeek.TUESDAY, java.time.DayOfWeek.WEDNESDAY, java.time.DayOfWeek.THURSDAY, java.time.DayOfWeek.FRIDAY),
                    startDate = RoutingFixtures.serviceDate.minusYears(1),
                    endDate = RoutingFixtures.serviceDate.plusYears(1),
                ),
            ),
        )
        val plannerSat = StaticNetworkPlanner(net, RoutingFixtures.zone)
        // 2026-10-03 is a Saturday.
        val saturday = java.time.LocalDate.of(2026, 10, 3)
        val departSat = saturday.atTime(7, 0).atZone(RoutingFixtures.zone).toInstant()
        val result = kotlinx.coroutines.runBlocking {
            plannerSat.plan(RoutingFixtures.request(departAt = departSat))
        }
        assertIs<ProviderResult.Success<List<com.intellitrip.domain.JourneyOption>>>(result)
        assertTrue(result.data.isEmpty(), "weekday-only service must not plan on Saturday, got ${result.data.size}")
    }

    @Test
    fun calendarDateExceptionOverridesWeekday() {
        val weekdaySvc = com.intellitrip.domain.ServiceKey(RoutingFixtures.feedId, "WEEKDAY")
        val net = RoutingNetwork(
            feedId = RoutingFixtures.feedId,
            metadata = RoutingFixtures.network.metadata,
            routes = RoutingFixtures.network.routes,
            stops = RoutingFixtures.network.stops,
            trips = RoutingFixtures.network.trips,
            stopTimes = RoutingFixtures.stopTimes,
            calendars = listOf(
                com.intellitrip.domain.Calendar(
                    serviceKey = weekdaySvc,
                    daysOfWeek = setOf(java.time.DayOfWeek.MONDAY),
                    startDate = RoutingFixtures.serviceDate.minusYears(1),
                    endDate = RoutingFixtures.serviceDate.plusYears(1),
                ),
            ),
            calendarDates = listOf(
                com.intellitrip.domain.CalendarDate(
                    serviceKey = weekdaySvc,
                    date = RoutingFixtures.serviceDate,
                    exceptionType = com.intellitrip.domain.ServiceExceptionType.REMOVED,
                ),
            ),
        )
        assertTrue(!net.isActive(weekdaySvc, RoutingFixtures.serviceDate))
        val addedSvc = com.intellitrip.domain.ServiceKey(RoutingFixtures.feedId, "ADDED")
        val net2 = RoutingNetwork(
            feedId = RoutingFixtures.feedId,
            metadata = RoutingFixtures.network.metadata,
            routes = RoutingFixtures.network.routes,
            stops = RoutingFixtures.network.stops,
            trips = RoutingFixtures.network.trips,
            stopTimes = RoutingFixtures.stopTimes,
            calendarDates = listOf(
                com.intellitrip.domain.CalendarDate(
                    serviceKey = addedSvc,
                    date = RoutingFixtures.serviceDate,
                    exceptionType = com.intellitrip.domain.ServiceExceptionType.ADDED,
                ),
            ),
        )
        assertTrue(net2.isActive(addedSvc, RoutingFixtures.serviceDate))
    }
}

/**
 * Transport-level behavior of the OTP adapter. Response mapping is covered by
 * OpenTripPlannerRoutingProviderTest in its own file.
 */
class OtpTransportTest {

    private val request = RoutingFixtures.request()

    @Test
    fun planUrlEncodesOriginAndDestination() {
        val provider = OpenTripPlannerRoutingProvider(
            baseUrl = "https://otp.example.org",
            feedId = RoutingFixtures.feedId,
            transport = { "" },
        )
        val url = provider.planUrl(request)
        assertTrue(url.startsWith("https://otp.example.org/otp/routers/default/plan"))
        assertTrue(url.contains("from="))
        assertTrue(url.contains("to="))
        assertTrue(url.contains("mode=TRANSIT%2CWALK"))
    }

    @Test
    fun unreachableServiceIsReportedAsNetworkFailure() {
        val provider = OpenTripPlannerRoutingProvider(
            baseUrl = "https://otp.example.org",
            feedId = RoutingFixtures.feedId,
            transport = { throw java.io.IOException("down") },
        )
        val result = kotlinx.coroutines.runBlocking { provider.plan(request) }
        assertIs<ProviderResult.NetworkFailure>(result)
    }

    @Test
    fun providerIdIsNeutral() {
        val provider = OpenTripPlannerRoutingProvider(
            baseUrl = "https://otp.example.org",
            feedId = RoutingFixtures.feedId,
            transport = { "{}" },
        )
        assertEquals("open-trip-planner", provider.providerId)
    }
}