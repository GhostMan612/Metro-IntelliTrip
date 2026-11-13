package com.intellitrip.gtfsrt

/**
 * Portable GTFS-Realtime decoder.
 *
 * This is a deliberately small protobuf wire reader rather than a generic
 * runtime: it understands exactly the fields in
 * `gtfs-realtime.proto` that the app maps, and skips everything else.
 * JVM keeps the generated Java bindings because they are mature and already
 * shipped on Android; wasmJs uses this implementation. A parity test decodes
 * the same official bytes with both and compares the whole model.
 *
 * Failure is always a [GtfsRealtimeException]. A truncated length, an
 * over-long varint, invalid UTF-8 or an unsupported wire type is a malformed
 * payload, never a best-effort partial feed: the polling client turns the
 * exception into an explicit `MalformedResponse`.
 */
object PortableFeedDecoder : FeedDecoder {

    private const val WIRE_VARINT = 0
    private const val WIRE_FIXED64 = 1
    private const val WIRE_LENGTH = 2
    private const val WIRE_FIXED32 = 5

    override fun decode(bytes: ByteArray): RtFeedMessage {
        val reader = ProtoReader(bytes, 0, bytes.size)
        var header: Header? = null
        val entities = mutableListOf<RtFeedEntity>()
        while (reader.hasMore()) {
            val tag = reader.readTag()
            when (tag.field) {
                // FeedMessage.header = 1
                1 -> if (tag.wire == WIRE_LENGTH) {
                    header = parseHeader(reader.readSubMessage())
                } else {
                    reader.skip(tag.wire)
                }

                // FeedMessage.entity = 2 (repeated)
                2 -> if (tag.wire == WIRE_LENGTH) {
                    entities += parseEntity(reader.readSubMessage())
                } else {
                    reader.skip(tag.wire)
                }

                else -> reader.skip(tag.wire)
            }
        }
        // `FeedHeader` and its `gtfs_realtime_version` are required in the
        // proto schema. Returning an empty feed for an empty payload would
        // turn a broken response into "no vehicles", which is the silent
        // failure the decoder exists to avoid.
        val parsedHeader = header ?: malformed("missing FeedHeader")
        if (!parsedHeader.versionSeen) malformed("missing gtfs_realtime_version")
        return RtFeedMessage(headerTimestamp = parsedHeader.timestamp, entities = entities)
    }

    private data class Header(val timestamp: Long, val versionSeen: Boolean)

    // FeedHeader: gtfs_realtime_version = 1 (required), timestamp = 3
    private fun parseHeader(reader: ProtoReader): Header {
        var timestamp = 0L
        var versionSeen = false
        while (reader.hasMore()) {
            val tag = reader.readTag()
            when (tag.field) {
                1 -> if (tag.wire == WIRE_LENGTH) {
                    reader.readString()
                    versionSeen = true
                } else {
                    reader.skip(tag.wire)
                }

                3 -> timestamp = if (tag.wire == WIRE_VARINT) reader.readVarint() else skipAndZero(reader, tag.wire)
                else -> reader.skip(tag.wire)
            }
        }
        return Header(timestamp = timestamp, versionSeen = versionSeen)
    }

    private fun skipAndZero(reader: ProtoReader, wire: Int): Long {
        reader.skip(wire)
        return 0L
    }

