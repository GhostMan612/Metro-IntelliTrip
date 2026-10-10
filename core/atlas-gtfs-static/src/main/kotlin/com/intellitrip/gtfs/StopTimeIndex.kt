package com.intellitrip.gtfs

import com.intellitrip.domain.GtfsServiceTime
import com.intellitrip.domain.StopKey
import com.intellitrip.domain.StopTime
import com.intellitrip.domain.TripKey
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * Compact, disk-backed index over `stop_times.txt`.
 *
 * The Twin Cities feed carries roughly 850k stop times. Retaining them as
 * objects exhausted an Android heap, so rows are written once at snapshot time
 * and read back per trip on demand. The parser streams into this sink without
 * ever holding the full table.
 */
class StopTimeIndexWriter(private val output: OutputStream) : AutoCloseable {

    private val data = DataOutputStream(BufferedOutputStream(output, 1 shl 16))
    private val segments = LinkedHashMap<TripKey, MutableList<Segment>>()
    private var offset = 0L
    private var written = 0L

    // Only one trip's rows are buffered at a time, so records for a trip are
    // always contiguous regardless of the order the feed lists them in.
    private var pendingTrip: TripKey? = null
    private val pending = ArrayList<StopTime>(128)

    // Reverse index: which trips serve a stop. Bounded by the stop count and it
    // lets the planner consider only trips near the origin instead of every trip.
    private val tripsByStop = HashMap<String, LinkedHashSet<TripKey>>()

    private data class Segment(val start: Long, val count: Int)

        fun write(stopTime: StopTime) {
        val trip = stopTime.tripKey
        tripsByStop.getOrPut(stopTime.stopKey.stopId) { LinkedHashSet() }.add(trip)
        if (trip != pendingTrip) {
            flushPending()
            pendingTrip = trip
        }
        pending += stopTime
    }

    /** Trips serving a stop, for planner pre-filtering. */
    fun tripsServingStop(stopId: String): Set<TripKey> = tripsByStop[stopId].orEmpty()

    private fun flushPending() {
        val trip = pendingTrip ?: return
        if (pending.isEmpty()) return
        val segment = Segment(offset, pending.size)
        segments.getOrPut(trip) { mutableListOf() }.add(segment)
        pending.forEach { stopTime ->
            val stopBytes = stopTime.stopKey.stopId.toByteArray(StandardCharsets.UTF_8)
            data.writeInt(stopTime.stopSequence)
            data.writeInt(stopTime.arrivalTime?.secondsSinceServiceDayStart ?: -1)
            data.writeInt(stopTime.departureTime?.secondsSinceServiceDayStart ?: -1)
            data.writeShort(stopBytes.size)
            data.write(stopBytes)
            offset += FIXED_RECORD_BYTES + stopBytes.size
            written += 1
        }
        pending.clear()
    }

