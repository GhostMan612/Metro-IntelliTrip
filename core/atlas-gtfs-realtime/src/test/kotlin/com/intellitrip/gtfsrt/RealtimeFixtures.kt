package com.intellitrip.gtfsrt

import com.google.transit.realtime.GtfsRealtime

internal object RealtimeFixtures {

    fun vehiclePositionsFeed(): ByteArray =
        GtfsRealtime.FeedMessage.newBuilder()
            .setHeader(
                GtfsRealtime.FeedHeader.newBuilder()
                    .setGtfsRealtimeVersion("2.0")
                    .setTimestamp(1_760_000_000)
            )
            .addEntity(
                GtfsRealtime.FeedEntity.newBuilder()
                    .setId("vp-1")
                    .setVehicle(
                        GtfsRealtime.VehiclePosition.newBuilder()
                            .setTrip(
                                GtfsRealtime.TripDescriptor.newBuilder()
                                    .setTripId("1005229")
                                    .setRouteId("901")
                                    .setDirectionId(1)
                            )
                            .setPosition(
                                GtfsRealtime.Position.newBuilder()
                                    .setLatitude(44.892864f)
                                    .setLongitude(-93.19815f)
                            )
                            .setTimestamp(1_760_000_000)
                            .setVehicle(
                                GtfsRealtime.VehicleDescriptor.newBuilder()
                                    .setId("bus-4242")
                            )
                    )
            )
            .addEntity(
                GtfsRealtime.FeedEntity.newBuilder()
                    .setId("vp-anonymous")
                    .setVehicle(
                        GtfsRealtime.VehiclePosition.newBuilder()
                            .setTrip(GtfsRealtime.TripDescriptor.newBuilder().setTripId("1005229"))
                            .setPosition(
                                GtfsRealtime.Position.newBuilder()
                                    .setLatitude(44.9f)
                                    .setLongitude(-93.2f)
                            )
                    )
            )
            .build()
            .toByteArray()

    fun tripUpdatesFeed(): ByteArray =
        GtfsRealtime.FeedMessage.newBuilder()
            .setHeader(
                GtfsRealtime.FeedHeader.newBuilder()
                    .setGtfsRealtimeVersion("2.0")
                    .setTimestamp(1_760_000_000)
            )
            .addEntity(
                GtfsRealtime.FeedEntity.newBuilder()
                    .setId("tu-1")
                    .setTripUpdate(
                        GtfsRealtime.TripUpdate.newBuilder()
                            .setTrip(
                                GtfsRealtime.TripDescriptor.newBuilder()
                                    .setTripId("1005229")
                                    .setRouteId("901")
                                    .setDirectionId(1)
                                    .setStartDate("20261005")
                                    .setStartTime("25:10:00")
                            )
                            .setVehicle(GtfsRealtime.VehicleDescriptor.newBuilder().setId("bus-4242"))
                            .setTimestamp(1_760_000_000)
                            .addStopTimeUpdate(
                                GtfsRealtime.TripUpdate.StopTimeUpdate.newBuilder()
                                    .setStopId("34")
                                    .setArrival(
                                        GtfsRealtime.TripUpdate.StopTimeEvent.newBuilder()
                                            .setDelay(120)
                                    )
                                    .setDeparture(
                                        GtfsRealtime.TripUpdate.StopTimeEvent.newBuilder()
                                            .setDelay(150)
                                    )
                            )
                    )
            )
            .build()
            .toByteArray()

    fun alertsFeed(): ByteArray =
        GtfsRealtime.FeedMessage.newBuilder()
            .setHeader(
                GtfsRealtime.FeedHeader.newBuilder()
                    .setGtfsRealtimeVersion("2.0")
                    .setTimestamp(1_760_000_000)
            )
            .addEntity(
                GtfsRealtime.FeedEntity.newBuilder()
                    .setId("alert-1")
                    .setAlert(
                        GtfsRealtime.Alert.newBuilder()
                            .setHeaderText(
                                GtfsRealtime.TranslatedString.newBuilder()
                                    .addTranslation(
                                        GtfsRealtime.TranslatedString.Translation.newBuilder()
                                            .setText("Blue Line: buses replace trains")
                                            .setLanguage("en")
                                    )
                            )
                            .addInformedEntity(
                                GtfsRealtime.EntitySelector.newBuilder()
                                    .setRouteId("901")
                                    .setStopId("34")
                            )
                    )
            )
            .build()
            .toByteArray()

    fun malformedPayload(): ByteArray = "this is definitely not a protobuf feed message".toByteArray()
}