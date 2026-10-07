package com.intellitrip.transit

import com.google.gson.annotations.SerializedName

data class RouteDto(
    @SerializedName("route_id") val routeId: String,
    @SerializedName("route_label") val label: String,
)

data class VehicleDto(
    @SerializedName("trip_id") val tripId: String?,
    @SerializedName("route_id") val routeId: String?,
    @SerializedName("direction_id") val directionId: Int?,
    @SerializedName("latitude") val latitude: Double,
    @SerializedName("longitude") val longitude: Double,
    @SerializedName("bearing") val bearing: Double?,
    @SerializedName("speed") val speed: Double?,
    @SerializedName("location_time") val locationTime: Long?,
)

data class VehicleLocation(
    val id: String,
    val routeId: String?,
    val lat: Double,
    val lon: Double,
    val bearing: Double?,
    val speed: Double?,
)
