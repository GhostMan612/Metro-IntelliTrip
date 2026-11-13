package com.intellitrip.gtfs

import com.intellitrip.contracts.platformDurablePut
import com.intellitrip.domain.FeedId
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

/**
 * Reload simulation for the browser durable cache.
 *
 * JVM is used because the durable seam is in-memory here but process-global,
 * which is exactly the shape of an IndexedDB reload: a fresh snapshot store
 * with no files, plus the payload/index bytes from the previous run.
 */
class DurableRehydrationTest {

    private val sourceUrl = "https://example.invalid/gtfs.zip"
    private val zip = GtfsFixtures.zipOf(GtfsFixtures.minimalFeed)

    private fun storeAt(root: Path) = StaticFeedSnapshotStore(PlatformFileStore(root.toString()))

    private fun refresher(
        feedId: FeedId,
        store: StaticFeedSnapshotStore,
        clock: Instant,
        transport: GtfsFeedAcquirer.HttpTransport = GtfsFeedAcquirer.HttpTransport {
            error("network must not be touched during rehydration")
        },
    ) = StaticFeedRefresher(
        feedId = feedId,
        sourceUrl = sourceUrl,
        acquirer = GtfsFeedAcquirer(transport = transport),
        store = store,
        clock = { clock },
    )

    @Test
    fun reloadRestoresPayloadAndPreservesOriginalFetchTime() = runBlocking {
        val feedId = FeedId("rehydrate-happy")
        val fetchedAt = Instant.parse("2026-11-11T06:00:00Z")
        val first = refresher(
            feedId = feedId,
            store = storeAt(createTempDirectory("rehydrate-first")),
            clock = fetchedAt,
            transport = GtfsFeedAcquirer.HttpTransport {
                GtfsFeedAcquirer.HttpResponse(
                    status = 200,
                    body = zip,
                    headers = mapOf("ETag" to "\"v1\""),
                )
            },
        )
        assertIs<RefreshOutcome.Activated>(first.refresh())

        // New process: no snapshot files, same durable payload/index.
        val secondStore = storeAt(createTempDirectory("rehydrate-second"))
        val restored = refresher(
            feedId = feedId,
            store = secondStore,
            clock = fetchedAt + 2.hours,
        ).rehydrateCached()

        assertNotNull(restored)
        assertEquals(fetchedAt, restored.feed.metadata.fetchedAt)
        assertEquals(2.hours, restored.age)
        assertFalse(restored.stale)
        assertEquals(GtfsFeedAcquirer.sha256(zip), restored.feed.metadata.hash)
        assertEquals(1, restored.feed.routes.size)
        assertNotNull(secondStore.activeSnapshot(feedId))
        assertTrue(zip.contentEquals(secondStore.readPayload(feedId)))
    }

    @Test
    fun corruptPayloadIsRejectedAndDoesNotActivate() = runBlocking {
        val feedId = FeedId("rehydrate-corrupt")
        val fetchedAt = Instant.parse("2026-11-11T06:00:00Z")
        val first = refresher(
            feedId = feedId,
            store = storeAt(createTempDirectory("rehydrate-corrupt-first")),
            clock = fetchedAt,
            transport = GtfsFeedAcquirer.HttpTransport {
                GtfsFeedAcquirer.HttpResponse(200, zip, mapOf("ETag" to "\"v1\""))
            },
        )
        assertIs<RefreshOutcome.Activated>(first.refresh())

        val hash = GtfsFeedAcquirer.sha256(zip)
        val corrupt = zip.copyOf()
        corrupt[corrupt.size / 2] = (corrupt[corrupt.size / 2].toInt() xor 0xFF).toByte()
        platformDurablePut(StaticFeedRefresher.durablePayloadKey(feedId, hash), corrupt)

        val secondStore = storeAt(createTempDirectory("rehydrate-corrupt-second"))
        val restored = refresher(
            feedId = feedId,
            store = secondStore,
            clock = fetchedAt + 1.hours,
        ).rehydrateCached()

        assertNull(restored)
        assertNull(secondStore.activeSnapshot(feedId))
    }

    @Test
    fun oldCacheIsLabelledStaleButStillReturned() = runBlocking {
        val feedId = FeedId("rehydrate-stale")
        val fetchedAt = Instant.parse("2026-10-01T06:00:00Z")
        val first = refresher(
            feedId = feedId,
            store = storeAt(createTempDirectory("rehydrate-stale-first")),
            clock = fetchedAt,
            transport = GtfsFeedAcquirer.HttpTransport {
                GtfsFeedAcquirer.HttpResponse(200, zip, emptyMap())
            },
        )
        assertIs<RefreshOutcome.Activated>(first.refresh())

        val restored = refresher(
            feedId = feedId,
            store = storeAt(createTempDirectory("rehydrate-stale-second")),
            clock = fetchedAt + 10.days,
        ).rehydrateCached()

        assertNotNull(restored)
        assertTrue(restored.stale)
        assertEquals(10.days, restored.age)
    }

    @Test
    fun aDifferentActiveSnapshotIsNotOverwrittenByCache() = runBlocking {
        val feedId = FeedId("rehydrate-active-wins")
        val fetchedAt = Instant.parse("2026-11-11T06:00:00Z")
        val first = refresher(
            feedId = feedId,
            store = storeAt(createTempDirectory("rehydrate-active-first")),
            clock = fetchedAt,
            transport = GtfsFeedAcquirer.HttpTransport {
                GtfsFeedAcquirer.HttpResponse(200, zip, emptyMap())
            },
        )
        assertIs<RefreshOutcome.Activated>(first.refresh())

        // A new process whose store already has a different snapshot.
        val secondStore = storeAt(createTempDirectory("rehydrate-active-second"))
        val otherMetadata = com.intellitrip.domain.FeedMetadata(
            feedId = feedId,
            publisherName = null,
            publisherUrl = null,
            lang = null,
            version = "newer",
            hash = "a-different-hash",
            fetchedAt = Instant.parse("2026-11-12T00:00:00Z"),
            validFrom = null,
            validTo = null,
            sourceUrl = sourceUrl,
        )
        secondStore.activate(feedId, zip, otherMetadata)

        val restored = refresher(
            feedId = feedId,
            store = secondStore,
            clock = fetchedAt + 1.hours,
        ).rehydrateCached()

        assertNull(restored)
        assertEquals("a-different-hash", secondStore.activeSnapshot(feedId)?.metadata?.hash)
    }
}
