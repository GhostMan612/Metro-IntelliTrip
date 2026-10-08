package com.intellitrip.contracts

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProviderResultTest {
    @Test
    fun successCarriesFreshness() {
        val now = Instant.parse("2026-10-07T00:00:00Z")
        val freshness = FreshnessMetadata(now, now, Duration.ZERO, "test-provider")
        val result = ProviderResult.Success("ok", freshness)
        assertEquals("ok", result.data)
        assertEquals(Duration.ZERO, result.freshness.age)
    }

    @Test
    fun staleSuccessNeverCountsAsFresh() {
        val now = Instant.parse("2026-10-07T00:00:00Z")
        val freshness = FreshnessMetadata(now, now, Duration.ofMinutes(5), "test-provider")
        val result = ProviderResult.StaleSuccess("old", freshness)
        assertTrue(result is ProviderResult.StaleSuccess<String>)
    }
}
