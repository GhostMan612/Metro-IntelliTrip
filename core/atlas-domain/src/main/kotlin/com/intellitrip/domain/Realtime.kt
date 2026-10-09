package com.intellitrip.domain

import java.time.Instant

data class StopTimeUpdate(
    val stopKey: StopKey,
    val arrivalDelaySeconds: Int?,
    val departureDelaySeconds: Int?,
)

data class TripUpdate(
    val tripKey: TripKey,
    val routeKey: RouteKey,
    val vehicleKey: VehicleKey?,
    val directionId: Int?,
    val startDate: java.time.LocalDate?,
    val startTime: GtfsServiceTime?,
    val timestamp: Instant?,
    val scheduleRelationship: ScheduleRelationship,
    val stopTimeUpdates: List<StopTimeUpdate>,
)

enum class ScheduleRelationship { SCHEDULED, ADDED, UNSCHEDULED, CANCELED, DUPLICATED, REPLACEMENT }

data class VehicleTypeCounts(
    val routeTypeCounts: Map<Int, Int>,
    val exactRevenue: Boolean,
)