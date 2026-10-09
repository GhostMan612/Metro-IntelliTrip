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
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AtlasEngineTest {

    private val feedId = FeedId("metro-transit-regional")

    private fun network(): RoutingNetwork {
        val zone = ZoneId.of("America/Chicago")
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
            metadata = com.intellitrip.domain.FeedMetadata(feedId, null, null, null, "1", "hash", Instant.EPOCH, null, null, null),
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
                    departAt = Instant.now(),
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
                    departAt = LocalDate.of(2026, 10, 5).atTime(7, 0).toInstant(java.time.ZoneOffset.ofHours(-5)),
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
                snapshotAt = Instant.EPOCH,
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
    fun offlineCapabilitiesAreAbsentWithoutRegistry() {
        val engine = AtlasEngine(feedId)
        assertEquals(null, engine.offlineCapabilities())
    }

    @Test
    fun offlineCapabilitiesAreReportedWithRegistry() {
        val engine = AtlasEngine(
            feedId,
            AtlasProviders(),
            com.intellitrip.offline.OfflineRegistry(
                com.intellitrip.gtfs.StaticFeedSnapshotStore(kotlin.io.path.createTempDirectory("engine"))
            ),
        )
        val capabilities = engine.offlineCapabilities()
        assertNotNull(capabilities)
        assertTrue(!capabilities.staticNetworkUsable)
    }
}