    // FeedEntity: id = 1 (required), trip_update = 3, vehicle = 4, alert = 5
    private fun parseEntity(reader: ProtoReader): RtFeedEntity {
        var id = ""
        var idSeen = false
        var vehiclePosition: RtVehiclePosition? = null
        var tripUpdate: RtTripUpdate? = null
        var alert: RtAlert? = null
        while (reader.hasMore()) {
            val tag = reader.readTag()
            when (tag.field) {
                1 -> if (tag.wire == WIRE_LENGTH) {
                    id = reader.readString()
                    idSeen = true
                } else {
                    reader.skip(tag.wire)
                }
                3 -> if (tag.wire == WIRE_LENGTH) {
                    tripUpdate = parseTripUpdate(reader.readSubMessage())
                } else {
                    reader.skip(tag.wire)
                }

                4 -> if (tag.wire == WIRE_LENGTH) {
                    vehiclePosition = parseVehiclePosition(reader.readSubMessage())
                } else {
                    reader.skip(tag.wire)
                }

                5 -> if (tag.wire == WIRE_LENGTH) {
                    alert = parseAlert(reader.readSubMessage())
                } else {
                    reader.skip(tag.wire)
                }

                else -> reader.skip(tag.wire)
            }
        }
        if (!idSeen) malformed("FeedEntity is missing its required id")
        return RtFeedEntity(
            id = id,
            vehiclePosition = vehiclePosition,
            tripUpdate = tripUpdate,
            alert = alert,
        )
    }

    // VehiclePosition: trip = 1, position = 2, timestamp = 5, vehicle = 8.
    // (position/vehicle were swapped in the first portable draft; the parity
    // test against the generated bindings caught it.)
    private fun parseVehiclePosition(reader: ProtoReader): RtVehiclePosition {
        var trip: RtTripDescriptor? = null
        var vehicle: RtVehicleDescriptor? = null
        var position: RtPosition? = null
        var timestamp = 0L
        while (reader.hasMore()) {
            val tag = reader.readTag()
            when (tag.field) {
                1 -> if (tag.wire == WIRE_LENGTH) {
                    trip = parseTripDescriptor(reader.readSubMessage())
                } else {
                    reader.skip(tag.wire)
                }

                2 -> if (tag.wire == WIRE_LENGTH) {
                    position = parsePosition(reader.readSubMessage())
                } else {
                    reader.skip(tag.wire)
                }

                5 -> timestamp = if (tag.wire == WIRE_VARINT) reader.readVarint() else skipAndZero(reader, tag.wire)

                8 -> if (tag.wire == WIRE_LENGTH) {
                    vehicle = parseVehicleDescriptor(reader.readSubMessage())
                } else {
                    reader.skip(tag.wire)
                }

                else -> reader.skip(tag.wire)
            }
        }
        return RtVehiclePosition(
            trip = trip,
            vehicle = vehicle,
            position = position,
            timestamp = timestamp,
        )
    }

    // Position: latitude = 1, longitude = 2, bearing = 3, speed = 5 (all float)
    private fun parsePosition(reader: ProtoReader): RtPosition {
        var latitude: Double? = null
        var longitude: Double? = null
        var bearing = 0.0
        var speed = 0.0
        while (reader.hasMore()) {
            val tag = reader.readTag()
            when (tag.field) {
                1 -> if (tag.wire == WIRE_FIXED32) {
                    latitude = Float.fromBits(reader.readFixed32()).toDouble()
                } else {
                    reader.skip(tag.wire)
                }

                2 -> if (tag.wire == WIRE_FIXED32) {
                    longitude = Float.fromBits(reader.readFixed32()).toDouble()
                } else {
                    reader.skip(tag.wire)
                }

                3 -> if (tag.wire == WIRE_FIXED32) {
                    bearing = Float.fromBits(reader.readFixed32()).toDouble()
                } else {
                    reader.skip(tag.wire)
                }

                5 -> if (tag.wire == WIRE_FIXED32) {
                    speed = Float.fromBits(reader.readFixed32()).toDouble()
                } else {
                    reader.skip(tag.wire)
                }

                else -> reader.skip(tag.wire)
            }
        }
        // Position.latitude and longitude are required. A position message
        // without usable coordinates is not a location.
        if (latitude == null || longitude == null) malformed("Position is missing latitude or longitude")
        return RtPosition(
            latitude = latitude,
            longitude = longitude,
            bearing = bearing,
            speed = speed,
        )
    }

