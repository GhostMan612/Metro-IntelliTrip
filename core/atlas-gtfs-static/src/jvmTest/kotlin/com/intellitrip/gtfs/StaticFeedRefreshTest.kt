package com.intellitrip.gtfs

import com.intellitrip.domain.FeedId
import com.intellitrip.domain.FeedMetadata
import com.intellitrip.domain.StopKey
import com.intellitrip.domain.TripKey
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import kotlinx.coroutines.runBlocking

class StaticFeedRefreshTest {

    private val feedId = FeedId("metro-transit-regional")
    private val zip = GtfsFixtures.zipOf(GtfsFixtures.minimalFeed)

    private fun storeAt(root: Path) = StaticFeedSnapshotStore(PlatformFileStore(root.toString()))

    private fun acquirer(transport: GtfsFeedAcquirer.HttpTransport) =
        GtfsFeedAcquirer(transport = transport)

    @Test
    fun refreshActivatesValidatedSnapshot() {
        val root = createTempDirectory("gtfs-store")
        val transport = GtfsFeedAcquirer.HttpTransport { GtfsFeedAcquirer.HttpResponse(200, zip, mapOf("ETag" to "v1")) }
        val refresher = StaticFeedRefresher(
            feedId = feedId,
            sourceUrl = "https://svc.metrotransit.org/mtgtfs/gtfs.zip",
            acquirer = acquirer(transport),
            store = storeAt(root),
            clock = { Instant.parse("2026-10-07T00:00:00Z") },
        )

        val outcome = runBlocking { refresher.refresh() }
        assertIs<RefreshOutcome.Activated>(outcome)
        val store = storeAt(root)
        assertEquals(feedId, store.activeSnapshot(feedId)?.metadata?.feedId)
        assertTrue(zip.contentEquals(store.readPayload(feedId)))
    }

    @Test
    fun notModifiedWithoutSnapshotIsRejected() {
        val root = createTempDirectory("gtfs-store")
        val transport = GtfsFeedAcquirer.HttpTransport { GtfsFeedAcquirer.HttpResponse(304, ByteArray(0), emptyMap()) }
        val outcome = runBlocking {
            StaticFeedRefresher(
                feedId,
                "https://svc.metrotransit.org/mtgtfs/gtfs.zip",
                acquirer(transport),
                storeAt(root),
            ).refresh()
        }
        assertIs<RefreshOutcome.Rejected>(outcome)
        assertNull(storeAt(root).activeSnapshot(feedId))
    }

    @Test
    fun conditionalRefreshReturnsNotModifiedWhenASnapshotExists() {
        val root = createTempDirectory("gtfs-store")
        val store = storeAt(root)
        store.activate(
            feedId,
            zip,
            FeedMetadata(feedId, null, null, null, "v", "h", Instant.DISTANT_PAST, null, null, "src"),
        )
        val transport = GtfsFeedAcquirer.HttpTransport { GtfsFeedAcquirer.HttpResponse(304, ByteArray(0), emptyMap()) }
        val outcome = runBlocking {
            StaticFeedRefresher(feedId, "https://example/gtfs.zip", acquirer(transport), store).refresh()
        }
        assertIs<RefreshOutcome.NotModified>(outcome)
        assertEquals("h", store.activeSnapshot(feedId)?.metadata?.hash)
    }

