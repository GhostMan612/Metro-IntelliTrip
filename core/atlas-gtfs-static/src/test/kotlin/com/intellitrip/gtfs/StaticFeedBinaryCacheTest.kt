package com.intellitrip.gtfs

import com.intellitrip.domain.FeedId
import com.intellitrip.domain.FeedMetadata
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class StaticFeedBinaryCacheTest {
    @Test
    fun roundTripsMinimalFeed() {
        val root = Files.createTempDirectory("cache-test")
        val feedId = FeedId("metro-transit-regional")
        val metadata = FeedMetadata(feedId, null, null, null, "1", "hash", Instant.now(), null, null, null)
        val feed = GtfsStaticParser.parse(
            feedId,
            GtfsFixtures.minimalFeed.mapValues { it.value.toByteArray() }.toMutableMap(),
            metadata,
        )
        val path = root.resolve("feed.cache")
        StaticFeedBinaryCache.write(feed, path)
        val read = StaticFeedBinaryCache.read(path)
        assertNotNull(read)
        assertEquals(feed.stops, read.stops)
        assertEquals(feed.trips, read.trips)
        assertEquals(feed.routes, read.routes)
        assertEquals(feed.agencies, read.agencies)
        assertEquals(feed.shapes, read.shapes)
        assertEquals(feed.calendars, read.calendars)
    }
}
