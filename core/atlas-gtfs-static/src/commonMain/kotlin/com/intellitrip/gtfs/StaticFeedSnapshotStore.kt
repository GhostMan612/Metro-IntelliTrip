package com.intellitrip.gtfs

import com.intellitrip.domain.FeedId
import com.intellitrip.domain.FeedMetadata

/**
 * Conditional-request validators returned with the active payload.
 *
 * Either may be absent: a publisher that sends neither ETag nor Last-Modified
 * does not support conditional refresh, and callers fall back to a full
 * download.
 */
data class FeedValidators(val etag: String? = null, val lastModified: String? = null) {
    val usable: Boolean get() = etag != null || lastModified != null
}

/**
 * Stores validated static feed snapshots.
 *
 * Activation is atomic: each validated payload is written to its own
 * immutable snapshot directory, then a small pointer file is moved into
 * place atomically. A failed validation therefore never replaces the active
 * snapshot, and the previous snapshot remains readable until the new one
 * is fully activated.
 *
 * All file I/O goes through [store] (the platform seam); paths here are
 * store paths starting under the store's root.
 */
class StaticFeedSnapshotStore(
    private val store: PlatformFileStore,
    root: String = "",
) {
    // Paths are stored resolved under the store root so the file-store seam
    // always sees absolute store paths.
    private val root: String = store.resolve(root)

    data class Snapshot(val metadata: FeedMetadata, val snapshotDir: String)

    /** Opens a stop-time sink writing into a staging area; call [close] to finalize. */
    fun newStopTimeSink(): StopTimeSinkHandle {
        val staging = createDirectories(rootOf(STAGING_DIR))
        return StopTimeSinkHandle(
            writer = StopTimeIndexWriter(),
            store = store,
            dataPath = joinPath(staging, STOP_TIME_DATA),
            indexPath = joinPath(staging, STOP_TIME_INDEX),
        )
    }

    fun stopTimeReader(feedId: FeedId): StopTimeIndexReader? {
        val snapshot = activeSnapshot(feedId) ?: return null
        val data = joinPath(snapshot.snapshotDir, STOP_TIME_DATA)
        val index = joinPath(snapshot.snapshotDir, STOP_TIME_INDEX)
        if (!store.exists(data) || !store.exists(index)) return null
        return StopTimeIndexReader(store.readBytes(index), store.readBytes(data))
    }

    fun activeSnapshot(feedId: FeedId): Snapshot? {
        val pointer = feedDir(feedId, POINTER_FILE)
        if (!store.exists(pointer)) return null
        val name = readText(pointer).trim()
        val dir = joinPath(feedDir(feedId, SNAPSHOTS_DIR), name)
        val metaFile = joinPath(dir, METADATA_FILE)
        if (!store.exists(metaFile)) return null
        val meta = readText(metaFile).lines().filter { it.isNotEmpty() }.associate { line ->
            val key = line.substringBefore('=')
            val value = line.substringAfter('=', "")
            key to value
        }
        return Snapshot(
            metadata = FeedMetadata(
                feedId = FeedId(readText(joinPath(dir, FEED_ID_FILE)).trim()),
                publisherName = null,
                publisherUrl = null,
                lang = null,
                version = meta["version"]?.takeIf { it.isNotEmpty() },
                hash = meta["hash"]?.takeIf { it.isNotEmpty() },
                fetchedAt = kotlinx.datetime.Instant.parse(meta["fetchedAt"] ?: kotlinx.datetime.Instant.DISTANT_PAST.toString()),
                validFrom = meta["validFrom"]?.takeIf { it.isNotEmpty() }?.let { parseBasicIsoDate(it) },
                validTo = meta["validTo"]?.takeIf { it.isNotEmpty() }?.let { parseBasicIsoDate(it) },
                sourceUrl = meta["sourceUrl"]?.takeIf { it.isNotEmpty() },
            ),
            snapshotDir = dir,
        )
    }

    /**
     * Writes a downloaded payload to the staging dir and returns its path.
     * Callers that hold the payload as a [ByteArray] should call this
     * *before* parsing so the array can be released before the uncompressed
     * tables peak. Activation then moves this file (no ByteArray copy).
     */
    fun writeStagedPayload(payload: ByteArray): String {
        val staging = createDirectories(rootOf(STAGING_DIR))
        val file = joinPath(staging, STAGED_PAYLOAD_FILE)
        store.writeBytes(file, payload)
        return file
    }

    /**
     * Activates from a previously staged payload file. Same atomicity as
     * [activate] but without holding the payload in memory.
     */
    fun activateStaged(feedId: FeedId, stagedPayload: String, metadata: FeedMetadata): String {
        val snapshots = createDirectories(feedDir(feedId, SNAPSHOTS_DIR))
        val snapshotName = snapshotName(metadata)
        val staging = createDirectories(joinPath(snapshots, snapshotName))
        store.move(stagedPayload, joinPath(staging, PAYLOAD_FILE), atomic = false)
        writeText(joinPath(staging, FEED_ID_FILE), listOf(feedId.value))
        writeText(joinPath(staging, METADATA_FILE), metadataLines(metadata))
        carryStagedStopTimes(staging)
        movePointer(feedId, snapshotName)
        prune(feedId, keepName = snapshotName)
        return staging
    }

    fun activate(feedId: FeedId, payload: ByteArray, metadata: FeedMetadata): String {
        val snapshots = createDirectories(feedDir(feedId, SNAPSHOTS_DIR))
        val snapshotName = snapshotName(metadata)
        val staging = createDirectories(joinPath(snapshots, snapshotName))
        store.writeBytes(joinPath(staging, PAYLOAD_FILE), payload)
        writeText(joinPath(staging, FEED_ID_FILE), listOf(feedId.value))
        writeText(joinPath(staging, METADATA_FILE), metadataLines(metadata))
        // Carry the staged stop-time index into the snapshot when present.
        carryStagedStopTimes(staging)
        movePointer(feedId, snapshotName)
        // The snapshot is live now. Anything left behind is reclaimable, and
        // the failures that leave anything behind are exactly the ones that
        // stop the process: a rejected payload, or a kill mid-refresh.
        // Without this the store grows a full copy of the feed per failed
        // attempt and never recovers.
        prune(feedId, keepName = snapshotName)
        return staging
    }

    /**
     * Removes snapshots that are neither the active one nor a recent
     * fallback.
     *
     * The most recent superseded snapshot is kept so a reader that resolved
     * the active pointer just before it moved still has something valid to
     * open. Pruning runs after the pointer swap, so it can never delete what
     * the pointer names.
     */
    private fun prune(feedId: FeedId, keepName: String) {
        val snapshotsDir = feedDir(feedId, SNAPSHOTS_DIR)
        if (!store.isDirectory(snapshotsDir)) return
        val pointerTarget = runCatching {
            val pointer = feedDir(feedId, POINTER_FILE)
            if (store.exists(pointer)) readText(pointer).trim() else null
        }.getOrNull()
        val candidates = runCatching { store.listNames(snapshotsDir) }.getOrElse { return }
            .filter { store.isDirectory(joinPath(snapshotsDir, it)) }

        // Newest first, by the timestamp encoded in the directory name.
        val ordered = candidates.sortedByDescending { it }
        var kept = 0
        ordered.forEach { name ->
            val dir = joinPath(snapshotsDir, name)
            when {
                name == keepName || name == pointerTarget -> Unit
                kept < RETAINED_SUPERSEDED_SNAPSHOTS -> kept++
                else -> runCatching { store.deleteIfExists(dir) }
            }
        }
    }

    /** Removes a staged payload file after a failed activation. */
    fun deleteStagedPayload(path: String) {
        store.deleteIfExists(path)
    }

    fun readPayload(feedId: FeedId): ByteArray {
        val snapshot = activeSnapshot(feedId)
            ?: throw GtfsArchiveException("No stored GTFS snapshot for feed ${feedId.value}")
        return store.readBytes(joinPath(snapshot.snapshotDir, PAYLOAD_FILE))
    }

    /** Path to the stored ZIP, for parsing without a fresh download. */
    fun payloadPath(feedId: FeedId): String? {
        val snapshot = activeSnapshot(feedId) ?: return null
        val path = joinPath(snapshot.snapshotDir, PAYLOAD_FILE)
        return if (store.exists(path)) path else null
    }

    fun cachePath(feedId: FeedId): String? {
        val snapshot = activeSnapshot(feedId) ?: return null
        val path = joinPath(snapshot.snapshotDir, StaticFeedBinaryCache.CACHE_FILE)
        return if (store.exists(path)) path else null
    }

    /** Binary cache for the active snapshot; null when absent or corrupt. */
    fun readCache(feedId: FeedId): ByteArray? =
        cachePath(feedId)?.let { path -> runCatching { store.readBytes(path) }.getOrNull() }

    /** Writes the binary cache for the active snapshot (cold-start fast path). */
    fun writeCache(feedId: FeedId, feed: StaticFeed) {
        val snapshot = activeSnapshot(feedId) ?: return
        runCatching {
            store.writeBytes(joinPath(snapshot.snapshotDir, StaticFeedBinaryCache.CACHE_FILE), StaticFeedBinaryCache.write(feed))
        }
    }

    private fun snapshotName(metadata: FeedMetadata): String = "snapshot-" +
        kotlinx.datetime.Clock.System.now().toEpochMilliseconds() + "-" +
        (metadata.hash ?: "nohash").take(12)

    private fun metadataLines(metadata: FeedMetadata): List<String> = listOfNotNull(
        metadata.version?.let { "version=$it" },
        metadata.hash?.let { "hash=$it" },
        "fetchedAt=${metadata.fetchedAt}",
        metadata.validFrom?.let { "validFrom=${formatBasicIsoDate(it)}" },
        metadata.validTo?.let { "validTo=${formatBasicIsoDate(it)}" },
        metadata.sourceUrl?.let { "sourceUrl=$it" },
    )

    private fun carryStagedStopTimes(snapshotStaging: String) {
        listOf(STOP_TIME_DATA, STOP_TIME_INDEX).forEach { name ->
            val staged = joinPath(rootOf(STAGING_DIR), name)
            if (store.exists(staged)) {
                store.move(staged, joinPath(snapshotStaging, name), atomic = false)
            }
        }
    }

    private fun movePointer(feedId: FeedId, snapshotName: String) {
        val dir = feedDir(feedId)
        val pointer = joinPath(dir, POINTER_FILE)
        val pointerTemp = joinPath(dir, "$POINTER_FILE.tmp")
        writeText(pointerTemp, listOf(snapshotName))
        store.move(pointerTemp, pointer, atomic = true)
    }

    private fun feedDir(feedId: FeedId, child: String): String = joinPath(feedDir(feedId), child)

    private fun feedDir(feedId: FeedId): String {
        val dir = joinPath(root, feedId.value)
        return dir
    }

    private fun createDirectories(path: String): String {
        store.createDirectories(path)
        return path
    }

    /**
     * HTTP validators for the last activated payload.
     *
     * Kept beside the feed rather than in [FeedMetadata] so the published
     * domain type stays free of transport concerns, and so a feed that never
     * sent an ETag costs nothing.
     */
    fun validators(feedId: FeedId): FeedValidators {
        val file = feedDir(feedId, VALIDATORS_FILE)
        if (!store.exists(file)) return FeedValidators()
        return runCatching {
            val lines = readText(file).lines().filter { it.contains('=') }
            val map = lines.associate {
                it.substringBefore('=').trim() to it.substringAfter('=', "").trim()
            }
            FeedValidators(
                etag = map["etag"]?.takeIf { it.isNotEmpty() },
                lastModified = map["lastModified"]?.takeIf { it.isNotEmpty() },
            )
        }.getOrElse { FeedValidators() }
    }

    /**
     * Records validators from the response that produced the active payload.
     *
     * Written through a temporary file and moved into place so an
     * interrupted write cannot leave a half-written validator that would
     * later produce a mismatched conditional request.
     */
    fun recordValidators(feedId: FeedId, validators: FeedValidators) {
        val dir = createDirectories(feedDir(feedId))
        val target = joinPath(dir, VALIDATORS_FILE)
        val temp = joinPath(dir, "$VALIDATORS_FILE.tmp")
        writeText(
            temp,
            listOfNotNull(
                validators.etag?.let { "etag=$it" },
                validators.lastModified?.let { "lastModified=$it" },
            ),
        )
        store.move(temp, target, atomic = false)
    }

    /**
     * Reads a UTF-8 file. Bytes are decoded in common code; the store seam
     * provides the bytes on every target.
     */
    private fun readText(path: String): String =
        store.readBytes(path).decodeToString().replace("\r\n", "\n")

    private fun writeText(path: String, lines: List<String>) {
        store.writeBytes(path, lines.joinToString("\n").encodeToByteArray())
    }

    private fun rootOf(child: String): String = joinPath(root, child)

    private fun joinPath(base: String, child: String): String =
        if (base.isEmpty()) child else "$base/$child"

    companion object {
        private const val PAYLOAD_FILE = "gtfs.zip"
        private const val METADATA_FILE = "metadata.properties"
        private const val FEED_ID_FILE = "feed-id"
        private const val POINTER_FILE = "active"
        private const val VALIDATORS_FILE = "validators.properties"

        /**
         * Superseded snapshots kept after an activation.
         *
         * One is enough to cover a reader that resolved the active pointer
         * moments before it moved; more than that is disk spent on data
         * nothing will read.
         */
        const val RETAINED_SUPERSEDED_SNAPSHOTS = 1
        private const val SNAPSHOTS_DIR = "snapshots"
        private const val STAGING_DIR = "staging-stop-times"
        private const val STOP_TIME_DATA = "stop-times.dat"
        private const val STOP_TIME_INDEX = "stop-times.idx"
        private const val STAGED_PAYLOAD_FILE = "gtfs.zip.staging"

        /** Formats a [kotlinx.datetime.LocalDate] as GTFS `yyyyMMdd`. */
        private fun formatBasicIsoDate(date: kotlinx.datetime.LocalDate): String =
            date.year.toString().padStart(4, '0') +
                date.monthNumber.toString().padStart(2, '0') +
                date.dayOfMonth.toString().padStart(2, '0')

        /** Parses a GTFS `yyyyMMdd` date; also accepts ISO `yyyy-MM-dd` for forward compat. */
        private fun parseBasicIsoDate(value: String): kotlinx.datetime.LocalDate {
            if (value.contains('-')) return kotlinx.datetime.LocalDate.parse(value)
            val year = value.substring(0, 4).toInt()
            val month = value.substring(4, 6).toInt()
            val day = value.substring(6, 8).toInt()
            return kotlinx.datetime.LocalDate(year, month, day)
        }
    }
}

