package com.intellitrip.offline

import com.intellitrip.domain.FeedId
import kotlinx.datetime.Instant
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StalenessModelTest {

    private val now = Instant.parse("2026-10-09T12:00:00Z")
    private val model = StalenessModel(
        FreshnessPolicy(freshWithin = 1.hours, usableWithin = 1.days)
    )

    @Test
    fun recentDataIsFresh() {
        assertEquals(Freshness.FRESH, model.classify(now - 600.seconds, now))
    }

    @Test
    fun agingDataIsStaleButUsable() {
        assertEquals(Freshness.STALE, model.classify(now - 6.hours, now))
    }

    @Test
    fun oldDataIsExpired() {
        assertEquals(Freshness.EXPIRED, model.classify(now - 3.days, now))
    }

    @Test
    fun missingDataIsAbsent() {
        assertEquals(Freshness.ABSENT, model.classify(null, now))
    }

    @Test
    fun onlyFreshRealtimeIsPresentableAsLive() {
        assertTrue(model.isPresentableAsLive(CacheEntry(FeedId("f"), now - 60.seconds), now))
        assertFalse(model.isPresentableAsLive(CacheEntry(FeedId("f"), now - 6.hours), now))
        assertFalse(model.isPresentableAsLive(null, now))
    }

    @Test
    fun staticStaysUsableWhileNotExpired() {
        assertTrue(model.isUsableStatic(CacheEntry(FeedId("f"), now - 6.hours), now))
        assertFalse(model.isUsableStatic(CacheEntry(FeedId("f"), now - 3.days), now))
    }
}
