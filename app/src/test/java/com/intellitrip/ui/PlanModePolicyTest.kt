package com.intellitrip.ui

import org.junit.Test
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

class PlanModePolicyTest {
    @Test
    fun walkEstimateNeverEnablesTransitSubmission() {
        assertFalse(canSubmitTransitPlan(walkEstimateOnly = true, planning = false))
        assertFalse(canSubmitTransitPlan(walkEstimateOnly = true, planning = true))
    }

    @Test
    fun transitPlanningIsOnlyEnabledWhileIdle() {
        assertTrue(canSubmitTransitPlan(walkEstimateOnly = false, planning = false))
        assertFalse(canSubmitTransitPlan(walkEstimateOnly = false, planning = true))
    }
}
