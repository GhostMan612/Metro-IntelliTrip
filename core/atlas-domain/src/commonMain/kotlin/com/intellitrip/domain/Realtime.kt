package com.intellitrip.domain

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate

/** GTFS-Realtime `StopTimeUpdate.ScheduleRelationship` for one stop. */
enum class StopTimeRelationship { SCHEDULED, SKIPPED, NO_DATA, UNSCHEDULED }

data class StopTimeUpdate(
    val stopKey: StopKey,
    val arrivalDelaySeconds: Int?,
    val departureDelaySeconds: Int?,
    /** Absolute provider event time, when the feed sends one instead of a delay. */
    val arrivalTime: Instant? = null,
    val departureTime: Instant? = null,
    /** Skipped/no-data stops must never be presented as a live prediction. */
    val scheduleRelationship: StopTimeRelationship = StopTimeRelationship.SCHEDULED,
)

data class TripUpdate(
    val tripKey: TripKey,
    val routeKey: RouteKey,
    val vehicleKey: VehicleKey?,
    val directionId: Int?,
    val startDate: LocalDate?,
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