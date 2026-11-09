package com.intellitrip.gtfs

import com.intellitrip.domain.FeedId
import com.intellitrip.domain.FeedMetadata
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate

/**
 * Conditional-request validators returned with the active payload.
 *
 * Either may be absent: a publisher that sends neither ETag nor Last-Modified does
 * not support conditional refresh, and callers fall back to a full download.
 */
data class FeedValidators(val etag: String? = null, val lastModified: String? = null) {
    val usable: Boolean get() = etag != null || lastModified != null
}

/**
 * Stores validated static feed snapshots.
 *
 * Activation is atomic: each validated payload is written to its own immutable
 * snapshot directory, then a small pointer file is moved into place atomically.
 * A failed validation therefore never replaces the active snapshot, and the
 * previous snapshot remains readable until the new one is fully activated.
 */
class StaticFeedSnapshotStore(private val root: Path) {

    data class Snapshot(val metadata: FeedMetadata, val snapshotDir: Path)

    /** Opens a stop-time sink writing into a staging area; call [close] to finalize. */
    fun newStopTimeSink(): StopTimeSinkHandle {
        val staging = Files.createDirectories(root.resolve(STAGING_DIR))
        return StopTimeSinkHandle(
            writer = StopTimeIndexWriter(Files.newOutputStream(staging.resolve(STOP_TIME_DATA))),
            dataPath = staging.resolve(STOP_TIME_DATA),
            indexPath = staging.resolve(STOP_TIME_INDEX),
        )
    }

    fun stopTimeReader(feedId: FeedId): StopTimeIndexReader? {
        val snapshot = activeSnapshot(feedId) ?: return null
        val data = snapshot.snapshotDir.resolve(STOP_TIME_DATA)
        val index = snapshot.snapshotDir.resolve(STOP_TIME_INDEX)
        if (!Files.exists(data) || !Files.exists(index)) return null
        return StopTimeIndexReader(index, data)
    }

    fun activeSnapshot(feedId: FeedId): Snapshot? {
        val pointer = feedDir(feedId).resolve(POINTER_FILE)
        if (!Files.exists(pointer)) return null
        val name = readText(pointer).trim()
        val dir = feedDir(feedId).resolve(SNAPSHOTS_DIR).resolve(name)
        val metaFile = dir.resolve(METADATA_FILE)
        if (!Files.exists(metaFile)) return null
        val meta = Files.readAllLines(metaFile).associate { line ->
            val key = line.substringBefore('=')
            val value = line.substringAfter('=', "")
            key to value
        }
        return Snapshot(
            metadata = FeedMetadata(
                feedId = FeedId(readText(dir.resolve(FEED_ID_FILE)).trim()),
                publisherName = null,
                publisherUrl = null,
                lang = null,
                version = meta["version"]?.takeIf { it.isNotEmpty() },
                hash = meta["hash"]?.takeIf { it.isNotEmpty() },
                fetchedAt = Instant.parse(meta["fetchedAt"] ?: Instant.DISTANT_PAST.toString()),
                validFrom = meta["validFrom"]?.takeIf { it.isNotEmpty() }?.let { parseBasicIsoDate(it) },
                validTo = meta["validTo"]?.takeIf { it.isNotEmpty() }?.let { parseBasicIsoDate(it) },
                sourceUrl = meta["sourceUrl"]?.takeIf { it.isNotEmpty() },
            ),
            snapshotDir = dir,
        )
    }

    /**
     * Writes a downloaded payload to the staging dir and returns its path.
     * Callers that hold the payload as a [ByteArray] should call this *before*
     * parsing so the array can be released before the uncompressed tables
     * peak. Activation then moves this file (no ByteArray copy).
     */
    @Throws(IOException::class)
    fun writeStagedPayload(payload: ByteArray): Path {
        val staging = Files.createDirectories(root.resolve(STAGING_DIR))
        val file = staging.resolve(STAGED_PAYLOAD_FILE)
        Files.write(file, payload)
        return file
    }

