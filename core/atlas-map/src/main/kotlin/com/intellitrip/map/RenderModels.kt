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
    /**
     * When this data was fetched from the provider.
     *
     * Distinct from the vehicle's own `updatedAt`: operators may omit per-vehicle
     * timestamps and reuse a stale header timestamp, so fetch time is the more
     * reliable freshness signal. Null means "not supplied", in which case each
     * vehicle's own timestamp is authoritative on its own.
     */
    val fetchedAt: Instant? = null,
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

/**
 * A scheduled stop as drawn.
 *
 * [feedId] and [name] travel with the stop because stop ids are only unique within a
 * feed. Rendering a bare id and then resolving the selection by that id is what lets
 * two agencies' first stops collide into one entry.
 */
data class StopRender(
    val id: String,
    val feedId: String,
    val name: String,
    val lon: Double,
    val lat: Double,
) {
    /** Feed-qualified key used by hit testing and selection lookup. */
    val key: String get() = "$feedId/$id"
}

data class ShapeLineRender(val id: String, val coordinates: List<List<Double>>)

/**
 * A planned journey's transit geometry, drawn as its own layer above the network.
 *
 * Coordinates are `[lon, lat]` pairs, matching GeoJSON order rather than the
 * latitude-first order used by the domain's [com.intellitrip.domain.GeoPoint].
 */
data class RouteLine(
    val id: String,
    val coordinates: List<DoubleArray>,
)
