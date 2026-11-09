package com.intellitrip.gtfs

import com.intellitrip.domain.FeedId
import com.intellitrip.domain.FeedMetadata
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
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
    private val clock: () -> Instant = { Clock.System.now() },
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
                val etag = acquisition.etag
                val lastModified = acquisition.lastModified
                // Stage to disk first so the compressed payload can be released
                // before uncompressed tables peak (low-end OOM fix).
                val stagedPayload = try {
                    store.writeStagedPayload(acquisition.payload)
                } catch (e: Exception) {
                    return RefreshOutcome.Rejected(feedId, e.message ?: "Storage failed")
                }
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
                    // Parse by streaming each ZIP entry as an InputStream from the
                    // staged file. No payload ByteArray, no per-table buffers.
                    val stopTimeSink = store.newStopTimeSink()
                    val parsed = try {
                        GtfsStaticParser.parseFromZip(feedId, stagedPayload, metadata, stopTimeSink)
                    } finally {
                        stopTimeSink.close()
                    }
                    parsed
                } catch (e: Exception) {
                    runCatching { java.nio.file.Files.deleteIfExists(stagedPayload) }
                    return RefreshOutcome.Rejected(feedId, e.message ?: "Validation failed")
                }
                // Only now that parsing succeeded is the snapshot activated. Recording
                // the new validators before this point would let a rejected payload
                // suppress the retry that would have replaced it.
                // Payload array is still referenced here; release the archive
                // reference via letting the acquisition go out of scope next.
                store.activateStaged(feedId, stagedPayload, metadata)
                // Binary cache for cold start (best-effort).
                runCatching { store.writeCache(feedId, feed) }
                store.recordValidators(feedId, FeedValidators(etag, lastModified))
                RefreshOutcome.Activated(feed)
            }
        }
    }
}