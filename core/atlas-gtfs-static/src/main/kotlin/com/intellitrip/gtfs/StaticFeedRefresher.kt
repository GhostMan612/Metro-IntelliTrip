package com.intellitrip.gtfs

import com.intellitrip.domain.FeedId
import com.intellitrip.domain.FeedMetadata
import java.time.Instant
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    /**
     * Acquires, validates and activates the feed. Suspends on an IO dispatcher:
     * callers may invoke this from the main thread without blocking it.
     */
    suspend fun refresh(): RefreshOutcome = withContext(ioDispatcher) { refreshBlocking() }

    /** Blocking refresh for hosts that already run on a background thread. */
    fun refreshBlocking(): RefreshOutcome {
        val active = store.activeSnapshot(feedId)
        // Without this the acquirer is asked for the whole archive every time, which
        // for a real feed is tens of megabytes on every launch. The publisher decides
        // whether that is necessary: a 304 arrives when the validators still match.
        val validators = store.validators(feedId)
        val acquisition = if (validators.usable) {
            acquirer.acquire(
                feedId = feedId,
                url = sourceUrl,
                currentEtag = validators.etag,
                currentLastModified = validators.lastModified,
            )
        } else {
            acquirer.acquire(feedId, sourceUrl)
        }
        return when (acquisition) {
            is GtfsFeedAcquirer.AcquisitionResult.NotModified -> {
                // A 304 may rotate the validators, so refresh them before returning.
                val refreshed = FeedValidators(acquisition.etag, acquisition.lastModified)
                if (refreshed.usable) store.recordValidators(feedId, refreshed)
                RefreshOutcome.NotModified(feedId)
            }

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
                    val files = GtfsArchive.read(acquisition.payload).toMutableMap()
                    // Stop times dominate the feed's memory profile, so they are
                    // streamed into a disk-backed index instead of being retained.
                    val stopTimeSink = store.newStopTimeSink()
                    val parsed = try {
                        GtfsStaticParser.parse(feedId, files, metadata, stopTimeSink)
                    } finally {
                        stopTimeSink.close()
                    }
                    parsed
                } catch (e: Exception) {
                    return RefreshOutcome.Rejected(feedId, e.message ?: "Validation failed")
                }
                // Only now that parsing succeeded is the snapshot activated. Recording
                // the new validators before this point would let a rejected payload
                // suppress the retry that would have replaced it.
                store.activate(feedId, acquisition.payload, metadata)
                store.recordValidators(feedId, FeedValidators(acquisition.etag, acquisition.lastModified))
                RefreshOutcome.Activated(feed)
            }
        }
    }
}