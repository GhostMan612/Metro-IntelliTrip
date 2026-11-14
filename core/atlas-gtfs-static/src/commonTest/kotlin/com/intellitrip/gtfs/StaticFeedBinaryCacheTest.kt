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

    @Test
    fun includeShapesFalseSkipsShapesWhilePreservingRoutingTables() {
        val feedId = FeedId("metro-transit-regional")
        val metadata = FeedMetadata(feedId, null, null, null, "1", "hash", Clock.System.now(), null, null, null)
        val feedWithShape = GtfsStaticParser.parse(
            feedId,
            (GtfsFixtures.minimalFeed + mapOf(
                "shapes.txt" to "shape_id,shape_pt_lat,shape_pt_lon,shape_pt_sequence\nSH1,44.97,-93.26,1\nSH1,44.98,-93.27,2\n",
            )).mapValues { it.value.toByteArray() }.toMutableMap(),
            metadata,
        )
        assertEquals(1, feedWithShape.shapes.size)
        val bytes = StaticFeedBinaryCache.write(feedWithShape)
        assertEquals(StaticFeedBinaryCache.VERSION, StaticFeedBinaryCache.versionOf(bytes))

        val withoutShapes = StaticFeedBinaryCache.read(bytes, includeShapes = false)
        assertNotNull(withoutShapes)
        assertEquals(emptyList(), withoutShapes.shapes)
        assertEquals(feedWithShape.stops, withoutShapes.stops)
        assertEquals(feedWithShape.trips, withoutShapes.trips)
        assertEquals(feedWithShape.routes, withoutShapes.routes)
        assertEquals(feedWithShape.calendars, withoutShapes.calendars)
        assertEquals(feedWithShape.transfers, withoutShapes.transfers)
    }
}
