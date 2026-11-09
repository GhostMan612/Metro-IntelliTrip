package com.intellitrip.offline

import com.intellitrip.domain.FeedId
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

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
    val validityStart: LocalDate? = null,
    val validityEnd: LocalDate? = null,
    val version: String? = null,
    val hash: String? = null,
)

data class FreshnessPolicy(
    val freshWithin: Duration = 12.hours,
    val usableWithin: Duration = 7.days,
)

/**
 * Staleness classification for cached data.
 *
 * Cached realtime data is never promoted to "current": once a snapshot leaves
 * the fresh window it is stale, and beyond the usable window it is expired and
 * must not be rendered as live positions.
 */
class StalenessModel(private val policy: FreshnessPolicy = FreshnessPolicy()) {

    fun classify(fetchedAt: Instant, now: Instant = Clock.System.now()): Freshness {
        val age = now - fetchedAt
        if (age.isNegative()) return Freshness.FRESH
        return when {
            age <= policy.freshWithin -> Freshness.FRESH
            age <= policy.usableWithin -> Freshness.STALE
            else -> Freshness.EXPIRED
        }
    }

    fun classify(entry: CacheEntry?, now: Instant = Clock.System.now()): Freshness =
        entry?.let { classify(it.fetchedAt, now) } ?: Freshness.ABSENT

    /** Realtime positions may only be presented as live while fresh. */
    fun isPresentableAsLive(entry: CacheEntry?, now: Instant = Clock.System.now()): Boolean =
        classify(entry, now) == Freshness.FRESH

    /** Static network stays usable while it has not expired. */
    fun isUsableStatic(entry: CacheEntry?, now: Instant = Clock.System.now()): Boolean =
        when (classify(entry, now)) {
            Freshness.FRESH, Freshness.STALE -> true
            else -> false
        }

    fun age(fetchedAt: Instant, now: Instant = Clock.System.now()): Duration =
        (now - fetchedAt).let { if (it.isNegative()) Duration.ZERO else it }
}