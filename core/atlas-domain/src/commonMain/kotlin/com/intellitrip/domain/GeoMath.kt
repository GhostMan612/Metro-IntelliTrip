package com.intellitrip.domain

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Shared geographic constants and math.
 *
 * Distance and walking speed used to be re-implemented in the planner, the
 * realtime filter, the departure board and the scope resolver. They agreed by
 * accident, and a change to one silently made the others disagree: a walk leg
 * could be offered at 1.35 m/s while the board labelled the same walk with a
 * different time. Keeping one implementation makes the rider-visible numbers
 * consistent and gives the tests one thing to verify.
 */
object GeoMath {

    /** Metres per degree of latitude, used for cheap local bounding boxes. */
    const val METERS_PER_LATITUDE_DEGREE: Double = 111_320.0

    /** Mean Earth radius used by the great-circle distance. */
    const val EARTH_RADIUS_METERS: Double = 6_371_000.0

    /** A brisk but ordinary walking pace, in metres per second (~4.9 km/h). */
    const val WALK_SPEED_METERS_PER_SECOND: Double = 1.35

    /** Great-circle distance in metres between two points. */
    fun distanceMeters(a: GeoPoint, b: GeoPoint): Double {
        val dLat = (b.lat - a.lat) * PI / 180.0
        val dLon = (b.lon - a.lon) * PI / 180.0
        val lat1 = a.lat * PI / 180.0
        val lat2 = b.lat * PI / 180.0
        val sinLat = sin(dLat / 2)
        val sinLon = sin(dLon / 2)
        val h = sinLat * sinLat + cos(lat1) * cos(lat2) * sinLon * sinLon
        return 2 * EARTH_RADIUS_METERS * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    /**
     * Straight-line walking time in whole minutes, rounded up.
     *
     * Rounding up is deliberate: a rider deciding whether they can catch a bus
     * is better served by two minutes for a 90-second walk than by one.
     */
    fun walkMinutes(
        meters: Double,
        speedMetersPerSecond: Double = WALK_SPEED_METERS_PER_SECOND,
    ): Int {
        if (meters <= 0.0 || speedMetersPerSecond <= 0.0) return 0
        return ceil(meters / speedMetersPerSecond / 60.0).toInt().coerceAtLeast(1)
    }
}
