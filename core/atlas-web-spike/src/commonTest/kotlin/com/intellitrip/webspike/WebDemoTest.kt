package com.intellitrip.webspike

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

class WebDemoTest {

    @Test
    fun demoPlanSucceedsOnLocalNetwork() {
        val verdict = runBlocking { WebDemo.demoPlanText() }
        assertTrue(verdict.contains("planned"), "expected a plan, got: $verdict")
    }

    @Test
    fun demoSnapshotHasStopsAndVehicle() {
        val snapshot = WebDemo.demoSnapshot()
        assertTrue(snapshot.stops.isNotEmpty())
        assertTrue(snapshot.vehicles.isNotEmpty())
    }

    @Test
    fun glideProgressMovesVehicle() {
        val start = WebDemo.demoSnapshot(progress = 0.0).vehicles.single()
        val end = WebDemo.demoSnapshot(progress = 1.0).vehicles.single()
        assertTrue(start.lon != end.lon || start.lat != end.lat)
        assertTrue(start.lat < end.lat)
    }

    @Test
    fun radarOverlayIsRainViewerTileTemplate() {
        val overlay = WebDemo.demoRadarOverlay()
        assertTrue(overlay.tileUrlTemplate.contains("tilecache.rainviewer.com"))
        assertTrue(overlay.tileUrlTemplate.contains("{z}/{x}/{y}"))
    }
}
