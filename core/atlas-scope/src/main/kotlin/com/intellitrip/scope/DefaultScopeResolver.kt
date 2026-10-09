package com.intellitrip.scope

import com.intellitrip.contracts.ScopeResolution
import com.intellitrip.contracts.ScopeResolver
import com.intellitrip.domain.CameraScope
import com.intellitrip.domain.DataScope
import com.intellitrip.domain.Focus
import com.intellitrip.domain.GeoPoint
import com.intellitrip.domain.LatLngBounds
import com.intellitrip.domain.RenderScope
import com.intellitrip.domain.ZoomBucket
import java.time.Duration

/**
 * Tunable scope behaviour. Phase 03 already consumes the scope primitives; this
 * policy supplies the continuous values the resolver derives from a [Focus].
 */
data class ScopePolicy(
    val defaultMaxAge: Duration = Duration.ofSeconds(45),
    val followZoom: Double = 15.0,
    val routeZoom: Double = 13.0,
    val regionZoom: Double = 11.0,
    val systemZoom: Double = 9.5,
    val radiusMeters: Double = 805.0,
    val tripCorridorMeters: Double = 1_200.0,
    val maxRenderedVehiclesSystem: Int = 6_000,
    val maxRenderedVehiclesRegional: Int = 2_500,
    val maxRenderedVehiclesLocal: Int = 500,
    val systemBounds: LatLngBounds = TwinCitiesBounds,
    val zoomThresholds: ZoomThresholds = ZoomThresholds(),
)

data class ZoomThresholds(
    val clusterMaxZoom: Double = 13.0,
    val individualMinZoom: Double = 14.0,
) {
    fun bucketFor(zoom: Double): ZoomBucket = when {
        zoom < clusterMaxZoom -> ZoomBucket.CLUSTER
        zoom < individualMinZoom -> ZoomBucket.SIMPLIFIED
        else -> ZoomBucket.INDIVIDUAL
    }
}

/** Metro Twin Cities bounding box; used as the "system" reference area. */
val TwinCitiesBounds: LatLngBounds =
    LatLngBounds(GeoPoint(44.75, -93.65), GeoPoint(45.30, -92.85))

/**
 * Resolves a [Focus] into camera, data and render scopes.
 *
 * Camera scope, data scope and render scope stay independent: a citywide camera
 * may still request system-wide data, and a local camera never silently widens
 * the data request.
 */
class DefaultScopeResolver(private val policy: ScopePolicy = ScopePolicy()) : ScopeResolver {

    override fun resolve(focus: Focus?): ScopeResolution {
        val render = renderScopeFor(focus)
        return when (focus) {
            null -> resolution(
                camera = CameraScope(policy.systemBounds, policy.systemZoom),
                data = dataScope(systemWide = true),
                render = render,
            )

            is Focus.Trip -> resolution(
                camera = CameraScope(policy.systemBounds, policy.followZoom),
                data = dataScope(trips = setOf(focus.key), maxAge = policy.defaultMaxAge),
                render = render,
            )

            is Focus.Route -> resolution(
                camera = CameraScope(policy.systemBounds, policy.routeZoom),
                data = dataScope(routes = setOf(focus.key), maxAge = policy.defaultMaxAge),
                render = render,
            )

            is Focus.Vehicle -> resolution(
                camera = CameraScope(policy.systemBounds, policy.followZoom),
                data = dataScope(vehicles = setOf(focus.key), maxAge = Duration.ofSeconds(20)),
                render = render,
            )

            is Focus.Radius -> resolution(
                camera = CameraScope(policy.systemBounds, zoomForRadius(focus.meters)),
                data = dataScope(
                    bounds = boundsAround(focus.center, focus.meters),
                    radius = com.intellitrip.domain.RadiusFilter(focus.center, focus.meters),
                    maxAge = policy.defaultMaxAge,
                ),
                render = render,
            )

            is Focus.Viewport -> resolution(
                camera = CameraScope(focus.bounds, policy.regionZoom),
                data = dataScope(bounds = focus.bounds, maxAge = policy.defaultMaxAge),
                render = render,
            )

            is Focus.Region -> resolution(
                camera = CameraScope(focus.bounds, policy.regionZoom),
                data = dataScope(
                    agencies = focus.agencyScopes,
                    bounds = focus.bounds,
                    maxAge = policy.defaultMaxAge,
                ),
                render = render,
            )

            is Focus.TripCorridor -> {
                val anchor = focus.anchor ?: policy.systemBounds.center()
                resolution(
                    camera = CameraScope(policy.systemBounds, policy.followZoom),
                    data = dataScope(
                        trips = setOf(focus.tripKey),
                        bounds = boundsAround(anchor, focus.corridorRadiusMeters),
                        radius = com.intellitrip.domain.RadiusFilter(anchor, focus.corridorRadiusMeters),
                        maxAge = policy.defaultMaxAge,
                    ),
                    render = render,
                )
            }

            Focus.System -> resolution(
                camera = CameraScope(policy.systemBounds, policy.systemZoom),
                data = dataScope(systemWide = true),
                render = render,
            )
        }
    }

