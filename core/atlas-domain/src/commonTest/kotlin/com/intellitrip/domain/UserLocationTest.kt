package com.intellitrip.domain

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertEquals
import kotlinx.datetime.Instant
import kotlin.time.Duration.Companion.seconds

class UserLocationTest {
    private val now = Instant.parse("2026-11-12T16:00:00Z")
    private val position = GeoPoint(44.95, -93.09)

    private fun fix(timestamp: Instant, accuracyMeters: Double = 15.0) =
        UserLocation(position, accuracyMeters, timestamp)

    @Test
    fun recentAndSlightlySkewedFixesRemainUsable() {
        assertTrue(fix(now - 30.seconds).isFresh(now))
        assertTrue(fix(now + 5.seconds).isFresh(now))
        assertTrue(fix(now - 60.seconds).shouldCenterCamera(now))
    }

    @Test
    fun ancientOrFutureDatedFixesNeverMoveTheCamera() {
        assertFalse(fix(now - 61.seconds).isFresh(now))
        assertFalse(fix(now + 6.seconds).isFresh(now))
        assertFalse(fix(now + 86_400.seconds).shouldCenterCamera(now))
        assertEquals("Location timestamp invalid", fix(now + 86_400.seconds).describe(now))
    }

    @Test
    fun highAccuracyRejectsInvalidRadiusWithoutBlockingCoarseFreshFixes() {
        assertFalse(fix(now, Double.NaN).isHighAccuracy(now))
        assertFalse(fix(now, Double.POSITIVE_INFINITY).isHighAccuracy(now))
        assertFalse(fix(now, -2.0).isHighAccuracy(now))
        assertTrue(fix(now, 20.0).isHighAccuracy(now))
        assertTrue(fix(now, 2_000.0).shouldCenterCamera(now))
        assertEquals("Location accuracy unavailable", fix(now, Double.NaN).describe(now))
    }
}
