package com.intellitrip.gtfs

import com.intellitrip.domain.FeedId
import com.intellitrip.domain.FeedMetadata
import com.intellitrip.domain.StopTime
import kotlinx.datetime.Clock

private class SnapshotStoreCoordination {
    val lock = SimpleLock()
    val liveStages = mutableSetOf<String>()
}

private object SnapshotStoreCoordinations {
    private val lock = SimpleLock()
    private val byRoot = mutableMapOf<String, SnapshotStoreCoordination>()

    fun forRoot(root: String): SnapshotStoreCoordination = lock.locked {
        byRoot.getOrPut(root) { SnapshotStoreCoordination() }
    }
}

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
    private val coordination = SnapshotStoreCoordinations.forRoot(this.root)

    /**
     * Stop-time readers by snapshot dir, so repeated board reads reuse the
     * parsed index instead of re-reading both files per call. Keys are
     * content-addressed by snapshot dir, so activating a new snapshot simply
     * misses (old entries stay but are never served). Guarded by [readerLock]
     * because the board reads from the main thread while the planner reads
     * from background threads. Bounded: the last 4 dirs are kept.
     */
    private val readerCache = mutableMapOf<String, StopTimeIndexReader>()
    private val readerLock = SimpleLock()

    data class Snapshot(val metadata: FeedMetadata, val snapshotDir: String)

    /**
     * A refresh's payload and stop-time index live together until activation.
     * The unique directory prevents overlapping refreshes from borrowing each
     * other's partially written files.
     */
    data class RefreshStage(
        val id: String,
        val directory: String,
        val payloadPath: String,
        val stopTimeDataPath: String,
        val stopTimeIndexPath: String,
    )

    fun beginRefresh(): RefreshStage {
        return coordination.lock.locked {
            var id: String
            var directory: String
            do {
                id = stageToken()
                directory = joinPath(rootOf(STAGING_DIR), "stage-$id")
            } while (id in coordination.liveStages || store.exists(directory))
            coordination.liveStages += id
            try {
                createDirectories(directory)
                RefreshStage(
                    id = id,
                    directory = directory,
                    payloadPath = joinPath(directory, STAGED_PAYLOAD_FILE),
                    stopTimeDataPath = joinPath(directory, STOP_TIME_DATA),
                    stopTimeIndexPath = joinPath(directory, STOP_TIME_INDEX),
                )
            } catch (e: Exception) {
                coordination.liveStages -= id
                throw e
            }
        }
    }

    fun newStopTimeSink(stage: RefreshStage): StopTimeSinkHandle = StopTimeSinkHandle(
        writer = StopTimeIndexWriter(),
        store = store,
        dataPath = stage.stopTimeDataPath,
        indexPath = stage.stopTimeIndexPath,
    )

    fun writeStagedPayload(payload: ByteArray, stage: RefreshStage) {
        store.writeBytes(stage.payloadPath, payload)
    }

    fun abortRefresh(stage: RefreshStage) {
        coordination.lock.locked {
            coordination.liveStages -= stage.id
            store.deleteIfExists(stage.directory)
        }
    }

    /**
     * Makes a validated stage visible in one serialized commit. In-progress
     * stage directories are never candidates for stale-stage cleanup.
     */
    fun activateStaged(feedId: FeedId, stage: RefreshStage, metadata: FeedMetadata): String =
        coordination.lock.locked {
            var snapshot: String? = null
            var snapshotName: String? = null
            var committed = false
            try {
                val snapshots = createDirectories(feedDir(feedId, SNAPSHOTS_DIR))
                val name = snapshotName(metadata, stage.id)
                snapshotName = name
                val directory = createDirectories(joinPath(snapshots, name))
                snapshot = directory
                store.move(stage.payloadPath, joinPath(directory, PAYLOAD_FILE), atomic = false)
                writeText(joinPath(directory, FEED_ID_FILE), listOf(feedId.value))
                writeText(joinPath(directory, METADATA_FILE), metadataLines(metadata))
                carryStageStopTimes(stage, directory)
                movePointer(feedId, name, stage.id)
                committed = true
            } catch (e: Exception) {
                coordination.liveStages -= stage.id
                committed = snapshotName?.let { name ->
                    runCatching { readText(feedDir(feedId, POINTER_FILE)).trim() == name }
                        .getOrDefault(false)
                } ?: false
                if (committed) {
                    val committedSnapshot = checkNotNull(snapshot)
                    runCatching { store.deleteIfExists(stage.directory) }
                    runCatching { prune(feedId, keepName = checkNotNull(snapshotName)) }
                    return@locked committedSnapshot
                }
                runCatching { snapshot?.let { store.deleteIfExists(it) } }
                runCatching { store.deleteIfExists(stage.directory) }
                throw e
            }
            val committedSnapshot = checkNotNull(snapshot)
            val committedName = checkNotNull(snapshotName)
            coordination.liveStages -= stage.id
            runCatching { store.deleteIfExists(stage.directory) }
            runCatching { prune(feedId, keepName = committedName) }
            committedSnapshot
        }

    /** Removes abandoned stage directories without touching active refreshes. */
    fun pruneStages() {
        coordination.lock.locked { pruneStagesLocked() }
    }

    private fun pruneStagesLocked() {
        val stagingRoot = rootOf(STAGING_DIR)
        if (!store.isDirectory(stagingRoot)) return
        val live = coordination.liveStages.toSet()
        store.listNames(stagingRoot).forEach { name ->
            val id = name.removePrefix("stage-")
            if (name.startsWith("stage-") && id !in live) {
                runCatching { store.deleteIfExists(joinPath(stagingRoot, name)) }
            }
        }
    }

    private fun stageToken(): String =
        Clock.System.now().toEpochMilliseconds().toString() + "-" +
            kotlin.random.Random.nextLong().toULong().toString(16)

    fun stopTimeReader(feedId: FeedId): StopTimeIndexReader? {
        val snapshot = activeSnapshot(feedId) ?: return null
        return readerLock.locked {
            readerCache[snapshot.snapshotDir]?.let { return@locked it }
            val files = stopTimeFiles(snapshot.snapshotDir)
            if (!store.exists(files.data) || !store.exists(files.index)) return@locked null
            // A corrupt or foreign-format pair fails closed rather than
            // returning rows parsed from the wrong record layout.
            val reader = runCatching {
                StopTimeIndexReader(store.readBytes(files.index), store.readBytes(files.data))
            }.getOrNull() ?: return@locked null
            // Bound the cache: drop oldest dirs (map order = insertion order).
            while (readerCache.size >= 4) readerCache.remove(readerCache.keys.first())
            readerCache[snapshot.snapshotDir] = reader
            reader
        }
    }

    /** True when the active snapshot has a usable index in the current format. */
    fun hasCurrentStopTimeIndex(feedId: FeedId): Boolean {
        val snapshot = activeSnapshot(feedId) ?: return false
        val files = stopTimeFiles(snapshot.snapshotDir)
        if (!store.exists(files.data) || !store.exists(files.index)) return false
        // Verify the magic, not just the pointer: a half-pruned or corrupted
        // generation must be rebuilt rather than reported as current.
        return runCatching {
            StopTimeIndexReader.isCurrentFormat(store.readBytes(files.index))
        }.getOrDefault(false)
    }

    /**
     * Rebuilds the active snapshot's stop-time index in the current format.
     *
     * This re-parses the stored archive, so it belongs on a background
     * thread. Publishing is atomic: both files are written under generation
     * names and the pointer file is swapped last. A crash therefore leaves
     * the previous generation readable, and an interrupted rebuild simply
     * runs again on the next refresh.
     *
     * Returns true when the active snapshot now has a current-format index.
     */
    fun reindexStopTimes(feedId: FeedId): Boolean = coordination.lock.locked {
        val snapshot = activeSnapshot(feedId) ?: return@locked false
        val payload = joinPath(snapshot.snapshotDir, PAYLOAD_FILE)
        if (!store.exists(payload)) return@locked false
        try {
            val writer = StopTimeIndexWriter()
            val sink = object : StopTimeSink {
                override fun accept(stopTime: StopTime) {
                    writer.write(stopTime)
                }
            }
            GtfsStaticParser.parseFromZip(feedId, store.readBytes(payload), snapshot.metadata, sink)
            val (data, index) = writer.finish()
            val generation = STOP_TIME_GENERATION_PREFIX + stageToken()
            val dataName = "$generation.dat"
            val indexName = "$generation.idx"
            store.writeBytes(joinPath(snapshot.snapshotDir, dataName), data)
            store.writeBytes(joinPath(snapshot.snapshotDir, indexName), index)
            val pointer = joinPath(snapshot.snapshotDir, STOP_TIME_POINTER)
            val temp = "$pointer.${stageToken()}.tmp"
            writeText(temp, listOf(dataName, indexName))
            store.move(temp, pointer, atomic = true)
            // The cached reader (if any) belongs to the previous generation.
            readerLock.locked { readerCache.remove(snapshot.snapshotDir) }
            pruneStopTimeFiles(snapshot.snapshotDir, dataName, indexName)
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Resolves the data/index pair named by the snapshot's pointer file. */
    private fun stopTimeFiles(snapshotDir: String): StopTimeFiles {
        val pointer = joinPath(snapshotDir, STOP_TIME_POINTER)
        if (store.exists(pointer)) {
            val names = runCatching { readText(pointer).lines().filter { it.isNotBlank() } }.getOrNull()
            if (names != null && names.size == 2) {
                return StopTimeFiles(
                    data = joinPath(snapshotDir, names[0].substringAfterLast('/')),
                    index = joinPath(snapshotDir, names[1].substringAfterLast('/')),
                )
            }
        }
        return StopTimeFiles(
            data = joinPath(snapshotDir, STOP_TIME_DATA),
            index = joinPath(snapshotDir, STOP_TIME_INDEX),
        )
    }

    /** Removes stop-time files from superseded generations (best effort). */
    private fun pruneStopTimeFiles(snapshotDir: String, dataName: String, indexName: String) {
        runCatching {
            store.listNames(snapshotDir)
                .filter { name ->
                    name.startsWith(STOP_TIME_FILE_PREFIX) &&
                        name != dataName &&
                        name != indexName &&
                        name != STOP_TIME_POINTER &&
                        name != "$STOP_TIME_POINTER.tmp"
                }
                .forEach { name -> store.deleteIfExists(joinPath(snapshotDir, name)) }
        }
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

    /** Stores a validated payload directly as a new immutable snapshot. */
    fun activate(feedId: FeedId, payload: ByteArray, metadata: FeedMetadata): String {
        return coordination.lock.locked {
            val snapshots = createDirectories(feedDir(feedId, SNAPSHOTS_DIR))
            val generation = stageToken()
            val snapshotName = snapshotName(metadata, generation)
            val staging = createDirectories(joinPath(snapshots, snapshotName))
            store.writeBytes(joinPath(staging, PAYLOAD_FILE), payload)
            writeText(joinPath(staging, FEED_ID_FILE), listOf(feedId.value))
            writeText(joinPath(staging, METADATA_FILE), metadataLines(metadata))
            movePointer(feedId, snapshotName, generation)
            prune(feedId, keepName = snapshotName)
            staging
        }
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
    fun writeCache(feedId: FeedId, feed: StaticFeed, expectedSnapshotDir: String? = null) {
        coordination.lock.locked {
            val snapshot = activeSnapshot(feedId) ?: return@locked
            if (expectedSnapshotDir != null && snapshot.snapshotDir != expectedSnapshotDir) return@locked
            runCatching {
                store.writeBytes(
                    joinPath(snapshot.snapshotDir, StaticFeedBinaryCache.CACHE_FILE),
                    StaticFeedBinaryCache.write(feed),
                )
            }
        }
    }

    private fun snapshotName(metadata: FeedMetadata, generation: String): String =
        "snapshot-${metadata.fetchedAt.toEpochMilliseconds()}-${(metadata.hash ?: "nohash").take(12)}-$generation"

    private fun metadataLines(metadata: FeedMetadata): List<String> = listOfNotNull(
        metadata.version?.let { "version=$it" },
        metadata.hash?.let { "hash=$it" },
        "fetchedAt=${metadata.fetchedAt}",
        metadata.validFrom?.let { "validFrom=${formatBasicIsoDate(it)}" },
        metadata.validTo?.let { "validTo=${formatBasicIsoDate(it)}" },
        metadata.sourceUrl?.let { "sourceUrl=$it" },
    )

    private data class StopTimeFiles(val data: String, val index: String)

    private fun carryStageStopTimes(stage: RefreshStage, snapshotStaging: String) {
        listOf(STOP_TIME_DATA, STOP_TIME_INDEX).forEach { name ->
            val staged = when (name) {
                STOP_TIME_DATA -> stage.stopTimeDataPath
                else -> stage.stopTimeIndexPath
            }
            if (store.exists(staged)) store.move(staged, joinPath(snapshotStaging, name), atomic = false)
        }
        if (store.exists(joinPath(snapshotStaging, STOP_TIME_DATA)) &&
            store.exists(joinPath(snapshotStaging, STOP_TIME_INDEX))
        ) {
            writeText(
                joinPath(snapshotStaging, STOP_TIME_POINTER),
                listOf(STOP_TIME_DATA, STOP_TIME_INDEX),
            )
        }
    }

    private fun movePointer(feedId: FeedId, snapshotName: String, generation: String) {
        val dir = feedDir(feedId)
        val pointer = joinPath(dir, POINTER_FILE)
        val pointerTemp = joinPath(dir, "$POINTER_FILE.$generation.tmp")
        writeText(pointerTemp, listOf(snapshotName))
        store.move(pointerTemp, pointer, atomic = true)
    }

    private fun feedDir(feedId: FeedId, child: String): String = joinPath(feedDir(feedId), child)

    private fun feedDir(feedId: FeedId): String {
        val id = feedId.value
        // Feed IDs may be supplied by SDK consumers. A raw '../' or platform
        // separator must never escape the app-private snapshot root.
        // Preserve all already-valid single-component feed directory names.
        require(
            id != "." && id != ".." &&
                id.none { it == '/' || it == '\\' || it.code < 0x20 || it.code == 0x7f }
        ) { "FeedId must be a safe single path component" }
        return joinPath(root, id)
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
    fun recordValidators(feedId: FeedId, validators: FeedValidators, expectedSnapshotDir: String? = null) {
        coordination.lock.locked {
            if (expectedSnapshotDir != null && activeSnapshot(feedId)?.snapshotDir != expectedSnapshotDir) return@locked
            val dir = createDirectories(feedDir(feedId))
            val target = joinPath(dir, VALIDATORS_FILE)
            val temp = joinPath(dir, "$VALIDATORS_FILE.${stageToken()}.tmp")
            writeText(
                temp,
                listOfNotNull(
                    validators.etag?.let { "etag=$it" },
                    validators.lastModified?.let { "lastModified=$it" },
                ),
            )
            store.move(temp, target, atomic = false)
        }
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
        private const val STOP_TIME_POINTER = "stop-times.current"
        private const val STOP_TIME_FILE_PREFIX = "stop-times"
        private const val STOP_TIME_GENERATION_PREFIX = "stop-times-g"
        private const val STAGED_PAYLOAD_FILE = "gtfs.zip"

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