    @Test
    fun refreshDerivesValidityFromTheNewPayloadInsteadOfInheritingTheOldFeed() {
        val root = createTempDirectory("gtfs-store")
        val store = storeAt(root)
        store.activate(
            feedId,
            zip,
            FeedMetadata(
                feedId = feedId,
                publisherName = null,
                publisherUrl = null,
                lang = null,
                version = "old-version",
                hash = "oldhash",
                fetchedAt = Instant.parse("2020-01-01T00:00:00Z"),
                validFrom = LocalDate(2020, 1, 1),
                validTo = LocalDate(2020, 12, 31),
                sourceUrl = "https://old.example/gtfs.zip",
            ),
        )
        val transport = GtfsFeedAcquirer.HttpTransport {
            GtfsFeedAcquirer.HttpResponse(
                200,
                zip,
                mapOf("ETag" to "\"new-etag\""),
            )
        }
        val outcome = runBlocking {
            StaticFeedRefresher(feedId, "https://example/gtfs.zip", acquirer(transport), store).refresh()
        }
        val activated = assertIs<RefreshOutcome.Activated>(outcome)
        assertEquals(null, activated.feed.metadata.version)
        assertEquals(LocalDate(2026, 1, 1), activated.feed.metadata.validFrom)
        assertEquals(LocalDate(2026, 12, 31), activated.feed.metadata.validTo)
        val active = store.activeSnapshot(feedId)!!.metadata
        assertEquals(LocalDate(2026, 1, 1), active.validFrom)
        assertEquals(LocalDate(2026, 12, 31), active.validTo)
    }

    @Test
    fun invalidPayloadDoesNotReplaceActiveSnapshot() {
        val root = createTempDirectory("gtfs-store")
        val store = storeAt(root)
        val metadata = FeedMetadata(feedId, null, null, null, "old", "oldhash", Instant.DISTANT_PAST, null, null, "src")
        store.activate(feedId, zip, metadata)

        val transport = GtfsFeedAcquirer.HttpTransport { GtfsFeedAcquirer.HttpResponse(200, "not-a-zip".toByteArray(), emptyMap()) }
        val outcome = runBlocking { StaticFeedRefresher(feedId, "https://example/gtfs.zip", acquirer(transport), store).refresh() }
        assertIs<RefreshOutcome.Rejected>(outcome)
        assertEquals("oldhash", store.activeSnapshot(feedId)?.metadata?.hash)
    }

    @Test
    fun conditionalHeadersAreSentWhenSnapshotExists() {
        val root = createTempDirectory("gtfs-store")
        val store = storeAt(root)
        val metadata = FeedMetadata(feedId, null, null, null, "v", "h", Instant.DISTANT_PAST, null, null, "src")
        store.activate(feedId, zip, metadata)

        var seen: GtfsFeedAcquirer.HttpRequest? = null
        val transport = GtfsFeedAcquirer.HttpTransport { request ->
            seen = request
            GtfsFeedAcquirer.HttpResponse(304, ByteArray(0), emptyMap())
        }
        runBlocking { StaticFeedRefresher(feedId, "https://example/gtfs.zip", acquirer(transport), store).refresh() }
        assertTrue(seen != null)
    }

    @Test
    fun snapshotStoreRoundTripsMetadata() {
        val root = createTempDirectory("gtfs-store")
        val store = storeAt(root)
        val metadata = FeedMetadata(
            feedId = feedId,
            publisherName = null,
            publisherUrl = null,
            lang = null,
            version = "42",
            hash = "abc",
            fetchedAt = Instant.parse("2026-10-07T00:00:00Z"),
            validFrom = LocalDate(2026, 1, 1),
            validTo = LocalDate(2026, 12, 31),
            sourceUrl = "https://example/gtfs.zip",
        )
        store.activate(feedId, zip, metadata)
        val read = store.activeSnapshot(feedId)!!.metadata
        assertEquals("42", read.version)
        assertEquals("abc", read.hash)
        assertEquals(metadata.validFrom, read.validFrom)
        assertEquals(metadata.sourceUrl, read.sourceUrl)
        assertTrue(Files.exists(root))
    }

    @Test
    fun hostileFeedIdsCannotEscapeSnapshotStorageRoot() {
        val root = createTempDirectory("gtfs-containment")
        val store = storeAt(root)
        val hostile = listOf("../escape", "..", ".", "nested/escape", "..\\escape", "bad\u0000name")
        hostile.forEach { value ->
            val id = FeedId(value)
            assertFailsWith<IllegalArgumentException>("accepted unsafe feed id: $value") {
                store.recordValidators(id, FeedValidators(etag = "attacker"))
            }
            assertFailsWith<IllegalArgumentException>("read escaped root: $value") {
                store.activeSnapshot(id)
            }
        }
        // A normal feed remains fully usable and receives its validators.
        store.recordValidators(feedId, FeedValidators(etag = "safe"))
        assertEquals("safe", store.validators(feedId).etag)
        assertFalse(Files.exists(root.parent.resolve("escape").resolve("validators.properties")))
    }

