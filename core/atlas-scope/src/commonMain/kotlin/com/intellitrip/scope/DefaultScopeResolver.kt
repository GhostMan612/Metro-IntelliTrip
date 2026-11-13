package com.intellitrip.scope

import com.intellitrip.contracts.ScopeResolution
import com.intellitrip.contracts.ScopeResolver
import com.intellitrip.domain.CameraScope
import com.intellitrip.domain.DataScope
import com.intellitrip.domain.Focus
import com.intellitrip.domain.GeoPoint
import com.intellitrip.domain.GeoMath
import com.intellitrip.domain.LatLngBounds
import com.intellitrip.domain.RenderScope
import com.intellitrip.domain.ZoomBucket
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Tunable scope behavior. Phase 03 already consumes the scope primitives; this
 * policy supplies the continuous values the resolver derives from a [Focus].
 */
data class ScopePolicy(
    val defaultMaxAge: Duration = 45.seconds,
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

            is Focus.Vehicle -> {
                // A followed vehicle is a specific point, not the metro area. The
                // app passes the latest fix as `anchor`; without one we keep the
                // system extent rather than inventing a location for a bare key.
                val anchor = focus.anchor
                resolution(
                    camera = if (anchor != null) {
                        CameraScope(boundsAround(anchor, policy.tripCorridorMeters), policy.followZoom)
                    } else {
                        CameraScope(policy.systemBounds, policy.followZoom)
                    },
                    data = dataScope(
                        vehicles = setOf(focus.key),
                        bounds = anchor?.let { boundsAround(it, policy.tripCorridorMeters) },
                        radius = anchor?.let { com.intellitrip.domain.RadiusFilter(it, policy.tripCorridorMeters) },
                        maxAge = 20.seconds,
                    ),
                    render = render,
                )
            }

            is Focus.Radius -> resolution(
                // Camera follows the radius center, not the system extent. Framing
                // systemBounds here made every Radius scope show the whole metro, so
                // a focus elsewhere in the city (following the user's own location,
                // say) centered nowhere near its subject.
                camera = CameraScope(boundsAround(focus.center, focus.meters), zoomForRadius(focus.meters)),
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
        val zoom = 17.0 - (log10(clamped / 50.0) * 1.2)
        return zoom.coerceIn(8.0, 17.0)
    }

    /**
     * Inverse of [zoomForRadius]: the radius that maps to [zoom].
     * Viewport gestures must use this so zoom -> meters -> zoom round-trips.
     */
    fun radiusForZoom(zoom: Double): Double {
        val clamped = zoom.coerceIn(8.0, 17.0)
        return 50.0 * 10.0.pow((17.0 - clamped) / 1.2)
    }

    fun boundsAround(center: GeoPoint, meters: Double): LatLngBounds {
        val latDelta = meters / GeoMath.METERS_PER_LATITUDE_DEGREE
        val lonDelta = meters / (GeoMath.METERS_PER_LATITUDE_DEGREE * cos(center.lat * PI / 180.0).coerceAtLeast(0.01))
        return LatLngBounds(
            southWest = GeoPoint((center.lat - latDelta).coerceAtLeast(-90.0), center.lon - lonDelta),
            northEast = GeoPoint((center.lat + latDelta).coerceAtMost(90.0), center.lon + lonDelta),
        )
    }
}