    // TripDescriptor: trip_id = 1, start_time = 2, start_date = 3, route_id = 5, direction_id = 6
    private fun parseTripDescriptor(reader: ProtoReader): RtTripDescriptor {
        var tripId = ""
        var routeId = ""
        var directionId: Int? = null
        var startDate = ""
        var startTime = ""
        while (reader.hasMore()) {
            val tag = reader.readTag()
            when (tag.field) {
                1 -> if (tag.wire == WIRE_LENGTH) tripId = reader.readString() else reader.skip(tag.wire)
                2 -> if (tag.wire == WIRE_LENGTH) startTime = reader.readString() else reader.skip(tag.wire)
                3 -> if (tag.wire == WIRE_LENGTH) startDate = reader.readString() else reader.skip(tag.wire)
                5 -> if (tag.wire == WIRE_LENGTH) routeId = reader.readString() else reader.skip(tag.wire)
                6 -> if (tag.wire == WIRE_VARINT) {
                    directionId = reader.readVarint().toInt()
                } else {
                    reader.skip(tag.wire)
                }

                else -> reader.skip(tag.wire)
            }
        }
        return RtTripDescriptor(
            tripId = tripId,
            routeId = routeId,
            directionId = directionId,
            startDate = startDate,
            startTime = startTime,
        )
    }

    // VehicleDescriptor.id = 1
    private fun parseVehicleDescriptor(reader: ProtoReader): RtVehicleDescriptor {
        var id = ""
        while (reader.hasMore()) {
            val tag = reader.readTag()
            when (tag.field) {
                1 -> if (tag.wire == WIRE_LENGTH) id = reader.readString() else reader.skip(tag.wire)
                else -> reader.skip(tag.wire)
            }
        }
        return RtVehicleDescriptor(id = id)
    }

    // TripUpdate: trip = 1, stop_time_update = 2, vehicle = 3, timestamp = 4
    private fun parseTripUpdate(reader: ProtoReader): RtTripUpdate {
        var trip: RtTripDescriptor? = null
        var vehicle: RtVehicleDescriptor? = null
        var timestamp: Long? = null
        val stopTimeUpdates = mutableListOf<RtStopTimeUpdate>()
        while (reader.hasMore()) {
            val tag = reader.readTag()
            when (tag.field) {
                1 -> if (tag.wire == WIRE_LENGTH) {
                    trip = parseTripDescriptor(reader.readSubMessage())
                } else {
                    reader.skip(tag.wire)
                }

                2 -> if (tag.wire == WIRE_LENGTH) {
                    stopTimeUpdates += parseStopTimeUpdate(reader.readSubMessage())
                } else {
                    reader.skip(tag.wire)
                }

                3 -> if (tag.wire == WIRE_LENGTH) {
                    vehicle = parseVehicleDescriptor(reader.readSubMessage())
                } else {
                    reader.skip(tag.wire)
                }

                4 -> if (tag.wire == WIRE_VARINT) {
                    timestamp = reader.readVarint()
                } else {
                    reader.skip(tag.wire)
                }

                else -> reader.skip(tag.wire)
            }
        }
        return RtTripUpdate(
            trip = trip,
            vehicle = vehicle,
            timestamp = timestamp,
            stopTimeUpdates = stopTimeUpdates,
        )
    }

    // StopTimeUpdate: arrival = 2, departure = 3, stop_id = 4, schedule_relationship = 5
    private fun parseStopTimeUpdate(reader: ProtoReader): RtStopTimeUpdate {
        var stopId = ""
        var arrival: RtStopTimeEvent? = null
        var departure: RtStopTimeEvent? = null
        var scheduleRelationship = 0
        while (reader.hasMore()) {
            val tag = reader.readTag()
            when (tag.field) {
                2 -> if (tag.wire == WIRE_LENGTH) {
                    arrival = parseStopTimeEvent(reader.readSubMessage())
                } else {
                    reader.skip(tag.wire)
                }

                3 -> if (tag.wire == WIRE_LENGTH) {
                    departure = parseStopTimeEvent(reader.readSubMessage())
                } else {
                    reader.skip(tag.wire)
                }

                4 -> if (tag.wire == WIRE_LENGTH) stopId = reader.readString() else reader.skip(tag.wire)
                5 -> if (tag.wire == WIRE_VARINT) {
                    scheduleRelationship = reader.readVarint().toInt()
                } else {
                    reader.skip(tag.wire)
                }

                else -> reader.skip(tag.wire)
            }
        }
        return RtStopTimeUpdate(
            stopId = stopId,
            arrival = arrival,
            departure = departure,
            scheduleRelationship = scheduleRelationship,
        )
    }

