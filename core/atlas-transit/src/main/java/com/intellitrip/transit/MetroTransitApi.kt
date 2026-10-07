package com.intellitrip.transit

import retrofit2.http.GET
import retrofit2.http.Path

interface MetroTransitApi {
    @GET("nextrip/routes")
    suspend fun routes(): List<RouteDto>

    @GET("nextrip/vehicles/{route_id}")
    suspend fun vehicles(@Path("route_id") routeId: String): List<VehicleDto>
}
