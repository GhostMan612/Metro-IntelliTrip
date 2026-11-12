package com.intellitrip

import com.intellitrip.domain.GeoMath
import com.intellitrip.domain.GeoPoint
import com.intellitrip.domain.UserLocation
import kotlinx.datetime.Instant

/**
 * The arrival cue is evidence-gated and one-shot.
 *
 * A cue is an assertion that the rider has reached the destination. It may only
 * fire from a fresh, high-accuracy fix inside the radius, and at most once per
 * selected journey. Re-running the location loop must not re-ring it.
 */
internal object ArrivalGate {

    const val RADIUS_METERS: Double = 100.0

    fun shouldCue(
        fix: UserLocation,
        destination: GeoPoint,
        journeyId: String,
        alreadyCuedJourneyId: String?,
        now: Instant,
    ): Boolean =
        alreadyCuedJourneyId != journeyId &&
            fix.isHighAccuracy(now) &&
            GeoMath.distanceMeters(fix.position, destination) <= RADIUS_METERS
}
