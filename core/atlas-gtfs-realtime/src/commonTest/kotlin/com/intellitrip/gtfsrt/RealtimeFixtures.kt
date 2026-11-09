package com.intellitrip.gtfsrt

internal object RealtimeFixtures {

    fun vehiclePositionsFeed(): RtFeedMessage = RtFeedMessage(
        headerTimestamp = 1_760_000_000,
        entities = listOf(
            RtFeedEntity(
                id = "vp-1",
                vehiclePosition = RtVehiclePosition(
                    trip = RtTripDescriptor(
                        tripId = "1005229",
                        routeId = "901",
                        directionId = 1,
                    ),
                    position = RtPosition(
                        latitude = 44.892864,
                        longitude = -93.19815,
                    ),
                    timestamp = 1_760_000_000,
                    vehicle = RtVehicleDescriptor(id = "bus-4242"),
                ),
            ),
            RtFeedEntity(
                id = "vp-anonymous",
                vehiclePosition = RtVehiclePosition(
                    trip = RtTripDescriptor(tripId = "1005229"),
                    position = RtPosition(
                        latitude = 44.9,
                        longitude = -93.2,
                    ),
                ),
            ),
        ),
    )

    fun tripUpdatesFeed(): RtFeedMessage = RtFeedMessage(
        headerTimestamp = 1_760_000_000,
        entities = listOf(
            RtFeedEntity(
                id = "tu-1",
                tripUpdate = RtTripUpdate(
                    trip = RtTripDescriptor(
                        tripId = "1005229",
                        routeId = "901",
                        directionId = 1,
                        startDate = "20261005",
                        startTime = "25:10:00",
                    ),
                    vehicle = RtVehicleDescriptor(id = "bus-4242"),
                    timestamp = 1_760_000_000,
                    stopTimeUpdates = listOf(
                        RtStopTimeUpdate(
                            stopId = "34",
                            arrival = RtStopTimeEvent(delay = 120),
                            departure = RtStopTimeEvent(delay = 150),
                        ),
                    ),
                ),
            ),
        ),
    )

    fun alertsFeed(): RtFeedMessage = RtFeedMessage(
        headerTimestamp = 1_760_000_000,
        entities = listOf(
            RtFeedEntity(
                id = "alert-1",
                alert = RtAlert(
                    headerText = RtTranslatedString(
                        translations = listOf("Blue Line: buses replace trains"),
                    ),
                    informedEntities = listOf(
                        RtEntitySelector(routeId = "901", stopId = "34"),
                    ),
                ),
            ),
        ),
    )

    fun malformedPayload(): ByteArray = "this is definitely not a protobuf feed message".toByteArray()
}
