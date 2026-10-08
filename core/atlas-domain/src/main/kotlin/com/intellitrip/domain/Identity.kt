package com.intellitrip.domain

@JvmInline
value class AgencyId(val value: String) {
    init { require(value.isNotBlank()) { "AgencyId must not be blank" } }
}

@JvmInline
value class FeedId(val value: String) {
    init { require(value.isNotBlank()) { "FeedId must not be blank" } }
}

data class RouteKey(val agencyId: AgencyId, val routeId: String) {
    init { require(routeId.isNotBlank()) }
}
data class TripKey(val agencyId: AgencyId, val tripId: String) {
    init { require(tripId.isNotBlank()) }
}
data class StopKey(val agencyId: AgencyId, val stopId: String) {
    init { require(stopId.isNotBlank()) }
}
data class VehicleKey(val agencyId: AgencyId, val vehicleId: String) {
    init { require(vehicleId.isNotBlank()) }
}
