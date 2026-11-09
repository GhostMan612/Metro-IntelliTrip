package com.intellitrip.domain

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.test.Test
import kotlin.test.assertEquals

class ScopeInvariantsTest {
    @Test
    fun scopeStateKeepsCameraDataRenderSeparate() {
        val center = GeoPoint(44.9778, -93.2650)
        val bounds = LatLngBounds(GeoPoint(44.8, -93.4), GeoPoint(45.1, -93.1))
        val scope = ScopeState(
            focus = Focus.Radius(center, 500.0),
            cameraScope = CameraScope(bounds, 12.0),
            dataScope = DataScope(agencies = null, routes = null, trips = null, vehicles = null, bounds = bounds, radius = RadiusFilter(center, 500.0), timeWindow = null, maxAge = 30.seconds, includeAlerts = true, systemWide = false),
            renderScope = RenderScope(bounds, ZoomBucket.CLUSTER, 500),
        )
        assertEquals(bounds, scope.cameraScope.bounds)
        assertEquals(30.seconds, scope.dataScope.maxAge)
        assertEquals(ZoomBucket.CLUSTER, scope.renderScope.zoomBucket)
    }
}
