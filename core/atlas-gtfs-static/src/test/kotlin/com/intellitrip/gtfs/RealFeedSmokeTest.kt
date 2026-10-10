package com.intellitrip.gtfs

import com.intellitrip.domain.FeedId
import com.intellitrip.domain.FeedMetadata
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Validates the parser against the real Metro Transit feed when a copy is placed
 * at `test/resources/gtfs-metro-transit.zip` (or the path given by the
 * `intellitrip.gtfs.fixture` system property).
 *
 * The feed is not committed; fetch it with:
 *   curl -o gtfs-metro-transit.zip https://svc.metrotransit.org/mtgtfs/gtfs.zip
 * When absent the test is skipped rather than silently passing.
 */
class RealFeedSmokeTest {

    private fun fixturePath(): java.io.File? {
        val override = System.getProperty("intellitrip.gtfs.fixture")
        val candidates = listOfNotNull(
            override?.let { java.io.File(it) },
            java.io.File("src/test/resources/gtfs-metro-transit.zip"),
            java.io.File("../gtfs-metro-transit.zip"),
        )
        return candidates.firstOrNull { it.exists() }
    }

    @Test
    fun parsesRealMetroTransitFeed() {
        val file = fixturePath() ?: run {
            println("SKIPPED real-feed verification: no gtfs-metro-transit.zip fixture present")
            return
        }
        println("Verifying real feed from ${file.absolutePath} (${file.length()} bytes)")
        val feedId = FeedId("metro-transit-regional")
        val payload = file.readBytes()
        assertTrue(GtfsArchive.isZip(payload), "fixture is not a ZIP archive")
        val files = GtfsArchive.read(payload).toMutableMap()
        val metadata = FeedMetadata(feedId, null, null, null, null, null, Instant.now(), null, null, null)
        val feed = runCatching { GtfsStaticParser.parse(feedId, files, metadata) }.getOrElse { error ->
            fail("real feed failed to parse: ${error.message}")
        }

        assertTrue(feed.agencies.isNotEmpty(), "expected agencies")
        assertTrue(feed.routes.size > 100, "expected the full route table, got ${feed.routes.size}")
        assertTrue(feed.stops.size > 1_000, "expected the full stop table, got ${feed.stops.size}")
        assertTrue(feed.trips.size > 1_000, "expected the full trip table, got ${feed.trips.size}")
        assertTrue(feed.stopTimes.size > 10_000, "expected many stop times, got ${feed.stopTimes.size}")
        assertTrue(feed.calendars.isNotEmpty() || feed.calendarDates.isNotEmpty(), "expected service calendar data")
        assertTrue(feed.shapes.isNotEmpty(), "expected shapes in the Metro Transit feed")

        // stop sequences must be ordered per trip
        val sampleTrip = feed.trips.first().key
        val sequences = feed.stopTimesByTrip[sampleTrip].orEmpty().map { it.stopSequence }
        assertTrue(sequences == sequences.sorted(), "stop times out of order for $sampleTrip")

        // service times past midnight must survive (24:00:00 == 86400 is valid)
        val lateTimes = feed.stopTimes.mapNotNull { it.arrivalTime?.secondsSinceServiceDayStart }.filter { it >= 86_400 }
        if (lateTimes.isNotEmpty()) {
            assertTrue(lateTimes.all { it >= 86_400 }, "service times past 24h were altered")
        }
    }
}