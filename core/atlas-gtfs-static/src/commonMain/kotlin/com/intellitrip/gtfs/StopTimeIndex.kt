package com.intellitrip.gtfs

import com.intellitrip.domain.FeedId
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
 * Format history:
 * - `STIDX002` kept sequence, arrival, departure and stop id, but dropped
 *   `pickup_type`/`drop_off_type`. A planner reading it could not tell a
 *   no-pickup stop from a regular one, so those rows were offered as
 *   boarding points.
 * - `STIDX003` stores both restrictions and the data-file length, so a
 *   data/index pair from different generations is rejected instead of
 *   misparsed.
 * - `STIDX004` encodes the reverse stop index (`stopId -> trips`) using
 *   4-byte integer trip ordinals referencing the forward trip table rather than
 *   repeating UTF-8 `"feed|tripId"` keys for every stop_time (~80% smaller index
 *   and zero per-entry string allocations on load). The reader still accepts
 *   `002` (with null restrictions) and `003` (string reverse keys), while
 *   `isCurrentFormat` identifies `004` so hosts upgrade existing snapshots in
 *   place via [StaticFeedSnapshotStore.reindexStopTimes].
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
            data.writeByte(stopTime.pickupType ?: NO_VALUE)
            data.writeByte(stopTime.dropOffType ?: NO_VALUE)
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
        val dataBytes = data.toByteArray()
        val index = GrowableBytes()
        index.writeBytes(INDEX_MAGIC)
        // The reader refuses a data file whose length does not match the one
        // recorded here. That is what makes a half-published generation (or a
        // data file left behind by another format) fail closed.
        index.writeLong(dataBytes.size.toLong())
        index.writeInt(segments.size)
        index.writeLong(written)
        val tripOrdinalByKey = HashMap<TripKey, Int>(((segments.size / 0.75f).toInt() + 1).coerceAtLeast(16))
        var nextOrdinal = 0
        segments.forEach { (trip, ranges) ->
            tripOrdinalByKey[trip] = nextOrdinal++
            val tripBytes = keyOf(trip).encodeToByteArray()
            index.writeShort(tripBytes.size)
            index.writeBytes(tripBytes)
            index.writeInt(ranges.size)
            ranges.forEach { range ->
                index.writeLong(range.start)
                index.writeInt(range.count)
            }
        }
        // Reverse index section: stopId -> trip ordinals into the forward trip table
        index.writeInt(tripsByStop.size)
        tripsByStop.forEach { (stopId, trips) ->
            val stopBytes = stopId.encodeToByteArray()
            index.writeShort(stopBytes.size)
            index.writeBytes(stopBytes)
            index.writeInt(trips.size)
            trips.forEach { trip ->
                val ordinal = tripOrdinalByKey[trip]
                    ?: error("stop-time reverse index references unindexed trip: $trip")
                index.writeInt(ordinal)
            }
        }
        return dataBytes to index.toByteArray()
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
        const val FIXED_RECORD_BYTES = 16

        /** Sentinel for an absent GTFS restriction; valid values are small ints. */
        internal const val NO_VALUE = 0xFF

        /** Stable, delimiter-separated identity so keys survive class changes. */
        fun keyOf(trip: TripKey): String = trip.feedId.value + "|" + trip.tripId
        internal val INDEX_MAGIC = "STIDX004".encodeToByteArray()
        internal val INDEX_MAGIC_V3 = "STIDX003".encodeToByteArray()
        internal val INDEX_MAGIC_V2 = "STIDX002".encodeToByteArray()

        private class SliceKey(
            private val bytes: ByteArray,
            private val offset: Int,
            private val length: Int,
        ) {
            private val hash: Int = computeHash(bytes, offset, length)

            override fun hashCode(): Int = hash

            override fun equals(other: Any?): Boolean {
                if (this === other) return true
                if (other !is SliceKey || length != other.length || hash != other.hash) return false
                val otherBytes = other.bytes
                val otherOffset = other.offset
                for (i in 0 until length) {
                    if (bytes[offset + i] != otherBytes[otherOffset + i]) return false
                }
                return true
            }

            private companion object {
                fun computeHash(bytes: ByteArray, offset: Int, length: Int): Int {
                    var h = 1
                    val end = offset + length
                    for (i in offset until end) {
                        h = 31 * h + bytes[i].toInt()
                    }
                    return h
                }
            }
        }

        /**
         * Losslessly transcodes a `STIDX003` index byte image to `STIDX004`
         * without re-parsing `gtfs.zip`.
         *
         * The forward trip table and the accompanying `stop-times.dat` file are
         * identical between `003` and `004`; only the reverse stop index changes
         * from UTF-8 `"feed|tripId"` keys to 4-byte forward-table ordinals.
         * Returns `(dataLength, v4IndexBytes)` or `null` when [indexBytes] is not
         * a well-formed `STIDX003` payload.
         */
        internal fun upgradeV3ToV4(indexBytes: ByteArray): Pair<Long, ByteArray>? {
            if (!StopTimeIndexReader.isV3Format(indexBytes)) return null
            return try {
                val cursor = ByteArrayCursor(indexBytes, INDEX_MAGIC_V3.size)
                val dataLength = cursor.readLong()
                if (dataLength < 0L) return null
                val tripCount = cursor.readInt()
                if (tripCount < 0 || tripCount > cursor.remaining / 7) return null
                cursor.readLong() // total rows, preserved in the verbatim forward slice
                val ordinalByTripKey = HashMap<SliceKey, Int>(((tripCount / 0.75f).toInt() + 1).coerceAtLeast(16))
                for (ordinal in 0 until tripCount) {
                    val nameLength = cursor.readShort()
                    if (nameLength <= 0) return null
                    val nameStart = cursor.position
                    cursor.skip(nameLength)
                    ordinalByTripKey[SliceKey(indexBytes, nameStart, nameLength)] = ordinal
                    val segmentCount = cursor.readInt()
                    if (segmentCount < 0 || segmentCount > cursor.remaining / 12) return null
                    cursor.skip(segmentCount * 12)
                }
                val forwardEnd = cursor.position
                val stopCount = cursor.readInt()
                if (stopCount < 0 || stopCount > cursor.remaining / 6) return null
                val out = GrowableBytes((forwardEnd + stopCount * 16).coerceAtLeast(1024))
                out.writeBytes(INDEX_MAGIC)
                out.writeBytes(indexBytes, INDEX_MAGIC_V3.size, forwardEnd - INDEX_MAGIC_V3.size)
                out.writeInt(stopCount)
                repeat(stopCount) {
                    val stopIdLength = cursor.readShort()
                    val stopIdStart = cursor.position
                    cursor.skip(stopIdLength)
                    out.writeShort(stopIdLength)
                    out.writeBytes(indexBytes, stopIdStart, stopIdLength)
                    val tripsInStop = cursor.readInt()
                    if (tripsInStop < 0 || tripsInStop > cursor.remaining / 2) return null
                    out.writeInt(tripsInStop)
                    repeat(tripsInStop) {
                        val tripNameLength = cursor.readShort()
                        val tripNameStart = cursor.position
                        cursor.skip(tripNameLength)
                        val ordinal = ordinalByTripKey[SliceKey(indexBytes, tripNameStart, tripNameLength)]
                            ?: return null
                        out.writeInt(ordinal)
                    }
                }
                if (cursor.remaining != 0) return null
                dataLength to out.toByteArray()
            } catch (_: Exception) {
                null
            }
        }
    }
}

