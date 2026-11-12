package com.intellitrip.domain

import kotlin.time.Duration
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.time.Duration.Companion.seconds

/**
 * The user's own position, with the accuracy and age needed to decide how to
 * present it.
 *
 * [accuracyMeters] is the radius within which the fix is meaningful. An old fix
 * with poor accuracy must be rendered as "last known", never as "you are here" —
 * the difference is the whole reason this carries metadata rather than a bare point.
 *
 * Freshness and precision are deliberately independent questions, because a
 * rider who grants only approximate location produces a fix that can be current
 * and still be far worse than 50 m. Folding the two together, as an earlier
 * `isPrecise()` did, meant such a rider was shown on the map but the map never
 * moved to them, which reads as a broken locate button rather than as reduced
 * precision.
 */
data class UserLocation(
    val position: GeoPoint,
    val accuracyMeters: Double,
    val timestamp: Instant,
) {
    /**
     * Whether the fix is recent enough to act on.
     *
     * This is the only question that may block camera movement. An approximate
     * fix that is one second old is still the rider's best current position.
     */
    fun isFresh(now: Instant = Clock.System.now()): Boolean {
        val age = now - timestamp
        // A future clock or corrupted provider timestamp is not a fresh fix.
        // Allow a small device/provider clock skew, but never an unbounded one.
        return age >= (-MAX_FUTURE_SKEW_SECONDS).seconds && age <= FRESH_SECONDS.seconds
    }

    /**
     * Whether the fix is tight enough to describe as "you are here".
     *
     * Governs wording and the size of the accuracy halo, never whether the map
     * centers. A fresh 2 km fix still centers the map; it is simply labelled as
     * approximate.
     */
    fun isHighAccuracy(now: Instant = Clock.System.now()): Boolean =
        isFresh(now) && accuracyMeters.isFinite() &&
            accuracyMeters >= 0.0 && accuracyMeters <= HIGH_ACCURACY_METERS

    /**
     * Whether the camera may move to this fix.
     *
     * Equivalent to [isFresh]: precision never blocks camera movement. A coarse
     * fix that is stale, however, must not drag the map anywhere.
     */
    fun shouldCenterCamera(now: Instant = Clock.System.now()): Boolean = isFresh(now)

    /** Text describing the fix honestly, distinguishing age from accuracy. */
    fun describe(now: Instant = Clock.System.now()): String = when {
        timestamp > now + MAX_FUTURE_SKEW_SECONDS.seconds -> "Location timestamp invalid"
        !isFresh(now) -> "Last known location, ${describeAge(now)}"
        !accuracyMeters.isFinite() || accuracyMeters < 0.0 -> "Location accuracy unavailable"
        accuracyMeters <= HIGH_ACCURACY_METERS -> "Location +/-${accuracyMeters.toInt()} m"
        else -> "Approximate location, +/-${describeAccuracy()} m"
    }

    private fun describeAge(now: Instant): String {
        val seconds = (now - timestamp).inWholeSeconds.coerceAtLeast(0)
        return when {
            seconds > 86_400 -> "${seconds / 86_400}d old"
            else -> "${seconds / 60} min old"
        }
    }

    /** Coarse enough that the exact metre count implies more than we know. */
    private fun describeAccuracy(): String =
        if (accuracyMeters >= 1_000) "${(accuracyMeters / 1_000).toInt()} km"
        else "${(accuracyMeters / 100).toInt() * 100} m"

    private companion object {
        /** Roughly a city block. Better than this is GPS noise, not measurement. */
        const val HIGH_ACCURACY_METERS = 50.0

        /** Beyond a minute the user has plausibly moved. */
        const val FRESH_SECONDS = 60L

        /** Tolerated GPS/provider clock difference; anything later is invalid. */
        const val MAX_FUTURE_SKEW_SECONDS = 5L
    }
}