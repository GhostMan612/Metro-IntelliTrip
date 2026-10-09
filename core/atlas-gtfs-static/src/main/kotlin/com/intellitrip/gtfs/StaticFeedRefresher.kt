package com.intellitrip.gtfs

import com.intellitrip.domain.FeedId
import com.intellitrip.domain.FeedMetadata
import java.time.Instant

sealed interface RefreshOutcome {
    data class NotModified(val feedId: FeedId) : RefreshOutcome
    data class Activated(val feed: StaticFeed) : RefreshOutcome
    data class Rejected(val feedId: FeedId, val reason: String) : RefreshOutcome
}

/**
 * Coordinates conditional refresh: acquire (with validators), parse/validate,
 * then atomically activate. A failed validation never replaces the active
 * snapshot.
 */
class StaticFeedRefresher(
    private val feedId: FeedId,
    private val sourceUrl: String,
    private val acquirer: GtfsFeedAcquirer,
    private val store: StaticFeedSnapshotStore,
    private val clock: () -> Instant = Instant::now,
) {

    fun refresh(): RefreshOutcome {
        val active = store.activeSnapshot(feedId)
        val acquisition = acquirer.acquire(feedId, sourceUrl)
        return when (acquisition) {
            is GtfsFeedAcquirer.AcquisitionResult.NotModified -> RefreshOutcome.NotModified(feedId)
            is GtfsFeedAcquirer.AcquisitionResult.Failure -> RefreshOutcome.Rejected(feedId, acquisition.message)
            is GtfsFeedAcquirer.AcquisitionResult.Updated -> {
                val metadata = FeedMetadata(
                    feedId = feedId,
                    publisherName = null,
                    publisherUrl = null,
                    lang = null,
                    version = active?.metadata?.version,
                    hash = GtfsFeedAcquirer.sha256(acquisition.payload),
                    fetchedAt = clock(),
                    validFrom = active?.metadata?.validFrom,
                    validTo = active?.metadata?.validTo,
                    sourceUrl = sourceUrl,
                )
                val feed = try {
                    val files = GtfsArchive.read(acquisition.payload)
                    GtfsStaticParser.parse(feedId, files, metadata)
                } catch (e: Exception) {
                    return RefreshOutcome.Rejected(feedId, e.message ?: "Validation failed")
                }
                store.activate(feedId, acquisition.payload, metadata)
                RefreshOutcome.Activated(feed)
            }
        }
    }
}