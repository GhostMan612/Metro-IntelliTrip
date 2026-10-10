package com.intellitrip.gtfs

import com.intellitrip.domain.FeedId
import com.intellitrip.domain.FeedMetadata
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter

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
        val name = Files.readString(pointer).trim()
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
                feedId = FeedId(Files.readString(dir.resolve(FEED_ID_FILE)).trim()),
                publisherName = null,
                publisherUrl = null,
                lang = null,
                version = meta["version"]?.takeIf { it.isNotEmpty() },
                hash = meta["hash"]?.takeIf { it.isNotEmpty() },
                fetchedAt = Instant.parse(meta["fetchedAt"] ?: Instant.EPOCH.toString()),
                validFrom = meta["validFrom"]?.takeIf { it.isNotEmpty() }?.let { LocalDate.parse(it, DATE_FORMAT) },
                validTo = meta["validTo"]?.takeIf { it.isNotEmpty() }?.let { LocalDate.parse(it, DATE_FORMAT) },
                sourceUrl = meta["sourceUrl"]?.takeIf { it.isNotEmpty() },
            ),
            snapshotDir = dir,
        )
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
            metadata.validFrom?.let { "validFrom=${it.format(DATE_FORMAT)}" },
            metadata.validTo?.let { "validTo=${it.format(DATE_FORMAT)}" },
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
        return staging
    }

    fun readPayload(feedId: FeedId): ByteArray {
        val snapshot = activeSnapshot(feedId)
            ?: throw GtfsArchiveException("No stored GTFS snapshot for feed ${feedId.value}")
        return Files.readAllBytes(snapshot.snapshotDir.resolve(PAYLOAD_FILE))
    }

    private fun feedDir(feedId: FeedId): Path = root.resolve(feedId.value)

    companion object {
        private const val PAYLOAD_FILE = "gtfs.zip"
        private const val METADATA_FILE = "metadata.properties"
        private const val FEED_ID_FILE = "feed-id"
        private const val POINTER_FILE = "active"
        private const val SNAPSHOTS_DIR = "snapshots"
        private const val STAGING_DIR = "staging-stop-times"
        private const val STOP_TIME_DATA = "stop-times.dat"
        private const val STOP_TIME_INDEX = "stop-times.idx"
        private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.BASIC_ISO_DATE
    }
}