    @Test
    fun missingStopTimeIndexIsRebuiltFromTheStoredArchive() {
        val root = createTempDirectory("gtfs-store")
        val store = storeAt(root)
        val metadata = FeedMetadata(feedId, null, null, null, "v", "h", Instant.DISTANT_PAST, null, null, "src")
        store.activate(feedId, zip, metadata)
        // Activation here did not use a stop-time sink, so no index exists.
        assertNull(store.stopTimeReader(feedId))
        assertFalse(store.hasCurrentStopTimeIndex(feedId))

        assertTrue(store.reindexStopTimes(feedId))
        assertTrue(store.hasCurrentStopTimeIndex(feedId))

        val reader = store.stopTimeReader(feedId)!!
        assertTrue(reader.isCurrentFormat)
        val rows = reader.stopTimesFor(TripKey(feedId, "1001")) { id -> StopKey(feedId, id) }
        assertEquals(listOf(1, 2), rows.map { it.stopSequence })
        assertEquals(listOf("34", "39"), rows.map { it.stopKey.stopId })
    }

    @Test
    fun overlappingRefreshStagesKeepPayloadAndStopTimeIndexPaired() {
        val root = createTempDirectory("gtfs-overlapping-stages")
        val firstStore = storeAt(root)
        val secondStore = storeAt(root)
        val firstPayload = GtfsFixtures.zipOf(GtfsFixtures.minimalFeed)
        val secondFeed = GtfsFixtures.minimalFeed.toMutableMap().apply {
            this["stop_times.txt"] = """
                trip_id,arrival_time,departure_time,stop_id,stop_sequence
                1001,23:45:00,23:50:00,39,1
                1001,25:10:00,25:15:00,34,2
            """.trimIndent() + "\n"
        }
        val secondPayload = GtfsFixtures.zipOf(secondFeed)
        val firstStage = firstStore.beginRefresh()
        val secondStage = secondStore.beginRefresh()
        val firstMetadata = FeedMetadata(
            feedId, null, null, null, "first", "first-hash",
            Instant.parse("2026-10-07T00:00:00Z"), null, null, "src",
        )
        val secondMetadata = firstMetadata.copy(
            version = "second",
            hash = "second-hash",
            fetchedAt = Instant.parse("2026-10-07T00:00:01Z"),
        )

        fun stage(
            store: StaticFeedSnapshotStore,
            refreshStage: StaticFeedSnapshotStore.RefreshStage,
            payload: ByteArray,
            metadata: FeedMetadata,
        ) {
            store.writeStagedPayload(payload, refreshStage)
            val sink = store.newStopTimeSink(refreshStage)
            try {
                GtfsStaticParser.parseFromZip(feedId, payload, metadata, sink)
            } finally {
                sink.close()
            }
        }

        stage(firstStore, firstStage, firstPayload, firstMetadata)
        stage(secondStore, secondStage, secondPayload, secondMetadata)

        secondStore.activateStaged(feedId, secondStage, secondMetadata)
        assertTrue(secondPayload.contentEquals(secondStore.readPayload(feedId)))
        assertEquals(
            listOf("39", "34"),
            secondStore.stopTimeReader(feedId)!!.stopTimesFor(TripKey(feedId, "1001")) { id ->
                StopKey(feedId, id)
            }.map { it.stopKey.stopId },
        )

        firstStore.activateStaged(feedId, firstStage, firstMetadata)
        assertTrue(firstPayload.contentEquals(firstStore.readPayload(feedId)))
        assertEquals(
            listOf("34", "39"),
            firstStore.stopTimeReader(feedId)!!.stopTimesFor(TripKey(feedId, "1001")) { id ->
                StopKey(feedId, id)
            }.map { it.stopKey.stopId },
        )
    }
}
