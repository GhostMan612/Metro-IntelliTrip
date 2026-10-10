package com.intellitrip.gtfs

import com.intellitrip.domain.FeedId
import com.intellitrip.domain.GtfsServiceTime
import com.intellitrip.domain.StopKey
import com.intellitrip.domain.StopTime
import com.intellitrip.domain.TripKey
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StopTimeIndexTest {

    private val feedId = FeedId("metro-transit-regional")
    private val stopKeyOf = { id: String -> StopKey(feedId, id) }

    private fun stopTime(tripId: String, seq: Int, stopId: String, seconds: Int) = StopTime(
        tripKey = TripKey(feedId, tripId),
        stopKey = StopKey(feedId, stopId),
        stopSequence = seq,
        arrivalTime = GtfsServiceTime.ofSeconds(seconds),
        departureTime = GtfsServiceTime.ofSeconds(seconds + 30),
        pickupType = null,
        dropOffType = null,
        timepoint = null,
        shapeDistTraveled = null,
    )

    private fun write(vararg rows: StopTime): StopTimeIndexReader {
        val root = createTempDirectory("stop-time-index")
        val data = root.resolve("stop-times.dat")
        val index = root.resolve("stop-times.idx")
        Files.newOutputStream(data).use { out ->
            StopTimeIndexWriter(out).use { writer ->
                rows.forEach { writer.write(it) }
                writer.finish(index)
            }
        }
        return StopTimeIndexReader(index, data)
    }

    @Test
    fun roundTripsStopTimesForATrip() {
        val reader = write(
            stopTime("T1", 1, "S1", 3600),
            stopTime("T1", 2, "S2", 3660),
            stopTime("T1", 3, "S3", 3720),
        )
        val rows = reader.stopTimesFor(TripKey(feedId, "T1"), stopKeyOf)
        assertEquals(3, rows.size)
        assertEquals(listOf(1, 2, 3), rows.map { it.stopSequence })
        assertEquals(StopKey(feedId, "S1"), rows[0].stopKey)
        assertEquals(3600, rows[0].arrivalTime?.secondsSinceServiceDayStart)
    }

    @Test
    fun keepsServiceTimesBeyondMidnight() {
        val reader = write(stopTime("T1", 1, "S1", 91800)) // 25:30
        val rows = reader.stopTimesFor(TripKey(feedId, "T1"), stopKeyOf)
        assertEquals(91800, rows.single().arrivalTime?.secondsSinceServiceDayStart)
    }

    @Test
    fun handlesSeveralTrips() {
        val reader = write(
            stopTime("T1", 1, "S1", 3600),
            stopTime("T2", 1, "S2", 7200),
            stopTime("T1", 2, "S3", 3660),
        )
        assertEquals(2, reader.tripCount())
        assertEquals(3, reader.rowCount())
        // rows for the same trip stay contiguous even when interleaved
        assertEquals(2, reader.stopTimesFor(TripKey(feedId, "T1"), stopKeyOf).size)
        assertEquals(1, reader.stopTimesFor(TripKey(feedId, "T2"), stopKeyOf).size)
    }

    @Test
    fun unknownTripReturnsEmpty() {
        val reader = write(stopTime("T1", 1, "S1", 3600))
        assertTrue(reader.stopTimesFor(TripKey(feedId, "MISSING"), stopKeyOf).isEmpty())
    }

    @Test
    fun missingFilesReadAsEmpty() {
        val root = createTempDirectory("stop-time-index-missing")
        val reader = StopTimeIndexReader(root.resolve("a.idx"), root.resolve("a.dat"))
        assertTrue(reader.stopTimesFor(TripKey(feedId, "T1"), stopKeyOf).isEmpty())
        assertEquals(0, reader.tripCount())
    }
}
