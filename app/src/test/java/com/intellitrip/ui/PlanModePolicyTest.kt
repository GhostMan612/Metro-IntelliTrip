package com.intellitrip.ui

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
