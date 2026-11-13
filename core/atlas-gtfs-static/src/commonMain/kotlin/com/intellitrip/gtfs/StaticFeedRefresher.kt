package com.intellitrip.gtfs

import com.intellitrip.contracts.platformDurableGet
import com.intellitrip.contracts.platformDurablePut
import com.intellitrip.domain.FeedId
import com.intellitrip.domain.FeedMetadata
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

sealed interface RefreshOutcome {
    data class NotModified(val feedId: FeedId) : RefreshOutcome
    data class Activated(val feed: StaticFeed) : RefreshOutcome
    data class Rejected(val feedId: FeedId, val reason: String) : RefreshOutcome
}

data class CachedFeed(
    val feed: StaticFeed,
    val age: Duration,
    val stale: Boolean,
)

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
        val currentSnapshot = store.activeSnapshot(feedId)
        val validators = if (currentSnapshot != null) store.validators(feedId) else FeedValidators()
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
                if (currentSnapshot == null || store.activeSnapshot(feedId) == null) {
                    return RefreshOutcome.Rejected(feedId, "304 Not Modified without a usable local snapshot")
                }
                val refreshed = FeedValidators(acquisition.etag, acquisition.lastModified)
                if (refreshed.usable) store.recordValidators(feedId, refreshed, currentSnapshot?.snapshotDir)
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

    private data class DurableManifest(
        val hash: String,
        val fetchedAt: String,
        val etag: String?,
        val lastModified: String?,
        val sourceUrl: String?,
    ) {
        private fun String.oneLine(): String = replace('\r', ' ').replace('\n', ' ')

        fun encode(): String = buildString {
            appendLine("version=1")
            appendLine("hash=${hash.oneLine()}")
            appendLine("fetchedAt=${fetchedAt.oneLine()}")
            etag?.let { appendLine("etag=${it.oneLine()}") }
            lastModified?.let { appendLine("lastModified=${it.oneLine()}") }
            sourceUrl?.let { appendLine("sourceUrl=${it.oneLine()}") }
        }

        companion object {
            fun parse(text: String): DurableManifest? {
                val fields = mutableMapOf<String, String>()
                text.lineSequence().forEach { line ->
                    if (line.isBlank()) return@forEach
                    val separator = line.indexOf('=')
                    if (separator <= 0) return null
                    fields[line.substring(0, separator).trim()] = line.substring(separator + 1)
                }
                if (fields["version"] != "1") return null
                val hash = fields["hash"]?.takeIf { it.isNotBlank() } ?: return null
                val fetchedAt = fields["fetchedAt"]?.takeIf { it.isNotBlank() } ?: return null
                return DurableManifest(
                    hash = hash,
                    fetchedAt = fetchedAt,
                    etag = fields["etag"]?.takeIf { it.isNotBlank() },
                    lastModified = fields["lastModified"]?.takeIf { it.isNotBlank() },
                    sourceUrl = fields["sourceUrl"]?.takeIf { it.isNotBlank() },
                )
            }
        }
    }

    private suspend fun activate(acquisition: GtfsFeedAcquirer.AcquisitionResult.Updated): RefreshOutcome {
        val stage = try {
            store.beginRefresh()
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
        val parsed = try {
            store.writeStagedPayload(acquisition.payload, stage)
            val stopTimeSink = store.newStopTimeSink(stage)
            try {
                GtfsStaticParser.parseFromZip(feedId, acquisition.payload, metadata, stopTimeSink)
            } finally {
                stopTimeSink.close()
            }
        } catch (e: Exception) {
            store.abortRefresh(stage)
            return RefreshOutcome.Rejected(feedId, e.message ?: "Validation failed")
        }
        val feed = parsed.copy(metadata = deriveMetadata(metadata, parsed))
        val snapshotDir = try {
            store.activateStaged(feedId, stage, feed.metadata)
        } catch (e: Exception) {
            store.abortRefresh(stage)
            return RefreshOutcome.Rejected(feedId, e.message ?: "Activation failed")
        }
        val newValidators = FeedValidators(acquisition.etag, acquisition.lastModified)
        runCatching { store.writeCache(feedId, feed, snapshotDir) }
        store.recordValidators(feedId, newValidators, snapshotDir)
        // Durable cross-restart payload cache (IndexedDB on wasmJs, in-memory
        // on JVM): a web shell primed with this can skip re-downloading bytes
        // it already activated. Best-effort — the snapshot store stays
        // authoritative.
        persistDurable(acquisition.payload, feed.metadata, newValidators)
        return RefreshOutcome.Activated(feed)
    }

    private fun deriveMetadata(metadata: FeedMetadata, feed: StaticFeed): FeedMetadata {
        if (metadata.validFrom != null || metadata.validTo != null) return metadata
        val dates = buildList {
            feed.calendars.forEach {
                add(it.startDate)
                add(it.endDate)
            }
            feed.calendarDates.forEach { add(it.date) }
        }
        return metadata.copy(validFrom = dates.minOrNull(), validTo = dates.maxOrNull())
    }

    private suspend fun persistDurable(
        payload: ByteArray,
        metadata: FeedMetadata,
        validators: FeedValidators,
    ) {
        val hash = metadata.hash ?: return
        try {
            platformDurablePut(durablePayloadKey(feedId, hash), payload)
            platformDurablePut(
                durableIndexKey(feedId),
                DurableManifest(
                    hash = hash,
                    fetchedAt = metadata.fetchedAt.toString(),
                    etag = validators.etag,
                    lastModified = validators.lastModified,
                    sourceUrl = metadata.sourceUrl,
                ).encode().encodeToByteArray(),
            )
        } catch (_: Exception) {
            // The snapshot store is authoritative; durable storage only avoids a later download.
        }
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

        fun durableIndexKey(feedId: FeedId): String = "payload-index/" + feedId.value

        val DEFAULT_CACHED_MAX_AGE: Duration = 7.days
    }

    suspend fun rehydrateCached(maxAge: Duration = DEFAULT_CACHED_MAX_AGE): CachedFeed? {
        val indexBytes = try {
            platformDurableGet(durableIndexKey(feedId))
        } catch (_: Exception) {
            null
        } ?: return null
        val manifest = DurableManifest.parse(indexBytes.decodeToString()) ?: return null
        val payload = try {
            platformDurableGet(durablePayloadKey(feedId, manifest.hash))
        } catch (_: Exception) {
            null
        } ?: return null
        if (!GtfsFeedAcquirer.sha256(payload).equals(manifest.hash, ignoreCase = true)) return null

        val fetchedAt = try {
            Instant.parse(manifest.fetchedAt)
        } catch (_: Exception) {
            return null
        }
        val active = store.activeSnapshot(feedId)
        if (active != null && active.metadata.hash != manifest.hash) return null

        val metadata = FeedMetadata(
            feedId = feedId,
            publisherName = null,
            publisherUrl = null,
            lang = null,
            version = null,
            hash = manifest.hash,
            fetchedAt = fetchedAt,
            validFrom = null,
            validTo = null,
            sourceUrl = manifest.sourceUrl ?: sourceUrl,
        )
        val stage = store.beginRefresh()
        val parsed = try {
            val stopTimeSink = store.newStopTimeSink(stage)
            try {
                GtfsStaticParser.parseFromZip(feedId, payload, metadata, stopTimeSink)
            } finally {
                stopTimeSink.close()
            }
        } catch (_: Exception) {
            store.abortRefresh(stage)
            return null
        }
        val finalMetadata = active?.metadata ?: deriveMetadata(metadata, parsed)
        val feed = parsed.copy(metadata = finalMetadata)
        val snapshotDir = if (active == null) {
            try {
                store.writeStagedPayload(payload, stage)
                store.activateStaged(feedId, stage, finalMetadata).also {
                    runCatching { store.writeCache(feedId, feed, it) }
                }
            } catch (_: Exception) {
                store.abortRefresh(stage)
                return null
            }
        } else {
            store.abortRefresh(stage)
            active.snapshotDir
        }
        val cachedValidators = FeedValidators(manifest.etag, manifest.lastModified)
        if (cachedValidators.usable) store.recordValidators(feedId, cachedValidators, snapshotDir)

        val age = (clock() - fetchedAt).coerceAtLeast(Duration.ZERO)
        return CachedFeed(feed = feed, age = age, stale = age > maxAge)
    }

    /** Blocking refresh for hosts that already run on a background thread. */
    fun refreshBlocking(): RefreshOutcome {
        // Without this the acquirer is asked for the whole archive every time,
        // which for a real feed is tens of megabytes on every launch. The
        // publisher decides whether that is necessary: a 304 arrives when the
        // validators still match.
        val currentSnapshot = store.activeSnapshot(feedId)
        val validators = if (currentSnapshot != null) store.validators(feedId)
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
                if (refreshed.usable) store.recordValidators(feedId, refreshed, currentSnapshot?.snapshotDir)
                ensureCurrentStopTimeIndex()
                RefreshOutcome.NotModified(feedId)
            }

            is GtfsFeedAcquirer.AcquisitionResult.Failure -> RefreshOutcome.Rejected(feedId, acquisition.message)
            is GtfsFeedAcquirer.AcquisitionResult.Updated -> {
                val etag = acquisition.etag
                val lastModified = acquisition.lastModified
                // Stage to the store first so the compressed payload can be
                // released before uncompressed tables peak (low-end OOM fix).
                val stage = try {
                    store.beginRefresh()
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
                val parsed = try {
                    store.writeStagedPayload(acquisition.payload, stage)
                    val stopTimeSink = store.newStopTimeSink(stage)
                    val parsed = try {
                        GtfsStaticParser.parseFromZip(feedId, acquisition.payload, metadata, stopTimeSink)
                    } finally {
                        stopTimeSink.close()
                    }
                    parsed
                } catch (e: Exception) {
                    store.abortRefresh(stage)
                    return RefreshOutcome.Rejected(feedId, e.message ?: "Validation failed")
                }
                val feed = parsed.copy(metadata = deriveMetadata(metadata, parsed))
                // Only now that parsing succeeded is the snapshot activated.
                // Recording the new validators before this point would let a
                // rejected payload suppress the retry that would have
                // replaced it.
                // Payload array is still referenced here; release the archive
                // reference via letting the acquisition go out of scope next.
                val snapshotDir = try {
                    store.activateStaged(feedId, stage, feed.metadata)
                } catch (e: Exception) {
                    store.abortRefresh(stage)
                    return RefreshOutcome.Rejected(feedId, e.message ?: "Activation failed")
                }
                // Binary cache for cold start (best-effort).
                runCatching { store.writeCache(feedId, feed, snapshotDir) }
                store.recordValidators(feedId, FeedValidators(etag, lastModified), snapshotDir)
                RefreshOutcome.Activated(feed)
            }
        }
    }
}
