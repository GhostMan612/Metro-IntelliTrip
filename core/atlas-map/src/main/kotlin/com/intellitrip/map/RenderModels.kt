package com.intellitrip.map

import com.intellitrip.domain.GeoPoint
import com.intellitrip.domain.LatLngBounds
import com.intellitrip.domain.Shape
import com.intellitrip.domain.Stop
import com.intellitrip.domain.StopKey
import com.intellitrip.domain.Vehicle
import com.intellitrip.domain.VehicleType
import com.intellitrip.domain.ZoomBucket
import java.time.Instant

/** Immutable render input: a transit snapshot plus the current camera. */
data class RenderInput(
    val camera: CameraView,
    val vehicles: List<Vehicle>,
    val stops: List<Stop>,
    val shapes: List<Shape>,
    val snapshotAt: Instant,
    val previousVehicles: List<Vehicle> = emptyList(),
)

data class CameraView(
    val bounds: LatLngBounds,
    val zoom: Double,
)

/**
 * Immutable output of the render pipeline. Renderers consume this snapshot and
 * push it to a bulk source/layer; they never query providers directly and never
 * create one view object per feature.
 */
data class RenderSnapshot(
    val camera: CameraView,
    val zoomBucket: ZoomBucket,
    val vehicles: List<VehicleRender>,
    val clusters: List<ClusterRender>,
    val stops: List<StopRender>,
    val shapeLines: List<ShapeLineRender>,
    val truncatedVehicleCount: Int,
)

data class VehicleRender(
    val id: String,
    val lon: Double,
    val lat: Double,
    val bearing: Double?,
    val vehicleType: VehicleType,
    val stale: Boolean,
    val interpolated: Boolean,
)

data class ClusterRender(
    val id: String,
    val lon: Double,
    val lat: Double,
    val count: Int,
)

data class StopRender(val id: String, val lon: Double, val lat: Double)

data class ShapeLineRender(val id: String, val coordinates: List<List<Double>>)