    private fun resolution(
        camera: CameraScope,
        data: DataScope,
        render: RenderScope,
    ): ScopeResolution = ScopeResolution(camera, data, render)

    private fun dataScope(
        agencies: Set<com.intellitrip.domain.AgencyId>? = null,
        routes: Set<com.intellitrip.domain.RouteKey>? = null,
        trips: Set<com.intellitrip.domain.TripKey>? = null,
        vehicles: Set<com.intellitrip.domain.VehicleKey>? = null,
        bounds: LatLngBounds? = null,
        radius: com.intellitrip.domain.RadiusFilter? = null,
        maxAge: Duration = policy.defaultMaxAge,
        systemWide: Boolean = false,
    ): DataScope = DataScope(
        agencies = agencies,
        routes = routes,
        trips = trips,
        vehicles = vehicles,
        bounds = bounds,
        radius = radius,
        timeWindow = null,
        maxAge = maxAge,
        includeAlerts = !systemWide,
        systemWide = systemWide,
    )

    private fun renderScopeFor(focus: Focus?): RenderScope {
        val zoom = cameraZoomFor(focus)
        val bucket = policy.zoomThresholds.bucketFor(zoom)
        val budget = when (bucket) {
            ZoomBucket.CLUSTER -> policy.maxRenderedVehiclesSystem
            ZoomBucket.SIMPLIFIED -> policy.maxRenderedVehiclesRegional
            ZoomBucket.INDIVIDUAL -> policy.maxRenderedVehiclesLocal
        }
        return RenderScope(viewport = policy.systemBounds, zoomBucket = bucket, maxRenderedVehicles = budget)
    }

    private fun cameraZoomFor(focus: Focus?): Double = when (focus) {
        null, is Focus.System -> policy.systemZoom
        is Focus.Trip, is Focus.Vehicle, is Focus.TripCorridor -> policy.followZoom
        is Focus.Route -> policy.routeZoom
        is Focus.Region, is Focus.Viewport -> policy.regionZoom
        is Focus.Radius -> zoomForRadius(focus.meters)
    }

    /** Continuous: closer radii request closer zooms rather than discrete modes. */
    fun zoomForRadius(meters: Double): Double {
        val clamped = meters.coerceAtLeast(50.0)
        val zoom = 17.0 - (Math.log10(clamped / 50.0) * 1.2)
        return zoom.coerceIn(8.0, 17.0)
    }

    fun boundsAround(center: GeoPoint, meters: Double): LatLngBounds {
        val latDelta = meters / 111_320.0
        val lonDelta = meters / (111_320.0 * Math.cos(Math.toRadians(center.lat)).coerceAtLeast(0.01))
        return LatLngBounds(
            southWest = GeoPoint((center.lat - latDelta).coerceAtLeast(-90.0), center.lon - lonDelta),
            northEast = GeoPoint((center.lat + latDelta).coerceAtLeast(0.0), center.lon + lonDelta),
        )
    }
}

fun LatLngBounds.center(): GeoPoint = GeoPoint(
    lat = (southWest.lat + northEast.lat) / 2,
    lon = (southWest.lon + northEast.lon) / 2,
)