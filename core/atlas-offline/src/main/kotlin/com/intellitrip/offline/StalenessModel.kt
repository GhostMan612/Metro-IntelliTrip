package com.intellitrip.offline

import com.intellitrip.domain.FeedId
import java.time.Duration
import java.time.Instant

/** How fresh a cached artifact is relative to policy. */
enum class Freshness {
    /** Within the preferred window; safe to present as current. */
    FRESH,

    /** Usable but clearly labeled as aging. */
    STALE,

    /** Beyond the usable window; must not be presented as current. */
    EXPIRED,

    /** No cached copy exists. */
    ABSENT,
}

data class CacheEntry(
    val feedId: FeedId,
    val fetchedAt: Instant,
    val validityStart: java.time.LocalDate? = null,
    val validityEnd: java.time.LocalDate? = null,
    val version: String? = null,
    val hash: String? = null,
)

data class FreshnessPolicy(
    val freshWithin: Duration = Duration.ofHours(12),
    val usableWithin: Duration = Duration.ofDays(7),
)

/**
 * Staleness classification for cached data.
 *
 * Cached realtime data is never promoted to "current": once a snapshot leaves
 * the fresh window it is stale, and beyond the usable window it is expired and
 * must not be rendered as live positions.
 */
class StalenessModel(private val policy: FreshnessPolicy = FreshnessPolicy()) {

    fun classify(fetchedAt: Instant, now: Instant = Instant.now()): Freshness {
        val age = Duration.between(fetchedAt, now)
        if (age.isNegative) return Freshness.FRESH
        return when {
            age <= policy.freshWithin -> Freshness.FRESH
            age <= policy.usableWithin -> Freshness.STALE
            else -> Freshness.EXPIRED
        }
    }

    fun classify(entry: CacheEntry?, now: Instant = Instant.now()): Freshness =
        entry?.let { classify(it.fetchedAt, now) } ?: Freshness.ABSENT

    /** Realtime positions may only be presented as live while fresh. */
    fun isPresentableAsLive(entry: CacheEntry?, now: Instant = Instant.now()): Boolean =
        classify(entry, now) == Freshness.FRESH

    /** Static network stays usable while it has not expired. */
    fun isUsableStatic(entry: CacheEntry?, now: Instant = Instant.now()): Boolean =
        when (classify(entry, now)) {
            Freshness.FRESH, Freshness.STALE -> true
            else -> false
        }

    fun age(fetchedAt: Instant, now: Instant = Instant.now()): Duration =
        Duration.between(fetchedAt, now).let { if (it.isNegative) Duration.ZERO else it }
}