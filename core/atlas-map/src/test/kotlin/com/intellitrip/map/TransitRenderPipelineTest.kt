package com.intellitrip.map

import com.intellitrip.domain.GeoPoint
import com.intellitrip.domain.LatLngBounds
import com.intellitrip.domain.Shape
import com.intellitrip.domain.ShapeKey
import com.intellitrip.domain.Stop
import com.intellitrip.domain.StopKey
import com.intellitrip.domain.Vehicle
import com.intellitrip.domain.VehicleKey
import com.intellitrip.domain.VehicleType
import com.intellitrip.domain.ZoomBucket
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TransitRenderPipelineTest {

    private val feedId = com.intellitrip.domain.FeedId("metro-transit-regional")
    private val bounds = LatLngBounds(GeoPoint(44.90, -93.30), GeoPoint(45.00, -93.10))
    private val now = Instant.parse("2026-10-07T12:00:00Z")

    private fun vehicle(id: String, lat: Double, lon: Double, updatedAt: Instant = now) = Vehicle(
        key = VehicleKey(feedId, id),
        routeKey = null,
        tripKey = null,
        position = GeoPoint(lat, lon),
        bearing = 90.0,
        speedMps = 5.0,
        updatedAt = updatedAt,
        vehicleType = VehicleType.BUS,
    )

    private fun renderInput(zoom: Double, vehicles: List<Vehicle>) = RenderInput(
        camera = CameraView(bounds, zoom),
        vehicles = vehicles,
        stops = emptyList(),
        shapes = emptyList(),
        snapshotAt = now,
    )

    @Test
    fun zoomSelectsBucket() {
        val pipeline = TransitRenderPipeline()
        assertEquals(ZoomBucket.CLUSTER, pipeline.zoomBucket(10.0))
        assertEquals(ZoomBucket.SIMPLIFIED, pipeline.zoomBucket(13.5))
        assertEquals(ZoomBucket.INDIVIDUAL, pipeline.zoomBucket(15.0))
    }

    @Test
    fun vehiclesOutsideViewportAreCulled() {
        val pipeline = TransitRenderPipeline()
        val inside = vehicle("a", 44.95, -93.20)
        val outside = vehicle("b", 45.50, -94.00)
        val snapshot = pipeline.render(renderInput(15.0, listOf(inside, outside)))
        assertEquals(listOf("a"), snapshot.vehicles.map { it.id })
    }

    @Test
    fun lowZoomClustersInsteadOfRenderingEveryVehicle() {
        val pipeline = TransitRenderPipeline(clusterCellDegrees = 0.05)
        val vehicles = (1..25).map { vehicle("bus-$it", 44.950 + it * 0.0001, -93.200 + it * 0.0001) }
        val snapshot = pipeline.render(renderInput(10.0, vehicles))
        assertTrue(snapshot.vehicles.isEmpty())
        assertTrue(snapshot.clusters.isNotEmpty())
        assertEquals(25, snapshot.clusters.sumOf { it.count })
    }

    @Test
    fun highZoomRendersIndividualVehicles() {
        val pipeline = TransitRenderPipeline()
        val vehicles = (1..25).map { vehicle("bus-$it", 44.950 + it * 0.0001, -93.200 + it * 0.0001) }
        val snapshot = pipeline.render(renderInput(15.0, vehicles))
        assertEquals(25, snapshot.vehicles.size)
        assertTrue(snapshot.clusters.isEmpty())
    }

    @Test
    fun vehicleBudgetIsReportedWhenTruncated() {
        val pipeline = TransitRenderPipeline(maxRenderedVehicles = 5)
        val vehicles = (1..10).map { vehicle("bus-$it", 44.950 + it * 0.0001, -93.200 + it * 0.0001) }
        val snapshot = pipeline.render(renderInput(15.0, vehicles))
        assertEquals(5, snapshot.vehicles.size)
        assertEquals(5, snapshot.truncatedVehicleCount)
    }

    @Test
    fun staleVehiclesAreFlagged() {
        val pipeline = TransitRenderPipeline(staleThresholdSeconds = 60)
        val fresh = vehicle("fresh", 44.95, -93.20, now)
        val stale = vehicle("stale", 44.951, -93.201, now.minusSeconds(600))
        val snapshot = pipeline.render(renderInput(15.0, listOf(fresh, stale)))
        assertEquals(false, snapshot.vehicles.first { it.id == "fresh" }.stale)
        assertEquals(true, snapshot.vehicles.first { it.id == "stale" }.stale)
    }

    @Test
    fun interpolationMovesVehiclesBetweenSnapshots() {
        val pipeline = TransitRenderPipeline()
        val previous = listOf(vehicle("a", 44.90, -93.20, now.minusSeconds(20)))
        val current = listOf(vehicle("a", 44.91, -93.20, now))
        val interpolated = pipeline.interpolate(previous, current, now.plusSeconds(10))
        val (moved, wasInterpolated) = interpolated.single()
        assertTrue(wasInterpolated)
        assertTrue(moved.position.lat > 44.90 && moved.position.lat < 44.91)
    }

    @Test
    fun interpolationSkipsVehiclesWithoutPriorSnapshot() {
        val pipeline = TransitRenderPipeline()
        val current = listOf(vehicle("new", 44.95, -93.20))
        val (_, wasInterpolated) = pipeline.interpolate(emptyList(), current, now).single()
        assertTrue(!wasInterpolated)
    }

    @Test
    fun geoJsonProducesBulkCollections() {
        val pipeline = TransitRenderPipeline()
        val vehicles = (1..5).map { vehicle("bus-$it", 44.950 + it * 0.0001, -93.200) }
        val snapshot = pipeline.render(
            renderInput(15.0, vehicles).copy(
                stops = listOf(Stop(StopKey(feedId, "34"), "34", "Chicago Ave", null, GeoPoint(44.97, -93.26), null, 0, null, null)),
                shapes = listOf(Shape(ShapeKey(feedId, "SH1"), listOf(GeoPoint(44.97, -93.26), GeoPoint(44.96, -93.27)))),
            )
        )
        val vehicleJson = GeoJsonWriter.vehicles(snapshot)
        assertTrue(vehicleJson.startsWith("{\"type\":\"FeatureCollection\""))
        assertTrue(vehicleJson.contains("bus-5"))
        assertEquals(1, Regex("\"id\":\"34\"").findAll(GeoJsonWriter.stops(snapshot)).count())
        assertTrue(GeoJsonWriter.shapes(snapshot).contains("LineString"))
    }

    @Test
    fun throttleLimitsUpdateFrequency() {
        val throttle = RenderUpdateThrottle(minimumInterval = java.time.Duration.ofMillis(250))
        assertTrue(throttle.shouldEmit(now))
        assertTrue(!throttle.shouldEmit(now.plusMillis(100)))
        assertTrue(throttle.shouldEmit(now.plusMillis(300)))
    }
}