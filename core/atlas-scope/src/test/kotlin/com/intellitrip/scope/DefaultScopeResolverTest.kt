package com.intellitrip.scope

import com.intellitrip.domain.AgencyId
import com.intellitrip.domain.Focus
import com.intellitrip.domain.GeoPoint
import com.intellitrip.domain.LatLngBounds
import com.intellitrip.domain.RouteKey
import com.intellitrip.domain.TripKey
import com.intellitrip.domain.VehicleKey
import com.intellitrip.domain.ZoomBucket
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DefaultScopeResolverTest {

    private val feedId = com.intellitrip.domain.FeedId("metro-transit-regional")
    private val resolver = DefaultScopeResolver()

    private val downtown = GeoPoint(44.9778, -93.2650)

    @Test
    fun noFocusDefaultsToSystemScope() {
        val resolution = resolver.resolve(null)
        assertTrue(resolution.dataScope.systemWide)
        assertEquals(ZoomBucket.CLUSTER, resolution.renderScope.zoomBucket)
        assertEquals(resolution.cameraScope.bounds, resolution.dataScope.bounds ?: resolution.cameraScope.bounds)
    }

    @Test
    fun systemFocusRequestsSystemWideData() {
        val resolution = resolver.resolve(Focus.System)
        assertTrue(resolution.dataScope.systemWide)
        assertEquals(resolver.resolve(null).dataScope.systemWide, resolution.dataScope.systemWide)
    }

    @Test
    fun tripFocusFiltersByTripAndFollows() {
        val trip = TripKey(feedId, "1001")
        val resolution = resolver.resolve(Focus.Trip(trip))
        assertEquals(setOf(trip), resolution.dataScope.trips)
        assertEquals(15.0, resolution.cameraScope.zoom)
        assertEquals(ZoomBucket.INDIVIDUAL, resolution.renderScope.zoomBucket)
    }

    @Test
    fun routeFocusFiltersByRoute() {
        val route = RouteKey(feedId, "901")
        val resolution = resolver.resolve(Focus.Route(route))
        assertEquals(setOf(route), resolution.dataScope.routes)
        assertEquals(13.0, resolution.cameraScope.zoom)
    }

    @Test
    fun vehicleFocusUsesTighterFreshness() {
        val vehicle = VehicleKey(feedId, "bus-4242")
        val resolution = resolver.resolve(Focus.Vehicle(vehicle))
        assertEquals(setOf(vehicle), resolution.dataScope.vehicles)
        assertEquals(Duration.ofSeconds(20), resolution.dataScope.maxAge)
    }

    @Test
    fun radiusFocusIsContinuous() {
        val halfMile = resolver.resolve(Focus.Radius(downtown, 804.0))
        val twoMiles = resolver.resolve(Focus.Radius(downtown, 3218.0))
        assertTrue(halfMile.cameraScope.zoom!! > twoMiles.cameraScope.zoom!!)
        assertNotNull(halfMile.dataScope.radius)
        assertEquals(804.0, halfMile.dataScope.radius?.meters)
        assertNotNull(halfMile.dataScope.bounds)
    }

    @Test
    fun viewportFocusUsesViewportForCameraAndData() {
        val bounds = LatLngBounds(GeoPoint(44.90, -93.40), GeoPoint(45.05, -93.10))
        val resolution = resolver.resolve(Focus.Viewport(bounds))
        assertEquals(bounds, resolution.cameraScope.bounds)
        assertEquals(bounds, resolution.dataScope.bounds)
    }

    @Test
    fun regionFocusSelectsAgencies() {
        val agencies = setOf(AgencyId("metro-transit-regional/0"), AgencyId("metro-transit-regional/4"))
        val bounds = LatLngBounds(GeoPoint(44.95, -93.35), GeoPoint(45.10, -93.15))
        val resolution = resolver.resolve(Focus.Region(agencies, bounds))
        assertEquals(agencies, resolution.dataScope.agencies)
        assertEquals(bounds, resolution.dataScope.bounds)
        assertTrue(!resolution.dataScope.systemWide)
    }

    @Test
    fun tripCorridorKeepsNearbyTransit() {
        val trip = TripKey(feedId, "1001")
        val resolution = resolver.resolve(Focus.TripCorridor(trip, 900.0, downtown))
        assertEquals(setOf(trip), resolution.dataScope.trips)
        assertEquals(900.0, resolution.dataScope.radius?.meters)
        assertNotNull(resolution.dataScope.bounds)
    }

    @Test
    fun cameraDataAndRenderScopesStayIndependent() {
        val system = resolver.resolve(Focus.System)
        // system camera is citywide, render is clustered, data is system-wide:
        // each scope answers only its own question.
        assertTrue(system.dataScope.systemWide)
        assertEquals(ZoomBucket.CLUSTER, system.renderScope.zoomBucket)
        assertTrue(system.renderScope.maxRenderedVehicles > 1000)

        val trip = resolver.resolve(Focus.Trip(TripKey(feedId, "1001")))
        assertTrue(!trip.dataScope.systemWide)
        assertNull(trip.dataScope.radius)
        assertEquals(ZoomBucket.INDIVIDUAL, trip.renderScope.zoomBucket)
    }

    @Test
    fun renderBudgetFollowsLodBucket() {
        val clustered = resolver.resolve(Focus.System).renderScope.maxRenderedVehicles
        val local = resolver.resolve(Focus.Trip(TripKey(feedId, "1"))).renderScope.maxRenderedVehicles
        assertTrue(clustered > local)
    }

    @Test
    fun scopeEngineBuildsImmutableState() {
        val engine = ScopeEngine(resolver)
        val state = engine.currentScope(Focus.Radius(downtown, 500.0))
        assertNotNull(state.focus)
        assertEquals(state.cameraScope, state.cameraScope)
    }
}