    /**
     * Activates from a previously staged payload file. Same atomicity as
     * [activate] but without holding the payload in memory.
     */
    @Throws(IOException::class)
    fun activateStaged(feedId: FeedId, stagedPayload: Path, metadata: FeedMetadata): Path {
        val snapshots = Files.createDirectories(feedDir(feedId).resolve(SNAPSHOTS_DIR))
        val snapshotName = "snapshot-" + System.currentTimeMillis() + "-" +
            (metadata.hash ?: "nohash").take(12)
        val staging = Files.createDirectories(snapshots.resolve(snapshotName))
        Files.move(stagedPayload, staging.resolve(PAYLOAD_FILE), StandardCopyOption.REPLACE_EXISTING)
        Files.write(staging.resolve(FEED_ID_FILE), listOf(feedId.value))
        Files.write(staging.resolve(METADATA_FILE), listOfNotNull(
            metadata.version?.let { "version=$it" },
            metadata.hash?.let { "hash=$it" },
            "fetchedAt=${metadata.fetchedAt}",
            metadata.validFrom?.let { "validFrom=${formatBasicIsoDate(it)}" },
            metadata.validTo?.let { "validTo=${formatBasicIsoDate(it)}" },
            metadata.sourceUrl?.let { "sourceUrl=$it" },
        ))
        listOf(STOP_TIME_DATA, STOP_TIME_INDEX).forEach { name ->
            val staged = root.resolve(STAGING_DIR).resolve(name)
            if (Files.exists(staged)) {
                Files.move(staged, staging.resolve(name), StandardCopyOption.REPLACE_EXISTING)
            }
        }
        val pointer = feedDir(feedId).resolve(POINTER_FILE)
        val pointerTemp = feedDir(feedId).resolve("$POINTER_FILE.tmp")
        Files.write(pointerTemp, listOf(snapshotName))
        Files.move(pointerTemp, pointer, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        prune(feedId, keepName = snapshotName)
        return staging
    }

    @Throws(IOException::class)
    fun activate(feedId: FeedId, payload: ByteArray, metadata: FeedMetadata): Path {
        val snapshots = Files.createDirectories(feedDir(feedId).resolve(SNAPSHOTS_DIR))
        val snapshotName = "snapshot-" + System.currentTimeMillis() + "-" +
            (metadata.hash ?: "nohash").take(12)
        val staging = Files.createDirectories(snapshots.resolve(snapshotName))
        Files.write(staging.resolve(PAYLOAD_FILE), payload)
        Files.write(staging.resolve(FEED_ID_FILE), listOf(feedId.value))
        Files.write(staging.resolve(METADATA_FILE), listOfNotNull(
            metadata.version?.let { "version=$it" },
            metadata.hash?.let { "hash=$it" },
            "fetchedAt=${metadata.fetchedAt}",
            metadata.validFrom?.let { "validFrom=${formatBasicIsoDate(it)}" },
            metadata.validTo?.let { "validTo=${formatBasicIsoDate(it)}" },
            metadata.sourceUrl?.let { "sourceUrl=$it" },
        ))
        // Carry the staged stop-time index into the snapshot when present.
        listOf(STOP_TIME_DATA, STOP_TIME_INDEX).forEach { name ->
            val staged = root.resolve(STAGING_DIR).resolve(name)
            if (Files.exists(staged)) {
                Files.move(staged, staging.resolve(name), StandardCopyOption.REPLACE_EXISTING)
            }
        }
        val pointer = feedDir(feedId).resolve(POINTER_FILE)
        val pointerTemp = feedDir(feedId).resolve("$POINTER_FILE.tmp")
        Files.write(pointerTemp, listOf(snapshotName))
        Files.move(pointerTemp, pointer, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        // The snapshot is live now. Anything left behind is reclaimable, and the
        // failures that leave anything behind are exactly the ones that stop the
        // process: a rejected payload, or a kill mid-refresh. Without this the store
        // grows a full copy of the feed per failed attempt and never recovers.
        prune(feedId, keepName = snapshotName)
        return staging
    }

    /**
     * Removes snapshots that are neither the active one nor a recent fallback.
     *
     * The most recent superseded snapshot is kept so a reader that resolved the
     * active pointer just before it moved still has something valid to open. Pruning
     * runs after the pointer swap, so it can never delete what the pointer names.
     */
    private fun prune(feedId: FeedId, keepName: String) {
        val snapshotsDir = feedDir(feedId).resolve(SNAPSHOTS_DIR)
        if (!Files.isDirectory(snapshotsDir)) return
        val pointerTarget = runCatching {
            val pointer = feedDir(feedId).resolve(POINTER_FILE)
            if (Files.exists(pointer)) readText(pointer).trim() else null
        }.getOrNull()
        val candidates = runCatching {
            Files.newDirectoryStream(snapshotsDir).use { stream ->
                stream.filter { Files.isDirectory(it) }.toList()
            }
        }.getOrElse { return }

        // Newest first, by the timestamp encoded in the directory name.
        val ordered = candidates.sortedByDescending { it.fileName.toString() }
        var kept = 0
        ordered.forEach { dir ->
            val name = dir.fileName.toString()
            when {
                name == keepName || name == pointerTarget -> Unit
                kept < RETAINED_SUPERSEDED_SNAPSHOTS -> kept++
                else -> runCatching { deleteTree(dir) }
            }
        }
    }

    private fun deleteTree(dir: Path) {
        if (!Files.exists(dir)) return
        Files.newDirectoryStream(dir).use { stream ->
            stream.forEach { entry ->
                if (Files.isDirectory(entry)) deleteTree(entry) else Files.deleteIfExists(entry)
            }
        }
        Files.deleteIfExists(dir)
    }

    fun readPayload(feedId: FeedId): ByteArray {
        val snapshot = activeSnapshot(feedId)
            ?: throw GtfsArchiveException("No stored GTFS snapshot for feed ${feedId.value}")
        return Files.readAllBytes(snapshot.snapshotDir.resolve(PAYLOAD_FILE))
    }

    /** Path to the stored ZIP, for file-streamed parsing without a ByteArray. */
    fun payloadPath(feedId: FeedId): Path? {
        val snapshot = activeSnapshot(feedId) ?: return null
        val path = snapshot.snapshotDir.resolve(PAYLOAD_FILE)
        return if (Files.exists(path)) path else null
    }

    fun cachePath(feedId: FeedId): Path? {
        val snapshot = activeSnapshot(feedId) ?: return null
        val path = snapshot.snapshotDir.resolve(StaticFeedBinaryCache.CACHE_FILE)
        return if (Files.exists(path)) path else null
    }

    /** Writes the binary cache for the active snapshot (cold-start fast path). */
    fun writeCache(feedId: FeedId, feed: StaticFeed) {
        val snapshot = activeSnapshot(feedId) ?: return
        runCatching { StaticFeedBinaryCache.write(feed, snapshot.snapshotDir.resolve(StaticFeedBinaryCache.CACHE_FILE)) }
    }

    private fun feedDir(feedId: FeedId): Path = root.resolve(feedId.value)

    /**
     * HTTP validators for the last activated payload.
     *
     * Kept beside the feed rather than in [FeedMetadata] so the published domain
     * type stays free of transport concerns, and so a feed that never sent an ETag
     * costs nothing.
     */
    fun validators(feedId: FeedId): FeedValidators {
        val file = feedDir(feedId).resolve(VALIDATORS_FILE)
        if (!Files.exists(file)) return FeedValidators()
        return runCatching {
            val lines = Files.readAllLines(file).filter { it.contains('=') }
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
     * Written through a temporary file and moved into place so an interrupted write
     * cannot leave a half-written validator that would later produce a mismatched
     * conditional request.
     */
    fun recordValidators(feedId: FeedId, validators: FeedValidators) {
        val dir = Files.createDirectories(feedDir(feedId))
        val target = dir.resolve(VALIDATORS_FILE)
        val temp = dir.resolve("$VALIDATORS_FILE.tmp")
        Files.write(
            temp,
            listOfNotNull(
                validators.etag?.let { "etag=$it" },
                validators.lastModified?.let { "lastModified=$it" },
            ),
        )
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
    }

    /**
     * Reads a UTF-8 file using only APIs available on Android.
     *
     * `Files.readString` is a Java 11 addition. Android ships `java.nio.file` from
     * API 26 but does not provide `readString`/`writeString` on API 34, so calling
     * it compiles against the JDK and then throws `NoSuchMethodError` on device.
     * `readAllBytes` is part of the original Java 7 API and is safe everywhere this
     * module runs.
     */
    private fun readText(path: Path): String =
        String(Files.readAllBytes(path), Charsets.UTF_8)

    companion object {
        private const val PAYLOAD_FILE = "gtfs.zip"
        private const val METADATA_FILE = "metadata.properties"
        private const val FEED_ID_FILE = "feed-id"
        private const val POINTER_FILE = "active"
        private const val VALIDATORS_FILE = "validators.properties"

        /**
         * Superseded snapshots kept after an activation.
         *
         * One is enough to cover a reader that resolved the active pointer moments
         * before it moved; more than that is disk spent on data nothing will read.
         */
        const val RETAINED_SUPERSEDED_SNAPSHOTS = 1
        private const val SNAPSHOTS_DIR = "snapshots"
        private const val STAGING_DIR = "staging-stop-times"
        private const val STOP_TIME_DATA = "stop-times.dat"
        private const val STOP_TIME_INDEX = "stop-times.idx"
        private const val STAGED_PAYLOAD_FILE = "gtfs.zip.staging"

        /** Formats a [LocalDate] as GTFS `yyyyMMdd` (formerly `BASIC_ISO_DATE`). */
        private fun formatBasicIsoDate(date: LocalDate): String =
            "%04d%02d%02d".format(date.year, date.monthNumber, date.dayOfMonth)

        /** Parses a GTFS `yyyyMMdd` date; also accepts ISO `yyyy-MM-dd` for forward compat. */
        private fun parseBasicIsoDate(value: String): LocalDate {
            if (value.contains('-')) return LocalDate.parse(value)
            val year = value.substring(0, 4).toInt()
            val month = value.substring(4, 6).toInt()
            val day = value.substring(6, 8).toInt()
            return LocalDate(year, month, day)
        }
    }
}