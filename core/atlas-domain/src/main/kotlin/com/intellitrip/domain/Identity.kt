package com.intellitrip.domain

@JvmInline
value class AgencyId(val value: String) {
    init { require(value.isNotBlank()) { "AgencyId must not be blank" } }
}

@JvmInline
value class FeedId(val value: String) {
    init { require(value.isNotBlank()) { "FeedId must not be blank" } }
}

data class RouteKey(val feedId: FeedId, val routeId: String) {
    init { require(routeId.isNotBlank()) }
}

data class TripKey(val feedId: FeedId, val tripId: String) {
    init { require(tripId.isNotBlank()) }
}

data class StopKey(val feedId: FeedId, val stopId: String) {
    init { require(stopId.isNotBlank()) }
}

data class VehicleKey(val feedId: FeedId, val vehicleId: String) {
    init { require(vehicleId.isNotBlank()) }
}

data class ServiceKey(val feedId: FeedId, val serviceId: String) {
    init { require(serviceId.isNotBlank()) }
}

data class ShapeKey(val feedId: FeedId, val shapeId: String) {
    init { require(shapeId.isNotBlank()) }
}