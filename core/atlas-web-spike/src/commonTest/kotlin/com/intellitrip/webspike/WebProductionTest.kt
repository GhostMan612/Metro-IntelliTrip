package com.intellitrip.webspike

import com.intellitrip.domain.AlertSeverity
import com.intellitrip.domain.GeoPoint
import com.intellitrip.domain.RouteKey
import com.intellitrip.domain.ServiceAlert
import com.intellitrip.domain.Vehicle
import com.intellitrip.domain.VehicleKey
import com.intellitrip.domain.VehicleType
import com.intellitrip.gtfs.StaticFeed
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.datetime.Clock

class WebProductionTest {

    private fun feed(): StaticFeed {
        val network = WebDemo.demoNetwork()
        return StaticFeed(
            feedId = network.feedId,
            metadata = network.metadata,
            agencies = network.agencies,
            routes = network.routes,
            stops = network.stops,
            trips = network.trips,
            // RoutingNetwork does not retain either table by property: stop
            // times live in the snapshot index and shapes are optional. The
            // web-shell tests only exercise stop/route/metadata helpers.
            stopTimes = emptyList(),
            calendars = network.calendars,
            calendarDates = network.calendarDates,
            shapes = emptyList(),
            frequencies = network.frequencies,
            transfers = network.transfers,
        )
    }

    @Test
    fun demoIsOptInOnly() {
        assertTrue(WebProduction.demoRequested("?demo=1"))
        assertTrue(WebProduction.demoRequested("?foo=bar&demo=1"))
        assertFalse(WebProduction.demoRequested(""))
        assertFalse(WebProduction.demoRequested("?demo=0"))
        assertFalse(WebProduction.demoRequested("?demo=true"))
        assertFalse(WebProduction.demoRequested("?DEmo=1"))
    }

    @Test
    fun boundsComeFromTheFeedNotAFixedCity() {
        val bounds = assertNotNull(WebProduction.feedBounds(feed()))
        assertEquals(44.9778, bounds.southWest.lat, 0.0001)
        assertEquals(-93.2650, bounds.southWest.lon, 0.0001)
        assertEquals(44.9950, bounds.northEast.lat, 0.0001)
        assertEquals(-93.2500, bounds.northEast.lon, 0.0001)
        val camera = WebProduction.cameraFor(feed())
        assertEquals(bounds, camera.bounds)
    }

    @Test
    fun nearbyStopsAreOrderedByDistanceAndRespectTheLimit() {
        val feed = feed()
        val nearest = WebProduction.nearbyStops(feed, feed.stops.first().location, limit = 2)
        assertEquals(listOf("S1", "S2"), nearest.map { it.key.stopId })
        assertEquals(1, WebProduction.nearbyStops(feed, feed.stops.first().location, limit = 1).size)
        assertTrue(WebProduction.nearbyStops(feed, feed.stops.first().location, limit = 0).isEmpty())
    }

    @Test
    fun stopLookupUsesFeedScopedKeys() {
        val feed = feed()
        assertEquals("Second", WebProduction.stopById(feed, "S2")?.name)
        assertNull(WebProduction.stopById(feed, "does-not-exist"))
    }

    @Test
    fun snapshotRendersStopsAndVehiclesInsideTheFeedExtent() {
        val feed = feed()
        val vehicle = Vehicle(
            key = VehicleKey(feed.feedId, "veh-1"),
            routeKey = feed.routes.single().key,
            tripKey = feed.trips.first().key,
            position = feed.stops.first().location,
            bearing = 0.0,
            speedMps = 10.0,
            updatedAt = Clock.System.now(),
            vehicleType = VehicleType.BUS,
        )
        val snapshot = WebProduction.snapshot(feed, listOf(vehicle), Clock.System.now())
        assertEquals(3, snapshot.stops.size)
        assertEquals(1, snapshot.vehicles.size)
        assertTrue(snapshot.shapeLines.isEmpty())
    }

    @Test
    fun alertLinesNameSeverityAndRouteCount() {
        val feed = feed()
        val alert = ServiceAlert(
            id = "a1",
            headline = "Blue Line detour",
            severity = AlertSeverity.WARNING,
            affectedRoutes = setOf(RouteKey(feed.feedId, "901")),
            affectedStops = emptySet(),
            affectsAgency = null,
        )
        val line = WebProduction.alertLines(listOf(alert)).single()
        assertTrue(line.contains("warning"))
        assertTrue(line.contains("Blue Line detour"))
        assertTrue(line.contains("1 route"))
    }

    @Test
    fun metadataNamesTheFeedContents() {
        val line = WebProduction.metadataLine(feed())
        assertTrue(line.contains("1 route"))
        assertTrue(line.contains("3 stops"))
        assertTrue(line.contains("2 trips"))
    }

    @Test
    fun anchorIsTheFeedCentre() {
        val anchor = WebProduction.anchorFor(feed())
        assertEquals(44.9864, anchor.lat, 0.000001)
        assertEquals(-93.2575, anchor.lon, 0.000001)
    }
}
