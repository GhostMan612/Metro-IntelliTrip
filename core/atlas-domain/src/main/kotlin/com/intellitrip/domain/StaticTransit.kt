package com.intellitrip.domain

import java.time.LocalDate

data class Route(
    val key: RouteKey,
    val agencyId: AgencyId?,
    val shortName: String?,
    val longName: String?,
    val description: String?,
    val routeType: Int,
    val url: String?,
    val color: String?,
    val textColor: String?,
    val sortOrder: Int?,
)

data class Stop(
    val key: StopKey,
    val code: String?,
    val name: String,
    val description: String?,
    val location: GeoPoint,
    val url: String?,
    val locationType: Int?,
    val parentStation: StopKey?,
    val zoneId: String?,
)

data class Trip(
    val key: TripKey,
    val routeKey: RouteKey,
    val serviceKey: ServiceKey,
    val headsign: String?,
    val directionId: Int?,
    val blockId: String?,
    val shapeKey: ShapeKey?,
    val wheelchairAccessible: Int?,
)

data class StopTime(
    val tripKey: TripKey,
    val stopKey: StopKey,
    val stopSequence: Int,
    val arrivalTime: GtfsServiceTime?,
    val departureTime: GtfsServiceTime?,
    val pickupType: Int?,
    val dropOffType: Int?,
    val timepoint: Int?,
    val shapeDistTraveled: Double?,
)

data class Calendar(
    val serviceKey: ServiceKey,
    val daysOfWeek: Set<java.time.DayOfWeek>,
    val startDate: LocalDate,
    val endDate: LocalDate,
)

data class CalendarDate(
    val serviceKey: ServiceKey,
    val date: LocalDate,
    val exceptionType: ServiceExceptionType,
)

data class Shape(
    val key: ShapeKey,
    val points: List<GeoPoint>,
)

data class Frequency(
    val tripKey: TripKey,
    val startTime: GtfsServiceTime,
    val endTime: GtfsServiceTime,
    val headwaySeconds: Int,
    val exactTimes: Int?,
)

data class Transfer(
    val fromStopKey: StopKey,
    val toStopKey: StopKey,
    val type: TransferType,
    val minTransferSeconds: Int?,
)

enum class ServiceExceptionType { ADDED, REMOVED }

enum class TransferType { RECOMMENDED, TIMED, TRANSFER, NO_TRANSFER }

data class FeedMetadata(
    val feedId: FeedId,
    val publisherName: String?,
    val publisherUrl: String?,
    val lang: String?,
    val version: String?,
    val hash: String?,
    val fetchedAt: java.time.Instant,
    val validFrom: LocalDate?,
    val validTo: LocalDate?,
    val sourceUrl: String?,
)