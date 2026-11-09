package com.intellitrip.map.web

import com.intellitrip.map.BasemapStyle
import com.intellitrip.map.RenderSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WebRenderBridgeTest {

    private fun snapshot() = RenderSnapshot(
        camera = com.intellitrip.map.CameraView(
            bounds = com.intellitrip.domain.LatLngBounds(
                com.intellitrip.domain.GeoPoint(44.9, -93.3),
                com.intellitrip.domain.GeoPoint(45.0, -93.1),
            ),
            zoom = 15.0,
        ),
        zoomBucket = com.intellitrip.domain.ZoomBucket.INDIVIDUAL,
        vehicles = listOf(
            com.intellitrip.map.VehicleRender(
                id = "v1", lon = -93.265, lat = 44.977, bearing = 90.0,
                vehicleType = com.intellitrip.domain.VehicleType.BUS,
                stale = false, interpolated = false, routeColor = "A00000",
            )
        ),
        clusters = emptyList(),
        stops = listOf(
            com.intellitrip.map.StopRender(
                id = "S1", feedId = "f", name = "First", lon = -93.265, lat = 44.977,
            )
        ),
        shapeLines = listOf(
            com.intellitrip.map.ShapeLineRender(
                id = "sh1",
                coordinates = listOf(listOf(-93.265, 44.977), listOf(-93.25, 44.99)),
            )
        ),
        truncatedVehicleCount = 0,
    )

    @Test
    fun emptyLayersAreOmitted() {
        val snap = snapshot().copy(vehicles = emptyList(), stops = emptyList(), shapeLines = emptyList())
        val payloads = snapshotToSources(snap)
        assertTrue(payloads.isEmpty(), "empty snapshot must produce no source payloads, got $payloads")
    }

    @Test
    fun payloadsCarryGeoJsonPerLayer() {
        val payloads = snapshotToSources(snapshot())
        val layers = payloads.map { it.layer }
        assertEquals(listOf(WebLayer.VEHICLES, WebLayer.STOPS, WebLayer.SHAPES), layers)
        payloads.forEach { assertTrue(it.geoJson.contains("\"FeatureCollection\""), "${it.layer} not GeoJSON") }
    }

    @Test
    fun styleUrlPassesThroughBasemapStyle() {
        val style = BasemapStyle("https://example.test/style.json", null, "attribution")
        assertEquals("https://example.test/style.json", webStyleUrl(style))
        assertEquals(null, webStyleUrl(BasemapStyle(null, "basemap/offline-style.json", "attribution")))
    }
}
