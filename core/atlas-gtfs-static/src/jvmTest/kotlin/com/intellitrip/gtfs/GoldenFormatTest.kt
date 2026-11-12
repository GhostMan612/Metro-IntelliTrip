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
 * Golden-byte checks for the binary cache and the archive fixtures. The
 * stop-time index is no longer byte-compatible with the pre-restriction
 * build on purpose: `STIDX003` adds pickup/drop-off, so it is checked by
 * round trip and magic instead (see [StopTimeIndexTest]).
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
    fun stopTimeIndexWritesCurrentFormat() {
        val f = feed()
        val writer = StopTimeIndexWriter()
        f.stopTimes.forEach { writer.write(it) }
        val (data, index) = writer.finish()
        assertTrue(StopTimeIndexReader.isCurrentFormat(index), "writer must emit STIDX003")
        val reader = StopTimeIndexReader(index, data)
        assertEquals(f.stopTimes.size, reader.rowCount())
        assertEquals(f.stopTimes.size, reader.stopTimesFor(f.stopTimes.first().tripKey) { id -> com.intellitrip.domain.StopKey(f.feedId, id) }.size)
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