    /**
     * StopTimeEvent: delay = 1, time = 2.
     *
     * Both encodings are legal. A delay is relative to the schedule; an
     * absolute `time` is the provider's predicted event instant. Keeping both
     * lets the UI prefer the absolute time when present and fall back to the
     * delay without inventing a zero.
     */
    private fun parseStopTimeEvent(reader: ProtoReader): RtStopTimeEvent? {
        var delay: Int? = null
        var time: Long? = null
        while (reader.hasMore()) {
            val tag = reader.readTag()
            when (tag.field) {
                1 -> if (tag.wire == WIRE_VARINT) {
                    delay = reader.readVarint().toInt()
                } else {
                    reader.skip(tag.wire)
                }

                2 -> if (tag.wire == WIRE_VARINT) {
                    time = reader.readVarint()
                } else {
                    reader.skip(tag.wire)
                }

                else -> reader.skip(tag.wire)
            }
        }
        return if (delay != null || time != null) RtStopTimeEvent(delay = delay, time = time) else null
    }

    // Alert: informed_entity = 5, header_text = 10, description_text = 11, severity_level = 14
    private fun parseAlert(reader: ProtoReader): RtAlert {
        var headerText = RtTranslatedString()
        var descriptionText = RtTranslatedString()
        // Proto default: UNKNOWN_SEVERITY (wire number 1), matching the
        // generated bindings' `getSeverityLevel().number`.
        var severityLevelNumber = 1
        val informedEntities = mutableListOf<RtEntitySelector>()
        while (reader.hasMore()) {
            val tag = reader.readTag()
            when (tag.field) {
                5 -> if (tag.wire == WIRE_LENGTH) {
                    informedEntities += parseEntitySelector(reader.readSubMessage())
                } else {
                    reader.skip(tag.wire)
                }

                10 -> if (tag.wire == WIRE_LENGTH) {
                    headerText = parseTranslatedString(reader.readSubMessage())
                } else {
                    reader.skip(tag.wire)
                }

                11 -> if (tag.wire == WIRE_LENGTH) {
                    descriptionText = parseTranslatedString(reader.readSubMessage())
                } else {
                    reader.skip(tag.wire)
                }

                14 -> if (tag.wire == WIRE_VARINT) {
                    severityLevelNumber = reader.readVarint().toInt()
                } else {
                    reader.skip(tag.wire)
                }

                else -> reader.skip(tag.wire)
            }
        }
        return RtAlert(
            headerText = headerText,
            descriptionText = descriptionText,
            severityLevelNumber = severityLevelNumber,
            informedEntities = informedEntities,
        )
    }

    // TranslatedString.translation = 1 (repeated); Translation.text = 1
    private fun parseTranslatedString(reader: ProtoReader): RtTranslatedString {
        val translations = mutableListOf<String>()
        while (reader.hasMore()) {
            val tag = reader.readTag()
            when (tag.field) {
                1 -> if (tag.wire == WIRE_LENGTH) {
                    translations += parseTranslation(reader.readSubMessage())
                } else {
                    reader.skip(tag.wire)
                }

                else -> reader.skip(tag.wire)
            }
        }
        return RtTranslatedString(translations = translations)
    }

    private fun parseTranslation(reader: ProtoReader): String {
        var text = ""
        while (reader.hasMore()) {
            val tag = reader.readTag()
            when (tag.field) {
                1 -> if (tag.wire == WIRE_LENGTH) text = reader.readString() else reader.skip(tag.wire)
                else -> reader.skip(tag.wire)
            }
        }
        return text
    }

