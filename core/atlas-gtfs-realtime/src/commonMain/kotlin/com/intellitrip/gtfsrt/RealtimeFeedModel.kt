package com.intellitrip.gtfsrt

/**
 * Portable decoded model of a GTFS-Realtime protobuf `FeedMessage`.
 *
 * The wire format (protobuf) is JVM-only: the JVM actual decodes the Java
 * protobuf bindings into this model, while the mapper/polling code operates
 * only on the model. Optional proto fields are modelled with nulls so the
 * `has*()` checks of the original implementation stay expressible.
 */

data class RtFeedMessage(
    val headerTimestamp: Long = 0,
    val entities: List<RtFeedEntity> = emptyList(),
)

data class RtFeedEntity(
    val id: String,
    val vehiclePosition: RtVehiclePosition? = null,
    val tripUpdate: RtTripUpdate? = null,
    val alert: RtAlert? = null,
)

data class RtVehiclePosition(
    val trip: RtTripDescriptor? = null,
    val vehicle: RtVehicleDescriptor? = null,
    val position: RtPosition? = null,
    /** Raw uint64; 0/absent when unset (mirrors the protobuf default). */
    val timestamp: Long = 0,
)

data class RtPosition(
    /** Null when `hasLatitude()` is false. */
    val latitude: Double? = null,
    /** Null when `hasLongitude()` is false. */
    val longitude: Double? = null,
    val bearing: Double = 0.0,
    val speed: Double = 0.0,
)

data class RtTripDescriptor(
    val tripId: String = "",
    val routeId: String = "",
    /** Null when `hasDirectionId()` is false. */
    val directionId: Int? = null,
    val startDate: String = "",
    val startTime: String = "",
)

data class RtVehicleDescriptor(
    val id: String = "",
)

data class RtTripUpdate(
    val trip: RtTripDescriptor? = null,
    val vehicle: RtVehicleDescriptor? = null,
    /** Null when `hasTimestamp()` is false. */
    val timestamp: Long? = null,
    val stopTimeUpdates: List<RtStopTimeUpdate> = emptyList(),
)

data class RtStopTimeUpdate(
    val stopId: String = "",
    val arrival: RtStopTimeEvent? = null,
    val departure: RtStopTimeEvent? = null,
)

data class RtStopTimeEvent(
    /** Null when `hasDelay()` is false. */
    val delay: Int? = null,
)

data class RtAlert(
    val headerText: RtTranslatedString = RtTranslatedString(),
    val descriptionText: RtTranslatedString = RtTranslatedString(),
    /**
     * Raw `Alert.SeverityLevel` number.
     *
     * The official proto numbers the enum 1..4 (`UNKNOWN_SEVERITY = 1`), not
     * 0..3; the generated bindings use the same default. Keeping the wire
     * number here makes the portable decoder byte-for-byte comparable with
     * the Java bindings.
     */
    val severityLevelNumber: Int = 1,
    val informedEntities: List<RtEntitySelector> = emptyList(),
)

data class RtTranslatedString(
    /** Translation texts, in order. The mapper uses the first entry. */
    val translations: List<String> = emptyList(),
)

data class RtEntitySelector(
    val routeId: String = "",
    val stopId: String = "",
)

/**
 * Decodes a GTFS-Realtime payload into the portable model.
 *
 * Platform implementations differ: JVM uses the generated Java protobuf
 * bindings, wasmJs uses [PortableFeedDecoder]. Both must reject malformed
 * bytes by throwing, which the polling client reports as a malformed
 * response rather than an empty feed.
 */
fun interface FeedDecoder {
    fun decode(bytes: ByteArray): RtFeedMessage
}

/** Returns the host platform's [FeedDecoder]. */
expect fun platformFeedDecoder(): FeedDecoder
