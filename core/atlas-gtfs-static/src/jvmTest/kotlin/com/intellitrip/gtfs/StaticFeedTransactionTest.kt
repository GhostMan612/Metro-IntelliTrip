package com.intellitrip.gtfs

import com.intellitrip.domain.FeedId
import com.intellitrip.domain.FeedMetadata
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.datetime.Instant

/**
 * Two refreshes may overlap. Each stage owns its payload and stop-time index;
 * activation must never combine one attempt's archive with another's index.
 */
class StaticFeedTransactionTest {

    private val feedId = FeedId("transaction-test")

    private fun storeAt(root: Path) = StaticFeedSnapshotStore(PlatformFileStore(root.toString()))

    private fun metadata(hash: String) = FeedMetadata(
        feedId = feedId,
        publisherName = null,
        publisherUrl = null,
        lang = null,
        version = null,
        hash = hash,
        fetchedAt = Instant.parse("2026-10-07T00:00:00Z"),
        validFrom = null,
        validTo = null,
        sourceUrl = "https://example/gtfs.zip",
    )

    private fun parseInto(
        store: StaticFeedSnapshotStore,
        stage: StaticFeedSnapshotStore.RefreshStage,
        payload: ByteArray,
        metadata: FeedMetadata,
    ): StaticFeed {
        val sink = store.newStopTimeSink(stage)
        return try {
            GtfsStaticParser.parseFromZip(feedId, payload, metadata, sink)
        } finally {
            sink.close()
        }
    }

    @Test
    fun overlappingStagesNeverMixPayloadAndStopTimeIndex() {
        val root = createTempDirectory("gtfs-transaction")
        val store = storeAt(root)

        val filesA = GtfsFixtures.minimalFeed
        val filesB = GtfsFixtures.minimalFeed + mapOf(
            "stops.txt" to """
                stop_id,stop_name,stop_lat,stop_lon
                34,Chicago Ave,44.97,-93.26
                39,17th St,44.96,-93.27
                40,Franklin Ave,44.955,-93.28
            """.trimIndent() + "\n",
            "stop_times.txt" to """
                trip_id,arrival_time,departure_time,stop_id,stop_sequence
                1001,23:45:00,23:50:00,34,1
                1001,25:10:00,25:15:00,39,2
                1001,25:20:00,25:21:00,40,3
            """.trimIndent() + "\n",
        )
        val payloadA = GtfsFixtures.zipOf(filesA)
        val payloadB = GtfsFixtures.zipOf(filesB)

        val stageA = store.beginRefresh()
        store.writeStagedPayload(payloadA, stageA)
        parseInto(store, stageA, payloadA, metadata("hash-a"))

        val stageB = store.beginRefresh()
        store.writeStagedPayload(payloadB, stageB)
        parseInto(store, stageB, payloadB, metadata("hash-b"))

        // B finishes first; A is still in flight. Activating B must not delete
        // A's stage, and activating A afterwards must use A's own rows.
        store.activateStaged(feedId, stageB, metadata("hash-b"))
        assertEquals(3, store.stopTimeReader(feedId)!!.rowCount())

        store.activateStaged(feedId, stageA, metadata("hash-a"))
        val active = store.activeSnapshot(feedId)!!
        assertEquals("hash-a", active.metadata.hash)
        assertTrue(payloadA.contentEquals(store.readPayload(feedId)))
        assertEquals(2, store.stopTimeReader(feedId)!!.rowCount())
    }
}
