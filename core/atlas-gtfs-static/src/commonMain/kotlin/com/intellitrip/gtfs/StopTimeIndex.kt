package com.intellitrip.gtfs

import com.intellitrip.domain.GtfsServiceTime
import com.intellitrip.domain.StopKey
import com.intellitrip.domain.StopTime
import com.intellitrip.domain.TripKey

/**
 * Compact stop-time index over `stop_times.txt`.
 *
 * The Twin Cities feed carries roughly 850k stop times. Retaining them as
 * objects exhausted a phone heap, so rows are written once at snapshot time
 * and read back per trip on demand. The parser streams into the writer
 * without ever holding the full table.
 *
 * On disk nothing changes between the old platform-stream build and this
 * one: the data file and the companion index keep the exact same binary
 * layout (`STIDX002`), so indexes written by either build are readable by
 * both. The difference is storage: rows are buffered into the writer's
 * [GrowableBytes] and handed out as bytes for [PlatformFileStore] to
 * persist, and the reader parses from bytes instead of a platform channel.
 */
class StopTimeIndexWriter {

    private val data = GrowableBytes(1 shl 16)
    private val segments = LinkedHashMap<TripKey, MutableList<Segment>>()
    private var offset = 0L
    private var written = 0L

    // Only one trip's rows are buffered at a time, so records for a trip are
    // always contiguous regardless of the order the feed lists them in.
    private var pendingTrip: TripKey? = null
    private val pending = ArrayList<StopTime>(128)

    // Reverse index: which trips serve a stop. Bounded by the stop count and
    // it lets the planner consider only trips near the origin instead of
    // every trip.
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
            val stopBytes = stopTime.stopKey.stopId.encodeToByteArray()
            data.writeInt(stopTime.stopSequence)
            data.writeInt(stopTime.arrivalTime?.secondsSinceServiceDayStart ?: -1)
            data.writeInt(stopTime.departureTime?.secondsSinceServiceDayStart ?: -1)
            data.writeShort(stopBytes.size)
            data.writeBytes(stopBytes)
            offset += FIXED_RECORD_BYTES + stopBytes.size
            written += 1
        }
        pending.clear()
    }

    /**
     * Finalizes the data buffer and produces the companion index bytes.
     * Returns `(dataBytes, indexBytes)`.
     */
    fun finish(): Pair<ByteArray, ByteArray> {
        flushPending()
        val index = GrowableBytes()
        index.writeBytes(INDEX_MAGIC)
        index.writeInt(segments.size)
        index.writeLong(written)
        segments.forEach { (trip, ranges) ->
            val tripBytes = keyOf(trip).encodeToByteArray()
            index.writeShort(tripBytes.size)
            index.writeBytes(tripBytes)
            index.writeInt(ranges.size)
            ranges.forEach { range ->
                index.writeLong(range.start)
                index.writeInt(range.count)
            }
        }
        // Reverse index section: stopId -> trips
        index.writeInt(tripsByStop.size)
        tripsByStop.forEach { (stopId, trips) ->
            val stopBytes = stopId.encodeToByteArray()
            index.writeShort(stopBytes.size)
            index.writeBytes(stopBytes)
            index.writeInt(trips.size)
            trips.forEach { trip ->
                val tripBytes = keyOf(trip).encodeToByteArray()
                index.writeShort(tripBytes.size)
                index.writeBytes(tripBytes)
            }
        }
        return data.toByteArray() to index.toByteArray()
    }

    /**
     * Releases buffered rows without producing an index. [finish] does both;
     * closing without finishing simply drops the in-memory buffer.
     */
    fun close() {
        // Nothing to flush: bytes are persisted by the host (store) after
        // finish(). Closing is a no-op so `use` blocks behave like the old
        // stream-based version.
    }

    companion object {
        const val FIXED_RECORD_BYTES = 14

        /** Stable, delimiter-separated identity so keys survive class changes. */
        fun keyOf(trip: TripKey): String = trip.feedId.value + "|" + trip.tripId
        internal val INDEX_MAGIC = "STIDX002".encodeToByteArray()
    }
}

/**
 * Random access to stop times by trip, parsed from the in-memory [dataBytes]
 * file image built by [StopTimeIndexWriter.finish].
 */
