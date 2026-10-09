package com.intellitrip.domain

import java.time.Instant

enum class VehicleType { BUS, RAIL, FERRY, OTHER }

data class Vehicle(
    val key: VehicleKey,
    val routeKey: RouteKey?,
    val tripKey: TripKey?,
    val position: GeoPoint,
    val bearing: Double?,
    val speedMps: Double?,
    val updatedAt: Instant,
    val vehicleType: VehicleType,
)

enum class AlertSeverity { INFO, WARNING, SEVERE }

data class ServiceAlert(
    val id: String,
    val headline: String,
    val severity: AlertSeverity,
    val affectedRoutes: Set<RouteKey>,
    val affectedStops: Set<StopKey>,
    val affectsAgency: AgencyId?,
)