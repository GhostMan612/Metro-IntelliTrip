package com.intellitrip

import com.intellitrip.domain.GeoMath
import com.intellitrip.domain.GeoPoint

/**
 * How the rider asked to travel.
 *
 * Walking is a first-class answer, not a planner failure. The app has no street
 * network, so it must never draw a made-up walking route: the estimate below is
 * explicitly straight-line and the UI says so.
 */
enum class TravelMode { TRANSIT, WALK_ONLY }

/**
 * Straight-line walking estimate between two points.
 *
 * This is deliberately not a [com.intellitrip.domain.JourneyOption]. A journey
 * carries legs with times and geometry; claiming one here would be a routed
 * result the app cannot produce. The UI presents this as an estimate with the
 * method named.
 */
data class WalkEstimate(
    val meters: Double,
    val minutes: Int,
    val withinCoverage: Boolean,
)

object WalkEstimator {

    /**
     * Beyond this a walk is not a realistic alternative for most riders.
     *
     * Two kilometres is roughly 25 minutes at the shared walking pace. The
     * cut-off is a product rule, not a routing limit, and is shown to the rider
     * instead of silently returning nothing.
     */
    const val MAX_WALKABLE_METERS: Double = 2_000.0

    fun estimate(origin: GeoPoint, destination: GeoPoint): WalkEstimate {
        val meters = GeoMath.distanceMeters(origin, destination)
        return WalkEstimate(
            meters = meters,
            minutes = GeoMath.walkMinutes(meters),
            withinCoverage = meters <= MAX_WALKABLE_METERS,
        )
    }
}
