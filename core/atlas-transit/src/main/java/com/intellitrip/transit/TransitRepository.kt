package com.intellitrip.transit

import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

object TransitRepository {
    private val api: MetroTransitApi = Retrofit.Builder()
        .baseUrl("https://svc.metrotransit.org/")
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(MetroTransitApi::class.java)

    suspend fun vehicleLocations(routeId: String): List<VehicleLocation> =
        api.vehicles(routeId).map { v ->
            VehicleLocation(
                id = v.tripId ?: "${v.routeId}-${v.latitude}-${v.longitude}",
                routeId = v.routeId,
                lat = v.latitude,
                lon = v.longitude,
                bearing = v.bearing,
                speed = v.speed,
            )
        }
}
