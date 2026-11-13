package com.intellitrip.domain

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.datetime.Instant

sealed interface Focus {
    data class Trip(val key: TripKey) : Focus
    data class Route(val key: RouteKey) : Focus
    /**
     * Follows one vehicle.
     *
     * [anchor] is the vehicle's last known position, when the host has one. The
     * scope engine is otherwise position-free: a [VehicleKey] alone cannot say
     * where to put the camera, so a resolver that framed `systemBounds` would
     * show the whole metro instead of the bus the rider selected. Callers that
     * track a live fix pass it here; callers that only have a key leave it null
     * and the resolver falls back to the system extent.
     */
    data class Vehicle(val key: VehicleKey, val anchor: GeoPoint? = null) : Focus
    data class Radius(val center: GeoPoint, val meters: Double) : Focus
    data class Viewport(val bounds: LatLngBounds) : Focus
    data class Region(val agencyScopes: Set<AgencyId>, val bounds: LatLngBounds) : Focus
    data class TripCorridor(
        val tripKey: TripKey,
        val corridorRadiusMeters: Double,
        val anchor: GeoPoint? = null,
    ) : Focus
    data object System : Focus
}

data class CameraScope(val bounds: LatLngBounds?, val zoom: Double?)

data class DataScope(
    val agencies: Set<AgencyId>?,
    val routes: Set<RouteKey>?,
    val trips: Set<TripKey>?,
    val vehicles: Set<VehicleKey>?,
    val bounds: LatLngBounds?,
    val radius: RadiusFilter?,
    val timeWindow: TimeWindow?,
    val maxAge: Duration,
    val includeAlerts: Boolean,
    val systemWide: Boolean,
) {
    companion object {
        /** Feed-wide realtime request with no geographic filter. */
        val DEFAULT_FEED: DataScope = DataScope(
            agencies = null,
            routes = null,
            trips = null,
            vehicles = null,
            bounds = null,
            radius = null,
            timeWindow = null,
            maxAge = 45.seconds,
            includeAlerts = true,
            systemWide = true,
        )
    }
}

data class RadiusFilter(val center: GeoPoint, val meters: Double)
data class TimeWindow(val from: Instant, val to: Instant)

data class RenderScope(val viewport: LatLngBounds, val zoomBucket: ZoomBucket, val maxRenderedVehicles: Int)

enum class ZoomBucket { CLUSTER, SIMPLIFIED, INDIVIDUAL }

data class ScopeState(
    val focus: Focus?,
    val cameraScope: CameraScope,
    val dataScope: DataScope,
    val renderScope: RenderScope,
)
