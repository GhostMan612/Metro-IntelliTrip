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

/**
 * A feed restored from the durable payload cache instead of the network.
 *
 * [age] is measured from the original activation time, not from the reload,
 * so a browser tab reopened a week later reports a week-old feed rather than
 * pretending it was just fetched. [stale] is a display hint, not a validity
 * claim: the parser still had to accept the payload.
 */
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
        val validators = store.validators(feedId)
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
                val refreshed = FeedValidators(acquisition.etag, acquisition.lastModified)
                if (refreshed.usable) store.recordValidators(feedId, refreshed)
                RefreshOutcome.NotModified(feedId)
            }
            is GtfsFeedAcquirer.AcquisitionResult.Failure -> RefreshOutcome.Rejected(feedId, acquisition.message)
            is GtfsFeedAcquirer.AcquisitionResult.Updated -> activate(acquisition)
        }
    }

    private suspend fun activate(acquisition: GtfsFeedAcquirer.AcquisitionResult.Updated): RefreshOutcome {
        val active = store.activeSnapshot(feedId)
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
        // it already activated. The index is written after the payload so a
        // half-completed write can never point at missing bytes.
        persistDurable(
            payload = acquisition.payload,
            metadata = metadata,
            validators = FeedValidators(acquisition.etag, acquisition.lastModified),
        )
        return RefreshOutcome.Activated(feed)
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
            // The snapshot store is authoritative; an unavailable durable
            // cache only costs a re-download after reload.
        }
    }

    /**
     * Restores the last activated payload from the durable byte cache.
     *
     * Intended for the browser shell immediately after start, where the
     * in-memory [StaticFeedSnapshotStore] is empty but IndexedDB still holds
     * the ZIP. The payload is re-hashed and fully re-parsed, so a truncated or
     * corrupted entry is rejected rather than surfaced as a valid feed. The
     * original `fetchedAt` is preserved, and [CachedFeed.stale] reports when
     * it is older than [maxAge] without withholding otherwise usable data.
     *
     * Never replaces an active snapshot with a different hash: if the process
     * already refreshed, the network result wins.
     */
    suspend fun rehydrateCached(
        maxAge: Duration = DEFAULT_CACHED_MAX_AGE,
    ): CachedFeed? {
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

        // Integrity before parsing: the key is content-addressed, so a
        // mismatch means the stored bytes are not the feed that was activated.
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
            version = active?.metadata?.version,
            hash = manifest.hash,
            fetchedAt = fetchedAt,
            validFrom = active?.metadata?.validFrom,
            validTo = active?.metadata?.validTo,
            sourceUrl = manifest.sourceUrl ?: sourceUrl,
        )
        val feed = try {
            val stopTimeSink = store.newStopTimeSink()
            try {
                GtfsStaticParser.parseFromZip(feedId, payload, metadata, stopTimeSink)
            } finally {
                stopTimeSink.close()
            }
        } catch (_: Exception) {
            return null
        }

        if (active == null) {
            store.activate(feedId, payload, metadata)
            runCatching { store.writeCache(feedId, feed) }
        }
        val validators = FeedValidators(manifest.etag, manifest.lastModified)
        if (validators.usable) store.recordValidators(feedId, validators)

        val age = (clock() - fetchedAt).coerceAtLeast(Duration.ZERO)
        return CachedFeed(feed = feed, age = age, stale = age > maxAge)
    }

    companion object {
        fun durablePayloadKey(feedId: FeedId, hash: String?): String =
            "payload/" + feedId.value + "/" + (hash ?: "nohash")

        /** Points at the payload key plus the validators and original fetch time. */
        fun durableIndexKey(feedId: FeedId): String = "payload-index/" + feedId.value

        /** A week is longer than the regional feed's usual update cadence. */
        val DEFAULT_CACHED_MAX_AGE: Duration = 7.days
    }

    /** Blocking refresh for hosts that already run on a background thread. */
    fun refreshBlocking(): RefreshOutcome {
        val active = store.activeSnapshot(feedId)
        // Without this the acquirer is asked for the whole archive every time,
        // which for a real feed is tens of megabytes on every launch. The
        // publisher decides whether that is necessary: a 304 arrives when the
        // validators still match.
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
                // A 304 may rotate the validators, so refresh them before
                // returning.
                val refreshed = FeedValidators(acquisition.etag, acquisition.lastModified)
                if (refreshed.usable) store.recordValidators(feedId, refreshed)
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
                    version = active?.metadata?.version,
                    hash = GtfsFeedAcquirer.sha256(acquisition.payload),
                    fetchedAt = clock(),
                    validFrom = active?.metadata?.validFrom,
                    validTo = active?.metadata?.validTo,
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



/**
 * Small text manifest beside the durable payload.
 *
 * Text rather than JSON keeps the cache readable in DevTools and avoids a
 * serialization dependency in this module. Values are single-line; ETags
 * containing quotes are harmless because only `=` and newlines are special.
 */
private data class DurableManifest(
    val hash: String,
    val fetchedAt: String,
    val etag: String?,
    val lastModified: String?,
    val sourceUrl: String?,
) {
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

private fun String.oneLine(): String = replace('\r', ' ').replace('\n', ' ')
