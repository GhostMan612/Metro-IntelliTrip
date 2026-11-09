package com.intellitrip.gtfsrt

import com.google.transit.realtime.GtfsRealtime
import com.google.protobuf.InvalidProtocolBufferException

/**
 * JVM feed decoder: Java protobuf (Android-compatible GTFS-Realtime bindings)
 * converted into the portable model. This is the only file in the module
 * that touches the protobuf wire format.
 */
actual fun platformFeedDecoder(): FeedDecoder = FeedDecoder { bytes ->
    val feed = try {
        GtfsRealtime.FeedMessage.parseFrom(bytes)
    } catch (e: InvalidProtocolBufferException) {
        throw GtfsRealtimeException("Malformed GTFS-Realtime payload", e)
    }
    feed.toRtFeedMessage()
}

internal fun GtfsRealtime.FeedMessage.toRtFeedMessage(): RtFeedMessage = RtFeedMessage(
    headerTimestamp = header.timestamp,
    entities = entityList.map { entity ->
        RtFeedEntity(
            id = entity.id,
            vehiclePosition = if (entity.hasVehicle()) entity.vehicle.toRtVehiclePosition() else null,
            tripUpdate = if (entity.hasTripUpdate()) entity.tripUpdate.toRtTripUpdate() else null,
            alert = if (entity.hasAlert()) entity.alert.toRtAlert() else null,
        )
    },
)

private fun GtfsRealtime.VehiclePosition.toRtVehiclePosition(): RtVehiclePosition =
    RtVehiclePosition(
        trip = if (hasTrip()) trip.toRtTripDescriptor() else null,
        vehicle = if (hasVehicle()) vehicle.toRtVehicleDescriptor() else null,
        position = if (hasPosition()) position.toRtPosition() else null,
        timestamp = timestamp,
    )

private fun GtfsRealtime.Position.toRtPosition(): RtPosition = RtPosition(
    latitude = if (hasLatitude()) latitude.toDouble() else null,
    longitude = if (hasLongitude()) longitude.toDouble() else null,
    bearing = bearing.toDouble(),
    speed = speed.toDouble(),
)

private fun GtfsRealtime.TripDescriptor.toRtTripDescriptor(): RtTripDescriptor = RtTripDescriptor(
    tripId = tripId,
    routeId = routeId,
    directionId = if (hasDirectionId()) directionId else null,
    startDate = startDate,
    startTime = startTime,
)

private fun GtfsRealtime.VehicleDescriptor.toRtVehicleDescriptor(): RtVehicleDescriptor =
    RtVehicleDescriptor(id = id)

private fun GtfsRealtime.TripUpdate.toRtTripUpdate(): RtTripUpdate = RtTripUpdate(
    trip = if (hasTrip()) trip.toRtTripDescriptor() else null,
    vehicle = if (hasVehicle()) vehicle.toRtVehicleDescriptor() else null,
    timestamp = if (hasTimestamp()) timestamp else null,
    stopTimeUpdates = stopTimeUpdateList.map { stopUpdate ->
        RtStopTimeUpdate(
            stopId = stopUpdate.stopId,
            arrival = stopUpdate.arrival.toRtStopTimeEventOrNull(),
            departure = stopUpdate.departure.toRtStopTimeEventOrNull(),
        )
    },
)

private fun GtfsRealtime.TripUpdate.StopTimeEvent.toRtStopTimeEventOrNull(): RtStopTimeEvent? =
    // `getArrival()`/`getDeparture()` return a default instance when the
    // message field is absent; both absent and no-delay map to null.
    if (hasDelay()) RtStopTimeEvent(delay = delay) else null

private fun GtfsRealtime.Alert.toRtAlert(): RtAlert = RtAlert(
    headerText = headerText.toRtTranslatedString(),
    descriptionText = descriptionText.toRtTranslatedString(),
    severityLevelNumber = severityLevel.number,
    informedEntities = informedEntityList.map { selector ->
        RtEntitySelector(
            routeId = selector.routeId,
            stopId = selector.stopId,
        )
    },
)

private fun GtfsRealtime.TranslatedString.toRtTranslatedString(): RtTranslatedString =
    RtTranslatedString(translations = translationList.map { it.text })