/** The index bytes are not a format this build can read. */
class StopTimeIndexFormatException(message: String) : IllegalStateException(message)

/**
 * Random access to stop times by trip, parsed from the in-memory [dataBytes]
 * file image built by [StopTimeIndexWriter.finish].
 *
 * Accepts the current `STIDX004` layout as well as legacy `STIDX003` and
 * `STIDX002` ones. `STIDX002` rows carry no boarding/alighting restrictions, and
 * `STIDX003` uses slower string keys in its reverse index, so callers should
 * rebuild non-current indexes through [StaticFeedSnapshotStore.reindexStopTimes].
 */
class StopTimeIndexReader(
    indexBytes: ByteArray,
    private val dataBytes: ByteArray,
) {

    private data class Segment(val start: Long, val count: Int)

    private class LoadedIndex(
        val segments: Map<TripKey, List<Segment>>,
        val stopIndex: Map<String, Set<TripKey>>,
    )

    private class CompactTripSet(private val items: Array<TripKey>) : AbstractSet<TripKey>() {
        override val size: Int get() = items.size
        override fun iterator(): Iterator<TripKey> = items.iterator()
        override fun contains(element: TripKey): Boolean = items.any { it == element }
    }

    private var pendingIndexBytes: ByteArray? = indexBytes
    private val format: IndexFormat = detectFormat(indexBytes)

    /** True when this index is in the current `STIDX004` format. */
    val isCurrentFormat: Boolean get() = format == IndexFormat.V4

    // Load once for both segments and stopIndex, then release raw indexBytes.
    private val loadedIndex: LoadedIndex by lazy {
        loadIndex().also { pendingIndexBytes = null }
    }

    private val segments: Map<TripKey, List<Segment>> get() = loadedIndex.segments
    private val stopIndex: Map<String, Set<TripKey>> get() = loadedIndex.stopIndex

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
        // The planner touches many trips while checking transfer reachability;
        // a 512-entry window thrashed and re-parsed rows on every miss.
        while (cache.size > 2_048) {
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
                    val pickup: Int?
                    val dropOff: Int?
                    if (format != IndexFormat.V2) {
                        pickup = cursor.readByte().takeIf { it != StopTimeIndexWriter.NO_VALUE }
                        dropOff = cursor.readByte().takeIf { it != StopTimeIndexWriter.NO_VALUE }
                    } else {
                        pickup = null
                        dropOff = null
                    }
                    val nameLength = cursor.readShort()
                    val stopId = cursor.readBytes(nameLength).decodeToString()
                    result += StopTime(
                        tripKey = trip,
                        stopKey = stopKeyOf(stopId),
                        stopSequence = sequence,
                        arrivalTime = arrival,
                        departureTime = departure,
                        pickupType = pickup,
                        dropOffType = dropOff,
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

    private fun loadIndex(): LoadedIndex {
        val rawIndex = pendingIndexBytes
        if (rawIndex == null || rawIndex.isEmpty() || dataBytes.isEmpty()) {
            return LoadedIndex(emptyMap(), emptyMap())
        }
        val cursor = ByteArrayCursor(rawIndex, StopTimeIndexWriter.INDEX_MAGIC.size)
        if (format != IndexFormat.V2) {
            val dataLength = cursor.readLong()
            if (dataLength != dataBytes.size.toLong()) {
                throw StopTimeIndexFormatException(
                    "stop-time index describes $dataLength data bytes but the data file has ${dataBytes.size}",
                )
            }
        }
        val tripCount = cursor.readInt()
        cursor.readLong() // total rows, informational
        val feedPool = HashMap<String, FeedId>(4)
        val tripCapacity = ((tripCount / 0.75f).toInt() + 1).coerceAtLeast(16)
        val result = HashMap<TripKey, List<Segment>>(tripCapacity)
        val tripPool = if (format == IndexFormat.V4) null else HashMap<String, TripKey>(tripCapacity)
        val tripsByOrdinal = if (format == IndexFormat.V4) arrayOfNulls<TripKey>(tripCount) else null
        repeat(tripCount) { ordinal ->
            val name = cursor.readBytes(cursor.readShort()).decodeToString()
            val segmentCount = cursor.readInt()
            val segments: List<Segment> = if (segmentCount == 1) {
                val start = cursor.readLong()
                val rows = cursor.readInt()
                listOf(Segment(start, rows))
            } else {
                ArrayList<Segment>(segmentCount).apply {
                    repeat(segmentCount) {
                        val start = cursor.readLong()
                        val rows = cursor.readInt()
                        add(Segment(start, rows))
                    }
                }
            }
            val (feedId, tripId) = parseKey(name, feedPool)
            val tripKey = TripKey(feedId, tripId)
            result[tripKey] = segments
            if (tripsByOrdinal != null) {
                tripsByOrdinal[ordinal] = tripKey
            } else {
                tripPool?.set(name, tripKey)
            }
        }

        val stopCount = cursor.readInt()
        val stopCapacity = ((stopCount / 0.75f).toInt() + 1).coerceAtLeast(16)
        val stops = HashMap<String, Set<TripKey>>(stopCapacity)
        val seenTrips = HashSet<TripKey>(256)
        val scratchTrips = ArrayList<TripKey>(256)
        repeat(stopCount) {
            val stopId = cursor.readBytes(cursor.readShort()).decodeToString()
            val tripsInStop = cursor.readInt()
            when (tripsInStop) {
                0 -> stops[stopId] = emptySet()
                1 -> {
                    val key = readReverseTripKey(cursor, tripCount, tripsByOrdinal, tripPool, feedPool)
                    stops[stopId] = setOf(key)
                }
                else -> {
                    seenTrips.clear()
                    scratchTrips.clear()
                    repeat(tripsInStop) {
                        val key = readReverseTripKey(cursor, tripCount, tripsByOrdinal, tripPool, feedPool)
                        if (seenTrips.add(key)) {
                            scratchTrips.add(key)
                        }
                    }
                    stops[stopId] = if (scratchTrips.size == 1) {
                        setOf(scratchTrips[0])
                    } else {
                        CompactTripSet(scratchTrips.toTypedArray())
                    }
                }
            }
        }
        return LoadedIndex(result, stops)
    }

    private fun readReverseTripKey(
        cursor: ByteArrayCursor,
        tripCount: Int,
        tripsByOrdinal: Array<TripKey?>?,
        tripPool: HashMap<String, TripKey>?,
        feedPool: HashMap<String, FeedId>,
    ): TripKey {
        if (tripsByOrdinal != null) {
            val ordinal = cursor.readInt()
            if (ordinal < 0 || ordinal >= tripCount) {
                throw StopTimeIndexFormatException(
                    "stop-time index trip ordinal $ordinal out of range (0 until $tripCount)",
                )
            }
            return tripsByOrdinal[ordinal]
                ?: throw StopTimeIndexFormatException("missing trip at ordinal $ordinal")
        }
        val tripName = cursor.readBytes(cursor.readShort()).decodeToString()
        return resolveTripKey(tripName, requireNotNull(tripPool), feedPool)
    }

    private fun resolveTripKey(
        name: String,
        tripPool: HashMap<String, TripKey>,
        feedPool: HashMap<String, FeedId>,
    ): TripKey {
        tripPool[name]?.let { return it }
        val (feedId, tripId) = parseKey(name, feedPool)
        val created = TripKey(feedId, tripId)
        tripPool[name] = created
        return created
    }

    private fun parseKey(name: String, feedPool: HashMap<String, FeedId>): Pair<FeedId, String> {
        val separator = name.indexOf('|')
        require(separator > 0) { "malformed stop-time index key: $name" }
        val rawFeed = name.substring(0, separator)
        val feed = feedPool.getOrPut(rawFeed) { FeedId(rawFeed) }
        return feed to name.substring(separator + 1)
    }

    companion object {
        /** True when [indexBytes] is the current `STIDX004` format. */
        fun isCurrentFormat(indexBytes: ByteArray): Boolean =
            matches(indexBytes, StopTimeIndexWriter.INDEX_MAGIC)

        /** True when [indexBytes] uses the intermediate `STIDX003` string reverse index. */
        internal fun isV3Format(indexBytes: ByteArray): Boolean =
            matches(indexBytes, StopTimeIndexWriter.INDEX_MAGIC_V3)

        /** True when [indexBytes] is the legacy format (no restrictions). */
        fun isLegacyFormat(indexBytes: ByteArray): Boolean =
            matches(indexBytes, StopTimeIndexWriter.INDEX_MAGIC_V2)

        private fun matches(bytes: ByteArray, magic: ByteArray): Boolean {
            if (bytes.size < magic.size) return false
            for (index in magic.indices) {
                if (bytes[index] != magic[index]) return false
            }
            return true
        }
    }
}

private enum class IndexFormat { V2, V3, V4 }

private fun detectFormat(indexBytes: ByteArray): IndexFormat {
    if (indexBytes.isEmpty()) return IndexFormat.V4
    return when {
        StopTimeIndexReader.isCurrentFormat(indexBytes) -> IndexFormat.V4
        StopTimeIndexReader.isV3Format(indexBytes) -> IndexFormat.V3
        StopTimeIndexReader.isLegacyFormat(indexBytes) -> IndexFormat.V2
        else -> throw StopTimeIndexFormatException(
            "unrecognized stop-time index format (expected STIDX002, STIDX003, or STIDX004)",
        )
    }
}
