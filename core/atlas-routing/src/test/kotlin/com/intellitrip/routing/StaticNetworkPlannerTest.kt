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
}

class OpenTripPlannerRoutingProviderTest {

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