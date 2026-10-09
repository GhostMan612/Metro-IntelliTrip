package com.intellitrip.gtfs

import com.intellitrip.domain.FeedId
import com.intellitrip.domain.FeedMetadata
import com.intellitrip.domain.GtfsServiceTime
import com.intellitrip.domain.ShapeKey
import com.intellitrip.domain.StopKey
import com.intellitrip.domain.TripKey
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GtfsStaticParserTest {

    private val feedId = FeedId("metro-transit-regional")
    private val metadata = FeedMetadata(feedId, null, null, null, "1", "hash", Instant.EPOCH, null, null, null)

    private fun parse(files: Map<String, String>) = GtfsStaticParser.parse(feedId, files, metadata)

    @Test
    fun parsesMinimalFeed() {
        val feed = parse(GtfsFixtures.minimalFeed)
        assertEquals(1, feed.agencies.size)
        assertEquals(1, feed.routes.size)
        assertEquals(2, feed.stops.size)
        assertEquals(1, feed.trips.size)
        assertEquals(2, feed.stopTimes.size)
    }

    @Test
    fun feedIdRemainsStableAcrossSnapshots() {
        val feed = parse(GtfsFixtures.minimalFeed)
        assertEquals(feedId, feed.routes.first().key.feedId)
        assertEquals(feedId, feed.stops.first().key.feedId)
    }

    @Test
    fun identicalIdsInDifferentFeedsDoNotCollide() {
        val a = parse(GtfsFixtures.minimalFeed)
        val b = GtfsStaticParser.parse(
            FeedId("other-feed"),
            GtfsFixtures.minimalFeed,
            FeedMetadata(FeedId("other-feed"), null, null, null, "1", "hash", Instant.EPOCH, null, null, null),
        )
        assertTrue(a.stops.first().key != b.stops.first().key)
        assertTrue(a.routes.first().key != b.routes.first().key)
    }

    @Test
    fun sharedStopKeepsSingleIdentityWithinFeed() {
        val feed = parse(GtfsFixtures.minimalFeed)
        assertEquals(StopKey(feedId, "34"), feed.stopsByKey[StopKey(feedId, "34")]?.key)
        assertEquals(1, feed.stops.count { it.key == StopKey(feedId, "34") })
    }

    @Test
    fun stopTimesPreserveTimesBeyondMidnight() {
        val feed = parse(GtfsFixtures.minimalFeed)
        val times = feed.stopTimesByTrip[TripKey(feedId, "1001")]!!
        assertEquals(GtfsServiceTime.ofSeconds(85500), times[0].arrivalTime)
        assertEquals(GtfsServiceTime.ofSeconds(90600), times[1].arrivalTime)
        assertEquals(25, times[1].arrivalTime!!.hours)
    }

    @Test
    fun stopTimesAreOrderedByStopSequence() {
        val feed = parse(GtfsFixtures.minimalFeed)
        val times = feed.stopTimesByTrip[TripKey(feedId, "1001")]!!
        assertEquals(listOf(1, 2), times.map { it.stopSequence })
    }

    @Test
    fun shapesAreOrderedBySequence() {
        val feed = parse(GtfsFixtures.minimalFeed)
        val shape = feed.shapesByKey[ShapeKey(feedId, "SH1")]
        assertNotNull(shape)
        assertEquals(44.96, shape.points.first().lat)
    }

    @Test
    fun optionalFilesMayBeAbsent() {
        val files = GtfsFixtures.minimalFeed.filterKeys {
            it !in setOf("shapes.txt", "calendar.txt", "calendar_dates.txt", "feed_info.txt", "frequencies.txt", "transfers.txt")
        }
        val feed = parse(files)
        assertTrue(feed.shapes.isEmpty())
        assertTrue(feed.calendars.isEmpty())
        assertTrue(feed.transfers.isEmpty())
    }

    @Test
    fun calendarDatesAreParsed() {
        val feed = parse(GtfsFixtures.minimalFeed)
        assertEquals(1, feed.calendarDates.size)
    }

    @Test
    fun missingRequiredFileIsRejected() {
        val files = GtfsFixtures.minimalFeed.filterKeys { it != "routes.txt" }
        assertFailsWith<GtfsValidationException> { parse(files) }
    }

    @Test
    fun unknownStopReferenceIsRejected() {
        val files = GtfsFixtures.minimalFeed.toMutableMap().apply {
            this["stop_times.txt"] = "trip_id,arrival_time,departure_time,stop_id,stop_sequence\n1001,10:00:00,10:00:00,NOPE,1\n"
        }
        assertFailsWith<GtfsValidationException> { parse(files) }
    }

    @Test
    fun invalidServiceTimeIsRejected() {
        val files = GtfsFixtures.minimalFeed.toMutableMap().apply {
            this["stop_times.txt"] = "trip_id,arrival_time,departure_time,stop_id,stop_sequence\n1001,99:99:99,99:99:99,34,1\n"
        }
        assertFailsWith<IllegalArgumentException> { parse(files) }
    }
}