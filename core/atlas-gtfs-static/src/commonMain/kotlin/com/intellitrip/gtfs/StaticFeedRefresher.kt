package com.intellitrip.gtfs

import com.intellitrip.domain.FeedId
import com.intellitrip.domain.FeedMetadata
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

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
    // `Dispatchers.IO` is JVM-only; Default is the portable choice here.
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {

    /**
     * Acquires, validates and activates the feed. Suspends on an IO
     * dispatcher: callers may invoke this from the main thread without
     * blocking it.
     */
    suspend fun refresh(): RefreshOutcome = kotlinx.coroutines.withContext(ioDispatcher) { refreshBlockingAsync() }

    /**
     * Same semantics as [refreshBlocking] over [IngestedFeedAcquirer]. The acquire
     * step on wasmJs goes over the browser fetch bridge; file/parse work stays
     * in-memory/byte-based, so the whole refresh is portable.
     */
    private suspend fun refreshBlockingAsync(): RefreshOutcome {
        val validators = if (store.activeSnapshot(feedId) != null) store.validators(feedId)
            else FeedValidators(null, null)
        val acquisition = if (validators.usable) {
            acquirer.acquireAsync(
                feedId = feedId,
                url = sourceUrl,
                currentEtag = validators.etag,
                currentLastModified = validators.lastModified,
            )
        } else {
            acquirer.acquireAsync(feedId, sourceUrl)
        }
        return when (acquisition) {
            is GtfsFeedAcquirer.AcquisitionResult.NotModified -> {
                if (store.activeSnapshot(feedId) == null) {
                    return RefreshOutcome.Rejected(feedId, "304 Not Modified without a usable local snapshot")
                }
                val refreshed = FeedValidators(acquisition.etag, acquisition.lastModified)
                if (refreshed.usable) store.recordValidators(feedId, refreshed)
                // A 304 is the cheapest moment to migrate a snapshot written
                // by an older build: the archive is already on disk, and this
                // runs off the main thread. No-op once the index is current.
                ensureCurrentStopTimeIndex()
                RefreshOutcome.NotModified(feedId)
            }
            is GtfsFeedAcquirer.AcquisitionResult.Failure -> RefreshOutcome.Rejected(feedId, acquisition.message)
            is GtfsFeedAcquirer.AcquisitionResult.Updated -> activate(acquisition)
        }
    }

    private suspend fun activate(acquisition: GtfsFeedAcquirer.AcquisitionResult.Updated): RefreshOutcome {
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
            version = null,
            hash = GtfsFeedAcquirer.sha256(acquisition.payload),
            fetchedAt = clock(),
            validFrom = null,
            validTo = null,
            sourceUrl = sourceUrl,
        )
        val feed = try {
            // Parse from the payload bytes, streaming each entry row-by-row
            // to the stop-time sink (same as refreshBlocking).
            val stopTimeSink = store.newStopTimeSink()
            try {
                GtfsStaticParser.parseFromZip(feedId, acquisition.payload, metadata, stopTimeSink)
            } finally {
                stopTimeSink.close()
            }
        } catch (e: Exception) {
            runCatching { store.deleteStagedPayload(stagedPayload) }
            return RefreshOutcome.Rejected(feedId, e.message ?: "Validation failed")
        }
        store.activateStaged(feedId, stagedPayload, metadata)
        runCatching { store.writeCache(feedId, feed) }
        store.recordValidators(feedId, FeedValidators(acquisition.etag, acquisition.lastModified))
        // Durable cross-restart payload cache (IndexedDB on wasmJs, in-memory
        // on JVM): a web shell primed with this can skip re-downloading bytes
        // it already activated. Best-effort — the snapshot store stays
        // authoritative; validators still drive the next conditional GET.
        runCatching { com.intellitrip.contracts.platformDurablePut(durablePayloadKey(feedId, metadata.hash), acquisition.payload) }
        return RefreshOutcome.Activated(feed)
    }

    /**
     * Brings a snapshot written by an older build onto the current stop-time
     * index format. Re-parses the stored archive, so it must not run on the
     * UI thread: [refresh] is already on a background dispatcher, and
     * [refreshBlocking] callers already run on one.
     */
    private fun ensureCurrentStopTimeIndex() {
        if (!store.hasCurrentStopTimeIndex(feedId)) store.reindexStopTimes(feedId)
    }

    companion object {
        fun durablePayloadKey(feedId: FeedId, hash: String?): String =
            "payload/" + feedId.value + "/" + (hash ?: "nohash")
    }

    /** Blocking refresh for hosts that already run on a background thread. */
    fun refreshBlocking(): RefreshOutcome {
        // Without this the acquirer is asked for the whole archive every time,
        // which for a real feed is tens of megabytes on every launch. The
        // publisher decides whether that is necessary: a 304 arrives when the
        // validators still match.
        val validators = if (store.activeSnapshot(feedId) != null) store.validators(feedId)
            else FeedValidators(null, null)
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
                if (store.activeSnapshot(feedId) == null) {
                    return RefreshOutcome.Rejected(feedId, "304 Not Modified without a usable local snapshot")
                }
                // A 304 may rotate the validators, so refresh them before
                // returning.
                val refreshed = FeedValidators(acquisition.etag, acquisition.lastModified)
                if (refreshed.usable) store.recordValidators(feedId, refreshed)
                ensureCurrentStopTimeIndex()
                RefreshOutcome.NotModified(feedId)
            }

            is GtfsFeedAcquirer.AcquisitionResult.Failure -> RefreshOutcome.Rejected(feedId, acquisition.message)
            is GtfsFeedAcquirer.AcquisitionResult.Updated -> {
                val etag = acquisition.etag
                val lastModified = acquisition.lastModified
                // Stage to the store first so the compressed payload can be
                // released before uncompressed tables peak (low-end OOM fix).
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
                    version = null,
                    hash = GtfsFeedAcquirer.sha256(acquisition.payload),
                    fetchedAt = clock(),
                    validFrom = null,
                    validTo = null,
                    sourceUrl = sourceUrl,
                )
                val feed = try {
                    // Parse from the payload bytes, streaming each entry
                    // row-by-row to the stop-time sink.
                    val stopTimeSink = store.newStopTimeSink()
                    val parsed = try {
                        GtfsStaticParser.parseFromZip(feedId, acquisition.payload, metadata, stopTimeSink)
                    } finally {
                        stopTimeSink.close()
                    }
                    parsed
                } catch (e: Exception) {
                    runCatching { store.deleteStagedPayload(stagedPayload) }
                    return RefreshOutcome.Rejected(feedId, e.message ?: "Validation failed")
                }
                // Only now that parsing succeeded is the snapshot activated.
                // Recording the new validators before this point would let a
                // rejected payload suppress the retry that would have
                // replaced it.
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


