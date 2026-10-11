package com.intellitrip.domain

import java.time.Duration
import java.time.Instant

/**
 * The user's own position, with the accuracy and age needed to decide how to
 * present it.
 *
 * [accuracyMeters] is the radius within which the fix is meaningful. An old fix
 * with poor accuracy must be rendered as "last known", never as "you are here" —
 * the difference is the whole reason this carries metadata rather than a bare point.
 */
data class UserLocation(
    val position: GeoPoint,
    val accuracyMeters: Double,
    val timestamp: Instant,
) {
    /** Whether this is fresh and precise enough to call "here". */
    fun isPrecise(now: Instant = Instant.now()): Boolean =
        accuracyMeters <= PRECISE_ACCURACY_METERS &&
            Duration.between(timestamp, now).seconds <= FRESH_SECONDS

    private companion object {
        /** Roughly a city block. Better than this is GPS noise, not measurement. */
        const val PRECISE_ACCURACY_METERS = 50.0

        /** Beyond a minute the user has plausibly moved. */
        const val FRESH_SECONDS = 60L
    }
}