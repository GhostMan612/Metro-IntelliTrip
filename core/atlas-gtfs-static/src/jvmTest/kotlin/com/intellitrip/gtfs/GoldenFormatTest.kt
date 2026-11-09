package com.intellitrip.gtfs

import com.intellitrip.domain.FeedId
import com.intellitrip.domain.FeedMetadata
import java.nio.file.Files
import java.nio.file.Paths
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Golden-byte checks for the two binary formats: the cache file and the
 * stop-time index must stay byte-identical to the old JVM build, so a cache
 * or index written by an earlier app version remains readable.
 *
 * Skipped when `build/golden/` does not contain the frozen fixtures (e.g. a
 * fresh `clean` run without regeneration).
 */
class GoldenFormatTest {

    private fun golden(name: String): ByteArray? {
        val dir = Paths.get("build/golden")
        if (!Files.isDirectory(dir)) return null
        val file = dir.resolve(name)
        if (!Files.exists(file)) return null
        return Files.readAllBytes(file)
    }

    private fun feed(): StaticFeed {
        val feedId = FeedId("metro-transit-regional")
        val metadata = FeedMetadata(
            feedId, null, null, null, "1", "hash",
            Instant.parse("2026-10-07T00:00:00Z"), null, null, "src",
        )
        return GtfsStaticParser.parse(feedId, GtfsFixtures.minimalFeed, metadata)
    }

    @Test
    fun binaryCacheBytesMatchGolden() {
        val expected = golden("feed.cache") ?: run {
            println("SKIPPED golden cache check: build/golden/feed.cache absent")
            return
        }
        val actual = StaticFeedBinaryCache.write(feed())
        assertEquals(expected.toList(), actual.toList(), "binary cache bytes changed")
        assertEquals(518, actual.size)
    }

    @Test
    fun stopTimeIndexBytesMatchGolden() {
        val expectedData = golden("stop-times.dat") ?: run {
            println("SKIPPED golden index check: build/golden absent")
            return
        }
        val expectedIndex = Files.readAllBytes(Paths.get("build/golden/stop-times.idx"))
        val f = feed()
        val writer = StopTimeIndexWriter()
        f.stopTimes.forEach { writer.write(it) }
        val (data, index) = writer.finish()
        assertEquals(expectedData.toList(), data.toList(), "stop-time data bytes changed")
        assertEquals(expectedIndex.toList(), index.toList(), "stop-time index bytes changed")
        assertEquals(32, data.size)
        assertEquals(143, index.size)
    }

    @Test
    fun goldenZipDecodesToTheSameTables() {
        val zip = golden("minimal.zip") ?: run {
            println("SKIPPED golden zip check: build/golden/minimal.zip absent")
            return
        }
        val files = GtfsArchive.read(zip)
        GtfsFixtures.minimalFeed.forEach { (name, content) ->
            val decoded = files[name]
            assertTrue(decoded != null, "missing table $name in golden zip decode")
            assertEquals(content.encodeToByteArray().toList(), decoded.toList(), "table $name differs")
        }
    }
}
