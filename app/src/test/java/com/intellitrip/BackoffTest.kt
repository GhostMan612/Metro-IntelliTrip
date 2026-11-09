package com.intellitrip

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the poll backoff ladder: 15 s base, doubling per consecutive failure,
 * capped at 120 s. Rate-limited feeds must not be retried at a fixed cadence.
 */
class BackoffTest {

    @Test
    fun backoffDoublesFromBase() {
        assertEquals(15_000L, IntelliTripViewModel.backoffMs(0))
        assertEquals(30_000L, IntelliTripViewModel.backoffMs(1))
        assertEquals(60_000L, IntelliTripViewModel.backoffMs(2))
        assertEquals(120_000L, IntelliTripViewModel.backoffMs(3))
    }

    @Test
    fun backoffCapsAt120Seconds() {
        // 15 * 2^3 = 120s exactly; further failures stay capped.
        assertEquals(120_000L, IntelliTripViewModel.backoffMs(4))
        assertEquals(120_000L, IntelliTripViewModel.backoffMs(10))
        assertEquals(120_000L, IntelliTripViewModel.backoffMs(Int.MAX_VALUE))
    }

    @Test
    fun negativeFailuresBehaveAsNoFailures() {
        assertEquals(15_000L, IntelliTripViewModel.backoffMs(-1))
    }
}
