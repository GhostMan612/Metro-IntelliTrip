package com.intellitrip

import com.intellitrip.domain.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WalkEstimateTest {

    private val a = GeoPoint(44.9778, -93.2650)
    private val b = GeoPoint(44.9860, -93.2650)

    @Test
    fun estimateNamesTheDistanceAndRoundsTimeUp() {
        val estimate = WalkEstimator.estimate(a, b)
        assertTrue(estimate.withinCoverage)
        assertTrue(estimate.meters in 850.0..950.0)
        // ~911 m at 1.35 m/s is ~11.2 min, rounded up.
        assertEquals(12, estimate.minutes)
    }

    @Test
    fun estimateIsZeroForTheSamePoint() {
        val estimate = WalkEstimator.estimate(a, a)
        assertEquals(0.0, estimate.meters, 0.001)
        assertEquals(0, estimate.minutes)
        assertTrue(estimate.withinCoverage)
    }

    @Test
    fun farAwayIsReportedAsOutsideCoverageRatherThanHidden() {
        val estimate = WalkEstimator.estimate(a, GeoPoint(44.9580, -93.1560))
        assertFalse(estimate.withinCoverage)
        assertTrue(estimate.minutes > 25)
    }

    @Test
    fun destinationChoiceDoesNotChangeTheAnswer() {
        assertEquals(
            WalkEstimator.estimate(a, b),
            WalkEstimator.estimate(b, a),
        )
    }
}
