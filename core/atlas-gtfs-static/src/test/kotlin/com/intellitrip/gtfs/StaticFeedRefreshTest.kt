package com.intellitrip.gtfs

import com.intellitrip.domain.FeedId
import com.intellitrip.domain.FeedMetadata
import java.nio.file.Files
import java.time.Instant
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StaticFeedRefreshTest {

    private val feedId = FeedId("metro-transit-regional")
    private val zip = GtfsFixtures.zipOf(GtfsFixtures.minimalFeed)

    private fun acquirer(transport: GtfsFeedAcquirer.HttpTransport) =
        GtfsFeedAcquirer(transport = transport)

    @Test
    fun refreshActivatesValidatedSnapshot() {
        val root = createTempDirectory("gtfs-store")
        val transport = GtfsFeedAcquirer.HttpTransport { GtfsFeedAcquirer.HttpResponse(200, zip, mapOf("ETag" to "v1")) }
        val refresher = StaticFeedRefresher(
            feedId,
            "https://svc.metrotransit.org/mtgtfs/gtfs.zip",
            acquirer(transport),
            StaticFeedSnapshotStore(root),
        ) { Instant.parse("2026-10-07T00:00:00Z") }

        val outcome = refresher.refresh()
        assertIs<RefreshOutcome.Activated>(outcome)
        val store = StaticFeedSnapshotStore(root)
        assertEquals(feedId, store.activeSnapshot(feedId)?.metadata?.feedId)
        assertTrue(zip.contentEquals(store.readPayload(feedId)))
    }

    @Test
    fun conditionalRefreshReturnsNotModified() {
        val root = createTempDirectory("gtfs-store")
        val transport = GtfsFeedAcquirer.HttpTransport { GtfsFeedAcquirer.HttpResponse(304, ByteArray(0), emptyMap()) }
        val outcome = StaticFeedRefresher(
            feedId,
            "https://svc.metrotransit.org/mtgtfs/gtfs.zip",
            acquirer(transport),
            StaticFeedSnapshotStore(root),
        ).refresh()
        assertIs<RefreshOutcome.NotModified>(outcome)
        assertNull(StaticFeedSnapshotStore(root).activeSnapshot(feedId))
    }

    @Test
    fun invalidPayloadDoesNotReplaceActiveSnapshot() {
        val root = createTempDirectory("gtfs-store")
        val store = StaticFeedSnapshotStore(root)
        val metadata = FeedMetadata(feedId, null, null, null, "old", "oldhash", Instant.EPOCH, null, null, "src")
        store.activate(feedId, zip, metadata)

        val transport = GtfsFeedAcquirer.HttpTransport { GtfsFeedAcquirer.HttpResponse(200, "not-a-zip".toByteArray(), emptyMap()) }
        val outcome = StaticFeedRefresher(feedId, "https://example/gtfs.zip", acquirer(transport), store).refresh()
        assertIs<RefreshOutcome.Rejected>(outcome)
        assertEquals("oldhash", store.activeSnapshot(feedId)?.metadata?.hash)
    }

    @Test
    fun conditionalHeadersAreSentWhenSnapshotExists() {
        val root = createTempDirectory("gtfs-store")
        val store = StaticFeedSnapshotStore(root)
        val metadata = FeedMetadata(feedId, null, null, null, "v", "h", Instant.EPOCH, null, null, "src")
        store.activate(feedId, zip, metadata)

        var seen: GtfsFeedAcquirer.HttpRequest? = null
        val transport = GtfsFeedAcquirer.HttpTransport { request ->
            seen = request
            GtfsFeedAcquirer.HttpResponse(304, ByteArray(0), emptyMap())
        }
        StaticFeedRefresher(feedId, "https://example/gtfs.zip", acquirer(transport), store).refresh()
        assertTrue(seen != null)
    }

    @Test
    fun snapshotStoreRoundTripsMetadata() {
        val root = createTempDirectory("gtfs-store")
        val store = StaticFeedSnapshotStore(root)
        val metadata = FeedMetadata(
            feedId = feedId,
            publisherName = null,
            publisherUrl = null,
            lang = null,
            version = "42",
            hash = "abc",
            fetchedAt = Instant.parse("2026-10-07T00:00:00Z"),
            validFrom = java.time.LocalDate.parse("20260101", java.time.format.DateTimeFormatter.BASIC_ISO_DATE),
            validTo = java.time.LocalDate.parse("20261231", java.time.format.DateTimeFormatter.BASIC_ISO_DATE),
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
}