    // EntitySelector: route_id = 2, stop_id = 5
    private fun parseEntitySelector(reader: ProtoReader): RtEntitySelector {
        var routeId = ""
        var stopId = ""
        while (reader.hasMore()) {
            val tag = reader.readTag()
            when (tag.field) {
                2 -> if (tag.wire == WIRE_LENGTH) routeId = reader.readString() else reader.skip(tag.wire)
                5 -> if (tag.wire == WIRE_LENGTH) stopId = reader.readString() else reader.skip(tag.wire)
                else -> reader.skip(tag.wire)
            }
        }
        return RtEntitySelector(routeId = routeId, stopId = stopId)
    }
}

private data class Tag(val field: Int, val wire: Int)

/**
 * Bounds-checked protobuf reader.
 *
 * `bytes` is shared with sub-message readers; each reader has its own
 * `[pos, end)` window so nested lengths cannot escape their parent. A
 * declared length larger than the remaining bytes fails before any read.
 */
private class ProtoReader(
    private val bytes: ByteArray,
    private var pos: Int,
    private val end: Int,
) {

    fun hasMore(): Boolean = pos < end

    fun readTag(): Tag {
        val raw = readVarint()
        // A tag is fieldNumber << 3 | wireType, with fieldNumber <= 2^29 - 1.
        if (raw <= 0L || raw > 0xFFFF_FFFFL) fail("invalid field tag")
        val field = (raw ushr 3).toInt()
        if (field == 0) fail("field number 0")
        return Tag(field = field, wire = (raw and 7L).toInt())
    }

    fun readVarint(): Long {
        var result = 0L
        var shift = 0
        while (shift < 64) {
            if (pos >= end) fail("truncated varint")
            val b = bytes[pos++].toInt() and 0xFF
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) {
                if (shift == 63 && b > 1) fail("varint overflow")
                return result
            }
            shift += 7
        }
        fail("varint too long")
    }

    fun readFixed32(): Int {
        if (end - pos < 4) fail("truncated 32-bit field")
        val value = (bytes[pos].toInt() and 0xFF) or
            ((bytes[pos + 1].toInt() and 0xFF) shl 8) or
            ((bytes[pos + 2].toInt() and 0xFF) shl 16) or
            ((bytes[pos + 3].toInt() and 0xFF) shl 24)
        pos += 4
        return value
    }

    fun readSubMessage(): ProtoReader {
        val length = readVarint()
        if (length < 0L) fail("negative message length")
        val remaining = (end - pos).toLong()
        if (length > remaining) fail("message length $length exceeds $remaining remaining bytes")
        val start = pos
        pos += length.toInt()
        return ProtoReader(bytes, start, start + length.toInt())
    }

    fun readString(): String {
        val sub = readSubMessage()
        return try {
            sub.bytes.decodeToString(
                startIndex = sub.pos,
                endIndex = sub.end,
                throwOnInvalidSequence = true,
            )
        } catch (e: Exception) {
            fail("invalid UTF-8 string")
        }
    }

    fun skip(wire: Int) {
        when (wire) {
            0 -> readVarint()
            1 -> advance(8)
            2 -> {
                val length = readVarint()
                if (length < 0L) fail("negative length for skipped field")
                if (length > (end - pos).toLong()) fail("skipped field exceeds message")
                advance(length.toInt())
            }

            5 -> advance(4)
            // Groups (3/4) are not used by GTFS-Realtime and cannot be skipped
            // safely without tracking nesting; reject rather than guess.
            else -> fail("unsupported wire type $wire")
        }
    }

    private fun advance(count: Int) {
        if (count < 0 || count > end - pos) fail("truncated field")
        pos += count
    }

    private fun fail(reason: String): Nothing =
        throw GtfsRealtimeException("Malformed GTFS-Realtime payload: $reason")
}


/** Decoder-level malformed-payload failure (outside the bounded reader). */
private fun malformed(reason: String): Nothing =
    throw GtfsRealtimeException("Malformed GTFS-Realtime payload: $reason")
