package com.intellitrip.gtfs

import com.intellitrip.domain.FeedId
import com.intellitrip.domain.FeedMetadata
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class StaticFeedBinaryCacheTest {
    @Test
    fun roundTripsMinimalFeed() {
        val feedId = FeedId("metro-transit-regional")
        val metadata = FeedMetadata(feedId, null, null, null, "1", "hash", Clock.System.now(), null, null, null)
        val feed = GtfsStaticParser.parse(
            feedId,
            GtfsFixtures.minimalFeed.mapValues { it.value.toByteArray() }.toMutableMap(),
            metadata,
        )
        val bytes = StaticFeedBinaryCache.write(feed)
        val read = StaticFeedBinaryCache.read(bytes)
        assertNotNull(read)
        assertEquals(feed.stops, read.stops)
        assertEquals(feed.trips, read.trips)
        assertEquals(feed.routes, read.routes)
        assertEquals(feed.agencies, read.agencies)
        assertEquals(feed.shapes, read.shapes)
        assertEquals(feed.calendars, read.calendars)
    }

    @Test
    fun corruptCacheReadsAsNull() {
        assertNotNull(StaticFeedBinaryCache.read(StaticFeedBinaryCache.write(
            GtfsStaticParser.parse(
                FeedId("metro-transit-regional"),
                GtfsFixtures.minimalFeed.mapValues { it.value.toByteArray() }.toMutableMap(),
                FeedMetadata(FeedId("metro-transit-regional"), null, null, null, "1", "hash", Instant.DISTANT_PAST, null, null, null),
            ),
        )))
        assertEquals(null, StaticFeedBinaryCache.read(byteArrayOf(1, 2, 3)))
    }
}
