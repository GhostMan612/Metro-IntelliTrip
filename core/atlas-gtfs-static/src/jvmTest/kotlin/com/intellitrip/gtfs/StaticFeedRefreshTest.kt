package com.intellitrip.gtfs

import com.intellitrip.domain.FeedId
import com.intellitrip.domain.FeedMetadata
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
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
    fun conditionalRefreshReturnsNotModified() {
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
        assertIs<RefreshOutcome.NotModified>(outcome)
        assertNull(storeAt(root).activeSnapshot(feedId))
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
}