class StopTimeIndexReader(
    private val indexBytes: ByteArray,
    private val dataBytes: ByteArray,
) {

    private data class Segment(val start: Long, val count: Int)

    private val segments: Map<TripKey, List<Segment>> by lazy { loadIndex().first }
    private val stopIndex: Map<String, Set<TripKey>> by lazy { loadIndex().second }

    /**
     * Small cache of recently read trips. A planner reads the same trip
     * repeatedly while building transfer connections, and each miss costs a
     * re-parse of that trip's rows; on a phone that dominates the whole plan.
     *
     * Insertion-order map used as an LRU: access re-inserts at the tail, and
     * the eldest entries are evicted past the cap.
     */
    private val cache = LinkedHashMap<TripKey, List<StopTime>>()
    private val lock = SimpleLock()

    private fun cacheGet(trip: TripKey): List<StopTime>? {
        val rows = cache.remove(trip) ?: return null
        cache[trip] = rows
        return rows
    }

    private fun cachePut(trip: TripKey, rows: List<StopTime>) {
        cache[trip] = rows
        while (cache.size > 512) {
            cache.remove(cache.keys.first())
        }
    }

    /** Trips that serve a stop, letting a planner skip unrelated trips. */
    fun tripsServing(stop: StopKey): Set<TripKey> = stopIndex[stop.stopId].orEmpty()

    fun hasTrips(): Boolean = segments.isNotEmpty()

    fun tripCount(): Int = segments.size

    fun rowCount(): Int = segments.values.sumOf { ranges -> ranges.sumOf { it.count } }

    /** Reads all stop times for one trip in stored order. */
    fun stopTimesFor(trip: TripKey, stopKeyOf: (String) -> StopKey): List<StopTime> {
        lock.locked { cacheGet(trip) }?.let { return it }
        val ranges = segments[trip] ?: return emptyList()
        val total = ranges.sumOf { it.count }
        val result = ArrayList<StopTime>(total)
        lock.locked {
            ranges.forEach { range ->
                val cursor = segmentCursor(range.start)
                repeat(range.count) {
                    val sequence = cursor.readInt()
                    val arrival = cursor.readInt().takeIf { it >= 0 }?.let { GtfsServiceTime.ofSeconds(it) }
                    val departure = cursor.readInt().takeIf { it >= 0 }?.let { GtfsServiceTime.ofSeconds(it) }
                    val nameLength = cursor.readShort()
                    val stopId = cursor.readBytes(nameLength).decodeToString()
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
        val sorted = result.sortedBy { it.stopSequence }
        lock.locked { cachePut(trip, sorted) }
        return sorted
    }

    /** All trips that have stop times, for callers that need to page through them. */
    fun trips(): Set<TripKey> = segments.keys

    private fun segmentCursor(start: Long): ByteArrayCursor {
        if (start < 0 || start > Int.MAX_VALUE) throw IllegalStateException("unexpected end of stop-time index data")
        return ByteArrayCursor(dataBytes, start.toInt())
    }

    private fun loadIndex(): Pair<Map<TripKey, List<Segment>>, Map<String, Set<TripKey>>> {
        if (indexBytes.isEmpty() || dataBytes.isEmpty()) {
            return emptyMap<TripKey, List<Segment>>() to emptyMap<String, Set<TripKey>>()
        }
        val cursor = ByteArrayCursor(indexBytes, StopTimeIndexWriter.INDEX_MAGIC.size)
        val tripCount = cursor.readInt()
        cursor.readLong() // total rows, informational
        val result = LinkedHashMap<TripKey, List<Segment>>(tripCount * 2)
        repeat(tripCount) {
            val name = cursor.readBytes(cursor.readShort()).decodeToString()
            val segmentCount = cursor.readInt()
            val segments = ArrayList<Segment>(segmentCount)
            repeat(segmentCount) {
                val start = cursor.readLong()
                val rows = cursor.readInt()
                segments.add(Segment(start, rows))
            }
            val (feedId, tripId) = parseKey(name)
            result[TripKey(feedId, tripId)] = segments
        }

        val stopCount = cursor.readInt()
        val stops = LinkedHashMap<String, Set<TripKey>>(stopCount * 2)
        repeat(stopCount) {
            val stopId = cursor.readBytes(cursor.readShort()).decodeToString()
            val tripsInStop = cursor.readInt()
            val trips = LinkedHashSet<TripKey>(tripsInStop * 2)
            repeat(tripsInStop) {
                val tripName = cursor.readBytes(cursor.readShort()).decodeToString()
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
}