    /** Finalizes the data file and writes the companion index. */
    fun finish(indexPath: Path) {
        flushPending()
        data.flush()
        data.close()
        Files.newOutputStream(indexPath, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
            .use { out ->
                out.write(INDEX_MAGIC)
                out.write(intBytes(segments.size))
                out.write(longBytes(written))
                segments.forEach { (trip, ranges) ->
                    val tripBytes = keyOf(trip).toByteArray(StandardCharsets.UTF_8)
                    out.write(shortBytes(tripBytes.size))
                    out.write(tripBytes)
                    out.write(intBytes(ranges.size))
                    ranges.forEach { range ->
                        out.write(longBytes(range.start))
                        out.write(intBytes(range.count))
                    }
                }
                // Reverse index section: stopId -> trips
                out.write(intBytes(tripsByStop.size))
                tripsByStop.forEach { (stopId, trips) ->
                    val stopBytes = stopId.toByteArray(StandardCharsets.UTF_8)
                    out.write(shortBytes(stopBytes.size))
                    out.write(stopBytes)
                    out.write(intBytes(trips.size))
                    trips.forEach { trip ->
                        val tripBytes = keyOf(trip).toByteArray(StandardCharsets.UTF_8)
                        out.write(shortBytes(tripBytes.size))
                        out.write(tripBytes)
                    }
                }
            }
    }

    /** Closes the data stream without writing an index; [finish] does both. */
    override fun close() {
        data.flush()
        data.close()
    }

    companion object {
        const val FIXED_RECORD_BYTES = 14

        /** Stable, delimiter-separated identity so keys survive class changes. */
        fun keyOf(trip: TripKey): String = trip.feedId.value + "|" + trip.tripId
        internal val INDEX_MAGIC = "STIDX002".toByteArray(Charsets.UTF_8)

        private fun intBytes(value: Int) = byteArrayOf(
            (value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte(),
        )

        private fun shortBytes(value: Int) = byteArrayOf((value ushr 8).toByte(), value.toByte())

        private fun longBytes(value: Long) = ByteArray(8) { i -> (value ushr (56 - 8 * i)).toByte() }
    }
}

/** Random access to stop times by trip. Holds one open channel for batch reads. */
class StopTimeIndexReader(private val indexPath: Path, private val dataPath: Path) : AutoCloseable {

    private data class Segment(val start: Long, val count: Int)

    // One channel for the lifetime of the reader: a planner performs many
    // per-trip reads and reopening the file each time dominated the work.
    private val channel: java.nio.channels.FileChannel? by lazy {
        if (Files.exists(dataPath)) {
            java.nio.channels.FileChannel.open(dataPath, StandardOpenOption.READ)
        } else {
            null
        }
    }

    private val segments: Map<TripKey, List<Segment>> by lazy { loadIndex().first }
    private val stopIndex: Map<String, Set<TripKey>> by lazy { loadIndex().second }

    /** Trips that serve a stop, letting a planner skip unrelated trips. */
    fun tripsServing(stop: StopKey): Set<TripKey> = stopIndex[stop.stopId].orEmpty()

    fun hasTrips(): Boolean = segments.isNotEmpty()

    fun tripCount(): Int = segments.size

    fun rowCount(): Int = segments.values.sumOf { ranges -> ranges.sumOf { it.count } }

    /** Reads all stop times for one trip in stored order. */
    fun stopTimesFor(trip: TripKey, stopKeyOf: (String) -> StopKey): List<StopTime> {
        val ranges = segments[trip] ?: return emptyList()
        val file = channel ?: return emptyList()
        val total = ranges.sumOf { it.count }
        val result = ArrayList<StopTime>(total)
        synchronized(file) {
            val buffer = ByteArray(StopTimeIndexWriter.FIXED_RECORD_BYTES + 64)
            ranges.forEach { range ->
                file.position(range.start)
                repeat(range.count) {
                    readFully(file, buffer, StopTimeIndexWriter.FIXED_RECORD_BYTES)
                    val sequence = readInt(buffer, 0)
                    val arrival = readInt(buffer, 4).takeIf { it >= 0 }?.let { GtfsServiceTime.ofSeconds(it) }
                    val departure = readInt(buffer, 8).takeIf { it >= 0 }?.let { GtfsServiceTime.ofSeconds(it) }
                    val nameLength = ((buffer[12].toInt() and 0xFF) shl 8) or (buffer[13].toInt() and 0xFF)
                    readFully(file, buffer, nameLength)
                    val stopId = String(buffer, 0, nameLength, StandardCharsets.UTF_8)
                    result += StopTime(
                        tripKey = trip,
                        stopKey = stopKeyOf(stopId),
                        stopSequence = sequence,
                        arrivalTime = arrival,
                        departureTime = departure,
                        pickupType = null,
                        dropOffType = null,
                        timepoint = null,
                        shapeDistTraveled = null,
                    )
                }
            }
        }
        return result.sortedBy { it.stopSequence }
    }

    /** All trips that have stop times, for callers that need to page through them. */
    fun trips(): Set<TripKey> = segments.keys

    override fun close() {
        channel?.close()
    }

    private fun loadIndex(): Pair<Map<TripKey, List<Segment>>, Map<String, Set<TripKey>>> {
        if (!Files.exists(indexPath) || !Files.exists(dataPath)) {
            return emptyMap<TripKey, List<Segment>>() to emptyMap<String, Set<TripKey>>()
        }
        val bytes = Files.readAllBytes(indexPath)
        var cursor = 0
        fun nextInt(): Int {
            val value = readInt(bytes, cursor); cursor += 4; return value
        }

        fun nextShort(): Int {
            val value = ((bytes[cursor].toInt() and 0xFF) shl 8) or (bytes[cursor + 1].toInt() and 0xFF)
            cursor += 2
            return value
        }

        fun nextLong(): Long {
            var value = 0L
            for (i in 0 until 8) value = (value shl 8) or (bytes[cursor + i].toLong() and 0xFF)
            cursor += 8
            return value
        }

        cursor = StopTimeIndexWriter.INDEX_MAGIC.size
        val tripCount = nextInt()
        nextLong() // total rows, informational
        val result = LinkedHashMap<TripKey, List<Segment>>(tripCount * 2)
        repeat(tripCount) {
            val nameLength = nextShort()
            val name = String(bytes, cursor, nameLength, StandardCharsets.UTF_8)
            cursor += nameLength
            val segmentCount = nextInt()
            val segments = ArrayList<Segment>(segmentCount)
            repeat(segmentCount) {
                val start = nextLong()
                val rows = nextInt()
                segments.add(Segment(start, rows))
            }
            val (feedId, tripId) = parseKey(name)
            result[TripKey(feedId, tripId)] = segments
        }

        val stopCount = nextInt()
        val stops = LinkedHashMap<String, Set<TripKey>>(stopCount * 2)
        repeat(stopCount) {
            val stopLength = nextShort()
            val stopId = String(bytes, cursor, stopLength, StandardCharsets.UTF_8)
            cursor += stopLength
            val tripCount = nextInt()
            val trips = LinkedHashSet<TripKey>(tripCount * 2)
            repeat(tripCount) {
                val tripLength = nextShort()
                val tripName = String(bytes, cursor, tripLength, StandardCharsets.UTF_8)
                cursor += tripLength
                val (tripFeed, tripId) = parseKey(tripName)
                trips.add(TripKey(tripFeed, tripId))
            }
            stops[stopId] = trips
        }
        return result to stops
    }

    private fun parseKey(name: String): Pair<com.intellitrip.domain.FeedId, String> {
        val separator = name.indexOf('|')
        require(separator > 0) { "malformed stop-time index key: $name" }
        return com.intellitrip.domain.FeedId(name.substring(0, separator)) to name.substring(separator + 1)
    }
    private fun readFully(channel: java.nio.channels.FileChannel, buffer: ByteArray, length: Int) {
        var read = 0
        val byteBuffer = java.nio.ByteBuffer.wrap(buffer, 0, length)
        while (read < length) {
            val n = channel.read(byteBuffer)
            if (n < 0) throw java.io.IOException("unexpected end of stop-time index data")
            read += n
        }
    }

    private fun readInt(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)
}