package com.intellitrip.engine

import com.intellitrip.domain.FeedId
import com.intellitrip.domain.Focus
import com.intellitrip.domain.GeoPoint
import com.intellitrip.domain.JourneyOption
import com.intellitrip.domain.Leg
import com.intellitrip.domain.Transfer
import com.intellitrip.domain.TripPlanRequest
import com.intellitrip.contracts.ProviderResult
import com.intellitrip.contracts.RoutingProvider
import com.intellitrip.domain.Route
import com.intellitrip.domain.RouteKey
import com.intellitrip.domain.ServiceKey
import com.intellitrip.domain.Stop
import com.intellitrip.domain.StopKey
import com.intellitrip.domain.StopTime
import com.intellitrip.domain.GtfsServiceTime
import com.intellitrip.domain.Trip
import com.intellitrip.domain.TripKey
import com.intellitrip.domain.TripUpdate
import com.intellitrip.routing.RoutingNetwork
import com.intellitrip.routing.StaticNetworkPlanner
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class AtlasEngineTest {

    private val feedId = FeedId("metro-transit-regional")

    private fun network(): RoutingNetwork {
        val zone = TimeZone.of("America/Chicago")
        val agency = com.intellitrip.domain.Agency(
            id = com.intellitrip.domain.AgencyId("metro-transit-regional/1"),
            feedId = feedId,
            name = "Metro Transit",
            url = null,
            timezone = zone,
            lang = null,
            phone = null,
            fareUrl = null,
            email = null,
        )
        val route = Route(RouteKey(feedId, "901"), agency.id, "A", "Blue Line", null, 0, null, null, null, null)
        val stops = listOf(
            Stop(StopKey(feedId, "S1"), "S1", "First", null, GeoPoint(44.9778, -93.2650), null, 0, null, null),
            Stop(StopKey(feedId, "S2"), "S2", "Second", null, GeoPoint(44.9900, -93.2650), null, 0, null, null),
        )
        val trip = Trip(
            key = TripKey(feedId, "T1"),
            routeKey = route.key,
            serviceKey = ServiceKey(feedId, "SVC"),
            headsign = null,
            directionId = 0,
            blockId = null,
            shapeKey = null,
            wheelchairAccessible = null,
        )
        val times = listOf(
            StopTime(TripKey(feedId, "T1"), StopKey(feedId, "S1"), 1, GtfsServiceTime.parse("08:00:00"), GtfsServiceTime.parse("08:00:00"), null, null, null, null),
            StopTime(TripKey(feedId, "T1"), StopKey(feedId, "S2"), 2, GtfsServiceTime.parse("08:10:00"), GtfsServiceTime.parse("08:10:00"), null, null, null, null),
        )
        return RoutingNetwork(
            feedId = feedId,
            metadata = com.intellitrip.domain.FeedMetadata(feedId, null, null, null, "1", "hash", Instant.DISTANT_PAST, null, null, null),
            routes = listOf(route),
            stops = stops,
            trips = listOf(trip),
            stopTimes = times,
        )
    }

    @Test
    fun engineWorksWithNoProviders() {
        val engine = AtlasEngine(feedId)
        val capabilities = engine.capabilities()
        assertTrue(!capabilities.staticTransit)
        assertTrue(!capabilities.realtimeTransit)
        assertTrue(!capabilities.routing)
    }

    @Test
    fun scopeIsAvailableWithoutAnyProvider() {
        val engine = AtlasEngine(feedId)
        val scope = engine.scope(Focus.Radius(GeoPoint(44.9778, -93.2650), 805.0))
        assertNotNull(scope.dataScope)
        assertTrue(scope.renderScope.maxRenderedVehicles > 0)
    }

    @Test
    fun routingIsUnavailableWithoutProvider() {
        val engine = AtlasEngine(feedId)
        val outcome = kotlinx.coroutines.runBlocking {
            engine.planJourney(
                TripPlanRequest(
                    origin = GeoPoint(44.9778, -93.2650),
                    destination = GeoPoint(44.99, -93.2650),
                    departAt = Clock.System.now(),
                    arriveBy = null,
                    maxTransfers = 0,
                )
            )
        }
        assertIs<JourneyOutcome.Unavailable>(outcome)
    }

    @Test
    fun injectedRoutingProviderIsUsed() {
        val planner = StaticNetworkPlanner(network())
        val engine = AtlasEngine(feedId, AtlasProviders(routing = planner))
        val outcome = kotlinx.coroutines.runBlocking {
            engine.planJourney(
                TripPlanRequest(
                    origin = GeoPoint(44.9778, -93.2650),
                    destination = GeoPoint(44.99, -93.2650),
                    departAt = LocalDateTime(2026, 10, 5, 7, 0).toInstant(TimeZone.of("America/Chicago")),
                    arriveBy = null,
                    maxTransfers = 0,
                )
            )
        }
        assertIs<JourneyOutcome.Planned>(outcome)
        assertTrue(outcome.journeys.isNotEmpty())
        val journey = outcome.journeys.first()
        assertTrue(journey.legs.any { it is Leg.Transit })
    }

    @Test
    fun routingProviderIsReplaceable() {
        val failing = object : RoutingProvider {
            override val providerId = "stub"
            override suspend fun plan(request: TripPlanRequest) =
                ProviderResult.Unavailable(com.intellitrip.contracts.ProviderError("no service"))
        }
        val engine = AtlasEngine(feedId, AtlasProviders(routing = failing))
        val outcome = kotlinx.coroutines.runBlocking {
            engine.planJourney(
                TripPlanRequest(GeoPoint(44.9, -93.2), GeoPoint(44.99, -93.26), null, null, 0)
            )
        }
        assertIs<JourneyOutcome.Unavailable>(outcome)
    }

    @Test
    fun renderIsRendererAgnostic() {
        val engine = AtlasEngine(feedId)
        val snapshot = engine.render(
            com.intellitrip.map.RenderInput(
                camera = com.intellitrip.map.CameraView(
                    bounds = com.intellitrip.domain.LatLngBounds(
                        GeoPoint(44.9, -93.3),
                        GeoPoint(45.0, -93.1),
                    ),
                    zoom = 15.0,
                ),
                vehicles = emptyList(),
                stops = emptyList(),
                shapes = emptyList(),
                snapshotAt = Instant.DISTANT_PAST,
            )
        )
        assertEquals(com.intellitrip.domain.ZoomBucket.INDIVIDUAL, snapshot.zoomBucket)
    }

    @Test
    fun transferEvaluationIsExposed() {
        val engine = AtlasEngine(feedId)
        val reports = engine.evaluateTransfers(emptyList<JourneyOption>(), emptyList<TripUpdate>())
        assertTrue(reports.isEmpty())
    }

    @Test
    fun offlineIsAbsentWithoutFlag() {
        val engine = AtlasEngine(feedId)
        assertEquals(false, engine.capabilities().offline)
    }

    @Test
    fun offlineIsReportedWhenFlagSet() {
        val engine = AtlasEngine(feedId, offlineAvailable = true)
        assertEquals(true, engine.capabilities().offline)
    }

    @Test
    fun partialResultMissingStopsPreservedInUnavailableOutcome() = runTest {
        val planner = StaticNetworkPlanner(network())
        val engine = AtlasEngine(feedId, AtlasProviders(routing = planner))
        val outcome = engine.planJourney(
            TripPlanRequest(
                origin = GeoPoint(45.50, -94.00),
                destination = GeoPoint(44.99, -93.2650),
                departAt = LocalDateTime(2026, 10, 5, 7, 0).toInstant(TimeZone.of("America/Chicago")),
                arriveBy = null,
                maxTransfers = 0,
            )
        )
        val unavailable = assertIs<JourneyOutcome.Unavailable>(outcome)
        assertTrue(unavailable.missing.contains("stops-within-walk-limit"))
        assertTrue(unavailable.missing.contains("no-origin-stops-within-walk-limit"))
        assertTrue(unavailable.reason.contains("stops", ignoreCase = true))
    }

    @Test
    fun partialResultArriveByUnachievablePreservedInUnavailableOutcome() = runTest {
        val planner = StaticNetworkPlanner(network())
        val engine = AtlasEngine(feedId, AtlasProviders(routing = planner))
        val zone = TimeZone.of("America/Chicago")
        val outcome = engine.planJourney(
            TripPlanRequest(
                origin = GeoPoint(44.9778, -93.2650),
                destination = GeoPoint(44.9900, -93.2650),
                departAt = LocalDateTime(2026, 10, 5, 7, 0).toInstant(zone),
                arriveBy = LocalDateTime(2026, 10, 5, 7, 30).toInstant(zone),
                maxTransfers = 0,
            )
        )
        val unavailable = assertIs<JourneyOutcome.Unavailable>(outcome)
        assertTrue(unavailable.missing.contains("arrive-by-unachievable"))
        assertTrue(unavailable.reason.contains("arrives by", ignoreCase = true))
    }

    @Test
    fun timeoutOverloadReturnsUnavailableTimeoutWhileCallerCancellationPropagates() = runTest {
        val slowProvider = object : RoutingProvider {
            override val providerId = "slow"
            override suspend fun plan(request: TripPlanRequest): ProviderResult<List<JourneyOption>> {
                kotlinx.coroutines.delay(5.seconds)
                return ProviderResult.Success(
                    emptyList(),
                    com.intellitrip.contracts.FreshnessMetadata(
                        Instant.DISTANT_PAST, Instant.DISTANT_PAST, kotlin.time.Duration.ZERO, "slow",
                    ),
                )
            }
        }
        val engine = AtlasEngine(feedId, AtlasProviders(routing = slowProvider))
        val request = TripPlanRequest(GeoPoint(44.9778, -93.2650), GeoPoint(44.99, -93.2650), null, null, 0)

        val timedOut = engine.planJourney(request, timeout = 25.milliseconds)
        val unavailable = assertIs<JourneyOutcome.Unavailable>(timedOut)
        assertTrue(unavailable.missing.contains("timeout"))
        assertTrue(unavailable.reason.contains("timed out", ignoreCase = true))

        val started = CompletableDeferred<Unit>()
        val gatedProvider = object : RoutingProvider {
            override val providerId = "gated"
            override suspend fun plan(request: TripPlanRequest): ProviderResult<List<JourneyOption>> {
                started.complete(Unit)
                kotlinx.coroutines.awaitCancellation()
            }
        }
        val gatedEngine = AtlasEngine(feedId, AtlasProviders(routing = gatedProvider))
        var caughtCancellation: CancellationException? = null
        val job = launch {
            try {
                gatedEngine.planJourney(request, timeout = 5.seconds)
            } catch (e: CancellationException) {
                caughtCancellation = e
                throw e
            }
        }
        started.await()
        job.cancel()
        job.join()
        assertNotNull(caughtCancellation)
        assertFailsWith<CancellationException> { throw caughtCancellation!! }
    }

}