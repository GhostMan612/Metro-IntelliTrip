package com.intellitrip.gtfs

import com.intellitrip.domain.FeedId
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Dev-only seed: writes a full snapshot of the real feed to build/snapshot-seed
 * so the BLU can verify without downloading/parsing on-device (CPU-bound).
 * Run with -Dintellitrip.gtfs.fixture=<abs path to gtfs zip>.
 */
class SnapshotSeedTest {
    @Test
    fun seedRealFeedSnapshot() {
        val fixture = System.getProperty("intellitrip.gtfs.fixture") ?: return
        val file = java.io.File(fixture)
        if (!file.exists()) return
        val feedId = FeedId("metro-transit-regional")
        val payload = file.readBytes()
        assertTrue(GtfsArchive.isZip(payload))
        val out: Path = java.nio.file.Paths.get("build/snapshot-seed")
        Files.createDirectories(out)
        val store = StaticFeedSnapshotStore(out)
        val transport = GtfsFeedAcquirer.HttpTransport {
            GtfsFeedAcquirer.HttpResponse(200, payload, mapOf("ETag" to "seed"))
        }
        val refresher = StaticFeedRefresher(
            feedId = feedId,
            sourceUrl = "https://svc.metrotransit.org/mtgtfs/gtfs.zip",
            acquirer = GtfsFeedAcquirer(transport = transport),
            store = store,
            clock = { Instant.now() },
        )
        val outcome = runBlocking { refresher.refresh() }
        println("seed outcome: $outcome")
        assertTrue(outcome is RefreshOutcome.Activated, "seed failed: $outcome")
        println("seed written to ${out.toAbsolutePath()}")
    }
}
