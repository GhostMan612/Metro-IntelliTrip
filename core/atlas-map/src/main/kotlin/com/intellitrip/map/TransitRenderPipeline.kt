package com.intellitrip.map

import com.intellitrip.domain.GeoPoint
import com.intellitrip.domain.ZoomBucket
import java.time.Duration
import java.time.Instant
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Provider-neutral rendering policy.
 *
 * Pipeline: live snapshot -> spatial filtering -> viewport culling -> LOD /
 * clustering -> bulk renderer payload. System-wide views are handled by
 * bucketing and clustering rather than by capping the requested radius.
 */
class TransitRenderPipeline(
    private val maxRenderedVehicles: Int = 6_000,
    private val clusterCellDegrees: Double = 0.02,
    private val staleThresholdSeconds: Long = 60,
    private val clusterMaxZoom: Double = 13.0,
    private val individualMinZoom: Double = 14.0,
) {

    companion object {
        /** Upper bound on points emitted per rendered shape line. */
        const val MAX_SHAPE_POINTS = 2_000
    }

    fun zoomBucket(zoom: Double): ZoomBucket = when {
        zoom < clusterMaxZoom -> ZoomBucket.CLUSTER
        zoom < individualMinZoom -> ZoomBucket.SIMPLIFIED
        else -> ZoomBucket.INDIVIDUAL
    }

    fun render(input: RenderInput): RenderSnapshot {
        val bucket = zoomBucket(input.camera.zoom)
        val visibleVehicles = input.vehicles.filter { input.camera.bounds.contains(it.position) }

        val stops = input.stops
            .filter { input.camera.bounds.contains(it.location) }
            .map { StopRender(it.key.stopId, it.location.lon, it.location.lat) }

        // Route geometry is large (hundreds of thousands of points system-wide), so
        // shapes are culled to the viewport and dropped entirely when clustered.
        val shapeLines = when (bucket) {
            ZoomBucket.CLUSTER -> emptyList()
            else -> input.shapes.mapNotNull { shape ->
                val clipped = shape.points.filter { input.camera.bounds.contains(it) }
                if (clipped.size < 2) {
                    null
                } else {
                    ShapeLineRender(
                        id = shape.key.shapeId,
                        coordinates = simplify(clipped).map { listOf(it.lon, it.lat) },
                    )
                }
            }
        }

        val interpolated = interpolate(input.previousVehicles, visibleVehicles, input.snapshotAt)
            .takeIf { bucket == ZoomBucket.INDIVIDUAL }
            ?: visibleVehicles.map { it to false }

        val staleAfter = input.snapshotAt.minusSeconds(staleThresholdSeconds)
        val vehicles = if (bucket == ZoomBucket.CLUSTER) emptyList() else interpolated
            .take(maxRenderedVehicles)
            .map { (vehicle, moved) ->
                VehicleRender(
                    id = vehicle.key.vehicleId,
                    lon = vehicle.position.lon,
                    lat = vehicle.position.lat,
                    bearing = vehicle.bearing,
                    vehicleType = vehicle.vehicleType,
                    stale = vehicle.updatedAt < staleAfter,
                    interpolated = moved,
                )
            }

        val clusters = when (bucket) {
            ZoomBucket.CLUSTER -> cluster(interpolated.map { it.first })
            ZoomBucket.SIMPLIFIED, ZoomBucket.INDIVIDUAL -> emptyList()
        }

        return RenderSnapshot(
            camera = input.camera,
            zoomBucket = bucket,
            vehicles = vehicles,
            clusters = clusters,
            stops = stops,
            shapeLines = shapeLines,
            truncatedVehicleCount = (interpolated.size - vehicles.size).coerceAtLeast(0),
        )
    }

    /**
     * Interpolates vehicles between two realtime snapshots so motion stays smooth
     * between polls. Vehicles without a usable prior position are not moved.
     */
    fun interpolate(
        previous: List<com.intellitrip.domain.Vehicle>,
        current: List<com.intellitrip.domain.Vehicle>,
        now: Instant,
        maxDeltaSeconds: Long = 30,
    ): List<Pair<com.intellitrip.domain.Vehicle, Boolean>> {
        val previousById = previous.associateBy { it.key }
        return current.map { vehicle ->
            val prior = previousById[vehicle.key]
            val delta = if (prior == null) 0 else Duration.between(prior.updatedAt, vehicle.updatedAt).seconds
            if (prior == null || delta <= 0 || delta > maxDeltaSeconds) {
                vehicle to false
            } else {
                val fraction = (Duration.between(vehicle.updatedAt, now).seconds.toDouble() / delta)
                    .coerceIn(0.0, 1.0)
                vehicle.copy(
                    position = GeoPoint(
                        lat = interpolateCoordinate(prior.position.lat, vehicle.position.lat, fraction),
                        lon = interpolateCoordinate(prior.position.lon, vehicle.position.lon, fraction),
                    ),
                ) to (fraction > 0.0)
            }
        }
    }

    private fun interpolateCoordinate(from: Double, to: Double, fraction: Double): Double {
        val delta = to - from
        if (abs(delta) > 180.0) {
            // dateline wrap: take the shorter path around the globe
            val adjusted = if (delta > 0) delta - 360.0 else delta + 360.0
            return normalizeLongitude(from + adjusted * fraction)
        }
        return from + delta * fraction
    }

    private fun normalizeLongitude(value: Double): Double {
        var lon = value
        while (lon > 180.0) lon -= 360.0
        while (lon < -180.0) lon += 360.0
        return lon
    }

    /**
     * Drops points that are visually indistinguishable at the current zoom so a
     * 320k-point feed does not become a multi-megabyte render payload.
     */
    private fun simplify(points: List<GeoPoint>): List<GeoPoint> {
        if (points.size <= MAX_SHAPE_POINTS) return points
        val step = (points.size / MAX_SHAPE_POINTS).coerceAtLeast(1)
        val reduced = ArrayList<GeoPoint>(MAX_SHAPE_POINTS + 1)
        var index = 0
        while (index < points.size) {
            reduced += points[index]
            index += step
        }
        if (reduced.last() !== points.last()) reduced += points.last()
        return reduced
    }

    /** Grid-based clustering keeps the renderer free of per-feature objects. */
    private fun cluster(vehicles: List<com.intellitrip.domain.Vehicle>): List<ClusterRender> {
        val cells = LinkedHashMap<String, MutableList<com.intellitrip.domain.Vehicle>>()
        vehicles.forEach { vehicle ->
            val cellLat = (vehicle.position.lat / clusterCellDegrees).roundToInt()
            val cellLon = (vehicle.position.lon / clusterCellDegrees).roundToInt()
            cells.getOrPut("$cellLat:$cellLon") { mutableListOf() }.add(vehicle)
        }
        return cells.entries
            .filter { it.value.size > 1 }
            .map { (cell, members) ->
                val lat = members.sumOf { it.position.lat } / members.size
                val lon = members.sumOf { normalizeLongitude(it.position.lon) } / members.size
                ClusterRender(id = "cluster-$cell", lon = lon, lat = lat, count = members.size)
            }
    }
}