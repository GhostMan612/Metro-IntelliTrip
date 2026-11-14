package com.intellitrip.gtfs

import com.intellitrip.domain.FeedId
import com.intellitrip.domain.GtfsServiceTime
import com.intellitrip.domain.StopKey
import com.intellitrip.domain.StopTime
import com.intellitrip.domain.TripKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
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
        val writer = StopTimeIndexWriter()
        rows.forEach { writer.write(it) }
        val (data, index) = writer.finish()
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
        val reader = StopTimeIndexReader(ByteArray(0), ByteArray(0))
        assertTrue(reader.stopTimesFor(TripKey(feedId, "T1"), stopKeyOf).isEmpty())
        assertEquals(0, reader.tripCount())
    }

    @Test
    fun preservesBoardingAndAlightingRestrictions() {
        val writer = StopTimeIndexWriter()
        writer.write(stopTime("T1", 1, "S1", 3600).copy(pickupType = 1, dropOffType = 0))
        writer.write(stopTime("T1", 2, "S2", 3660).copy(pickupType = 0, dropOffType = 1))
        writer.write(stopTime("T1", 3, "S3", 3720))
        val (data, index) = writer.finish()

        val rows = StopTimeIndexReader(index, data).stopTimesFor(TripKey(feedId, "T1"), stopKeyOf)

        assertEquals(listOf(1, 0), listOf(rows[0].pickupType, rows[0].dropOffType))
        assertEquals(listOf(0, 1), listOf(rows[1].pickupType, rows[1].dropOffType))
        // Absent in the feed stays absent, not silently "allowed".
        assertNull(rows[2].pickupType)
        assertNull(rows[2].dropOffType)
    }

    @Test
    fun writesCurrentFormatMagic() {
        val writer = StopTimeIndexWriter()
        writer.write(stopTime("T1", 1, "S1", 3600))
        val (_, index) = writer.finish()

        assertTrue(StopTimeIndexReader.isCurrentFormat(index))
        assertFalse(StopTimeIndexReader.isLegacyFormat(index))
    }

    @Test
    fun unknownFormatIsRejected() {
        val bogus = "NOPE0001".encodeToByteArray() + ByteArray(32)
        assertFailsWith<StopTimeIndexFormatException> {
            StopTimeIndexReader(bogus, ByteArray(32))
        }
    }

    @Test
    fun dataLengthMismatchIsRejected() {
        val writer = StopTimeIndexWriter()
        writer.write(stopTime("T1", 1, "S1", 3600))
        val (data, index) = writer.finish()

        // A half-published generation, or a data file from another format,
        // must fail closed instead of being parsed at the wrong stride.
        val reader = StopTimeIndexReader(index, data.copyOf(data.size - 1))
        assertFailsWith<StopTimeIndexFormatException> { reader.tripCount() }
    }

    @Test
    fun legacyIndexStillReadsWithNullRestrictions() {
        val rows = listOf(stopTime("T1", 1, "S1", 3600), stopTime("T1", 2, "S2", 3660))
        val (data, index) = legacyV2Index("T1", rows)

        val reader = StopTimeIndexReader(index, data)

        assertFalse(reader.isCurrentFormat, "a 002 index must report that it lacks restrictions")
        val read = reader.stopTimesFor(TripKey(feedId, "T1"), stopKeyOf).associateBy { it.stopSequence }
        assertEquals(2, read.size)
        assertEquals(3600, read[1]?.arrivalTime?.secondsSinceServiceDayStart)
        assertNull(read[1]?.pickupType)
        assertNull(read[1]?.dropOffType)
    }

    @Test
    fun tripsServingSupportsContainmentAndRepeatedLookupWithoutRecomputing() {
        val reader = write(
            stopTime("T1", 1, "S1", 3600),
            stopTime("T1", 2, "S1", 3630), // duplicate visit at same stop
            stopTime("T2", 1, "S1", 7200),
            stopTime("T3", 1, "S2", 7500),
        )
        val atS1 = reader.tripsServing(StopKey(feedId, "S1"))
        val atS2 = reader.tripsServing(StopKey(feedId, "S2"))
        assertEquals(setOf(TripKey(feedId, "T1"), TripKey(feedId, "T2")), atS1)
        assertEquals(setOf(TripKey(feedId, "T3")), atS2)
        assertTrue(TripKey(feedId, "T1") in atS1)
        assertFalse(TripKey(feedId, "T3") in atS1)
        assertEquals(3, reader.tripCount())
        assertEquals(2, reader.stopTimesFor(TripKey(feedId, "T1"), stopKeyOf).size)
    }

    @Test
    fun v3IndexStillReadsWithRestrictionsAndTriggersUpgrade() {
        val rows = listOf(
            stopTime("T1", 1, "S1", 3600).copy(pickupType = 0, dropOffType = 1),
            stopTime("T1", 2, "S2", 3660).copy(pickupType = 1, dropOffType = 0),
        )
        val (data, index) = legacyV3Index("T1", rows)

        val reader = StopTimeIndexReader(index, data)

        assertFalse(reader.isCurrentFormat, "a 003 index must report false so hosts upgrade to 004")
        assertFalse(StopTimeIndexReader.isCurrentFormat(index))
        assertFalse(StopTimeIndexReader.isLegacyFormat(index))
        assertEquals(setOf(TripKey(feedId, "T1")), reader.tripsServing(StopKey(feedId, "S1")))
        val read = reader.stopTimesFor(TripKey(feedId, "T1"), stopKeyOf).associateBy { it.stopSequence }
        assertEquals(2, read.size)
        assertEquals(0, read[1]?.pickupType)
        assertEquals(1, read[1]?.dropOffType)
        assertEquals(1, read[2]?.pickupType)
        assertEquals(0, read[2]?.dropOffType)
    }

    @Test
    fun corruptV4OrdinalIsRejected() {
        val writer = StopTimeIndexWriter()
        writer.write(stopTime("T1", 1, "S1", 3600))
        val (data, index) = writer.finish()
        // Overwrite the single 4-byte ordinal at the tail of the v4 index with an out-of-range int.
        val corrupted = index.copyOf()
        val tail = corrupted.size - 4
        corrupted[tail] = 0x00
        corrupted[tail + 1] = 0x00
        corrupted[tail + 2] = 0x01
        corrupted[tail + 3] = 0x00 // 256 >= tripCount (1)
        val reader = StopTimeIndexReader(corrupted, data)
        assertFailsWith<StopTimeIndexFormatException> {
            reader.tripsServing(StopKey(feedId, "S1"))
        }
    }

    /** Builds a `STIDX003` layout (restrictions + string reverse keys) by hand. */
    private fun legacyV3Index(tripId: String, rows: List<StopTime>): Pair<ByteArray, ByteArray> {
        val data = GrowableBytes()
        rows.forEach { row ->
            val stopBytes = row.stopKey.stopId.encodeToByteArray()
            data.writeInt(row.stopSequence)
            data.writeInt(row.arrivalTime!!.secondsSinceServiceDayStart)
            data.writeInt(row.departureTime!!.secondsSinceServiceDayStart)
            data.writeByte(row.pickupType ?: StopTimeIndexWriter.NO_VALUE)
            data.writeByte(row.dropOffType ?: StopTimeIndexWriter.NO_VALUE)
            data.writeShort(stopBytes.size)
            data.writeBytes(stopBytes)
        }
        val dataBytes = data.toByteArray()
        val index = GrowableBytes()
        index.writeBytes("STIDX003".encodeToByteArray())
        index.writeLong(dataBytes.size.toLong())
        index.writeInt(1) // trips
        index.writeLong(rows.size.toLong())
        val key = "metro-transit-regional|$tripId".encodeToByteArray()
        index.writeShort(key.size)
        index.writeBytes(key)
        index.writeInt(1) // segments
        index.writeLong(0L)
        index.writeInt(rows.size)
        index.writeInt(1) // stops
        val stopBytes = rows.first().stopKey.stopId.encodeToByteArray()
        index.writeShort(stopBytes.size)
        index.writeBytes(stopBytes)
        index.writeInt(1) // trips at this stop
        index.writeShort(key.size)
        index.writeBytes(key)
        return dataBytes to index.toByteArray()
    }

    /** Builds the pre-restriction `STIDX002` layout by hand. */
    private fun legacyV2Index(tripId: String, rows: List<StopTime>): Pair<ByteArray, ByteArray> {
        val data = GrowableBytes()
        rows.forEach { row ->
            val stopBytes = row.stopKey.stopId.encodeToByteArray()
            data.writeInt(row.stopSequence)
            data.writeInt(row.arrivalTime!!.secondsSinceServiceDayStart)
            data.writeInt(row.departureTime!!.secondsSinceServiceDayStart)
            data.writeShort(stopBytes.size)
            data.writeBytes(stopBytes)
        }
        val index = GrowableBytes()
        index.writeBytes("STIDX002".encodeToByteArray())
        index.writeInt(1) // trips
        index.writeLong(rows.size.toLong())
        val key = "metro-transit-regional|$tripId".encodeToByteArray()
        index.writeShort(key.size)
        index.writeBytes(key)
        index.writeInt(1) // segments
        index.writeLong(0L)
        index.writeInt(rows.size)
        index.writeInt(1) // stops
        val stopBytes = rows.first().stopKey.stopId.encodeToByteArray()
        index.writeShort(stopBytes.size)
        index.writeBytes(stopBytes)
        index.writeInt(1) // trips at this stop
        index.writeShort(key.size)
        index.writeBytes(key)
        return data.toByteArray() to index.toByteArray()
    }
}
