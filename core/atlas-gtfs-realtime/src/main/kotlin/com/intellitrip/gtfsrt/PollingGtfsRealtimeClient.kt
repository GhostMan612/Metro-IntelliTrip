package com.intellitrip.gtfsrt

import com.google.transit.realtime.GtfsRealtime
import com.intellitrip.contracts.FreshnessMetadata
import com.intellitrip.contracts.ProviderError
import com.intellitrip.contracts.ProviderResult
import com.intellitrip.domain.AlertSeverity
import com.intellitrip.domain.DataScope
import com.intellitrip.domain.FeedId
import com.intellitrip.domain.GeoPoint
import com.intellitrip.domain.GtfsServiceTime
import com.intellitrip.domain.RouteKey
import com.intellitrip.domain.ScheduleRelationship
import com.intellitrip.domain.ServiceAlert
import com.intellitrip.domain.StopKey
import com.intellitrip.domain.StopTimeUpdate
import com.intellitrip.domain.TripKey
import com.intellitrip.domain.TripUpdate
import com.intellitrip.domain.Vehicle
import com.intellitrip.domain.VehicleKey
import com.intellitrip.domain.VehicleType
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive

class GtfsRealtimeException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Fetches a GTFS-Realtime payload. Acquisition is always explicit. */
fun interface GtfsRealtimeFetcher {
    fun fetch(url: String): ByteArray
}

/**
 * Maps a GTFS-Realtime feed into feed-scoped domain models.
 *
 * The [feedId] supplied here is the static feed this realtime feed extends; it
 * is declared explicitly by the caller and is never inferred from
 * `FeedEntity.id` (ADR-011). Entities without a stable identity are dropped
 * rather than assigned a fabricated key.
 */
class GtfsRealtimeMapper(
    private val feedId: FeedId,
    private val routeTypes: Map<String, Int> = emptyMap(),
) {

    private val dateFormat: DateTimeFormatter = DateTimeFormatter.BASIC_ISO_DATE

    fun vehicles(feed: GtfsRealtime.FeedMessage): List<Vehicle> =
        feed.entityList.mapNotNull { entity ->
            val vehicle = entity.vehicle ?: return@mapNotNull null
            val position = vehicle.position
            if (!position.hasLatitude() || !position.hasLongitude()) return@mapNotNull null
            val vehicleId = vehicle.vehicle?.id?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val trip = vehicle.trip
            val timestamp = vehicle.timestamp.takeIf { it > 0 }?.let { Instant.ofEpochSecond(it) }
                ?: feed.header.timestamp.takeIf { it > 0 }?.let { Instant.ofEpochSecond(it) }
            val routeId = trip?.routeId?.takeIf { it.isNotBlank() }
            Vehicle(
                key = VehicleKey(feedId, vehicleId),
                routeKey = routeId?.let { RouteKey(feedId, it) },
                tripKey = trip?.tripId?.takeIf { it.isNotBlank() }?.let { TripKey(feedId, it) },
                position = GeoPoint(position.latitude.toDouble(), position.longitude.toDouble()),
                bearing = position.bearing.toDouble(),
                speedMps = position.speed.toDouble(),
                updatedAt = timestamp ?: Instant.EPOCH,
                vehicleType = vehicleType(routeId?.let { routeTypes[it] }),
            )
        }

    fun tripUpdates(feed: GtfsRealtime.FeedMessage): List<TripUpdate> =
        feed.entityList.mapNotNull { entity ->
            val update = entity.tripUpdate ?: return@mapNotNull null
            val tripId = update.trip.tripId?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            TripUpdate(
                tripKey = TripKey(feedId, tripId),
                routeKey = RouteKey(feedId, update.trip.routeId.orEmpty()),
                vehicleKey = update.vehicle?.id?.takeIf { it.isNotBlank() }?.let { VehicleKey(feedId, it) },
                directionId = if (update.trip.hasDirectionId()) update.trip.directionId else null,
                startDate = update.trip.startDate.takeIf { it.isNotBlank() }?.let {
                    runCatching { LocalDate.parse(it, dateFormat) }.getOrNull()
                },
                startTime = update.trip.startTime.takeIf { it.isNotBlank() }?.let {
                    runCatching { GtfsServiceTime.parse(it) }.getOrNull()
                },
                timestamp = if (update.hasTimestamp()) Instant.ofEpochSecond(update.timestamp) else null,
                scheduleRelationship = ScheduleRelationship.SCHEDULED,
                stopTimeUpdates = update.stopTimeUpdateList.map { stopUpdate ->
                    StopTimeUpdate(
                        stopKey = StopKey(feedId, stopUpdate.stopId),
                        arrivalDelaySeconds = if (stopUpdate.arrival.hasDelay()) stopUpdate.arrival.delay else null,
                        departureDelaySeconds = if (stopUpdate.departure.hasDelay()) stopUpdate.departure.delay else null,
                    )
                },
            )
        }

    fun alerts(feed: GtfsRealtime.FeedMessage): List<ServiceAlert> =
        feed.entityList.mapNotNull { entity ->
            val alert = entity.alert ?: return@mapNotNull null
            val headline = alert.headerText.translationList.firstOrNull()?.text
                ?: alert.descriptionText.translationList.firstOrNull()?.text
                ?: return@mapNotNull null
            ServiceAlert(
                id = entity.id,
                headline = headline,
                severity = alertSeverity(alert.severityLevel?.number),
                affectedRoutes = alert.informedEntityList.mapNotNull { selector ->
                    selector.routeId.takeIf { it.isNotBlank() }?.let { RouteKey(feedId, it) }
                }.toSet(),
                affectedStops = alert.informedEntityList.mapNotNull { selector ->
                    selector.stopId.takeIf { it.isNotBlank() }?.let { StopKey(feedId, it) }
                }.toSet(),
                affectsAgency = null,
            )
        }

    private fun vehicleType(routeType: Int?): VehicleType = when (routeType) {
        0, 3, 11, 12 -> VehicleType.RAIL
        4 -> VehicleType.FERRY
        else -> VehicleType.BUS
    }

    private fun alertSeverity(value: Int?): AlertSeverity = when (value) {
        SEVERITY_HIGH -> AlertSeverity.SEVERE
        null, SEVERITY_UNSET -> AlertSeverity.INFO
        else -> AlertSeverity.WARNING
    }

    private companion object {
        const val SEVERITY_HIGH = 1
        const val SEVERITY_UNSET = 0
    }
}

/**
 * Polling GTFS-Realtime client. Polling is conservative and backed off on
 * failure; no push transport is assumed.
 */
class PollingGtfsRealtimeClient(
    val feedId: FeedId,
    private val urls: RealtimeFeedUrls,
    private val fetcher: GtfsRealtimeFetcher,
    private val providerId: String = "gtfs-realtime",
    private val pollIntervalMillis: Long = 10_000,
    private val maxBackoffMillis: Long = 120_000,
    private val clock: () -> Instant = Instant::now,
) {

    data class RealtimeFeedUrls(
        val vehiclePositions: String,
        val tripUpdates: String,
        val alerts: String,
    )

    private val mapper = GtfsRealtimeMapper(feedId)

    fun vehiclePositions(): Flow<ProviderResult<List<Vehicle>>> =
        poll(urls.vehiclePositions, mapper::vehicles)

    fun tripUpdates(): Flow<ProviderResult<List<TripUpdate>>> =
        poll(urls.tripUpdates, mapper::tripUpdates)

    fun alerts(): Flow<ProviderResult<List<ServiceAlert>>> =
        poll(urls.alerts, mapper::alerts)

    private fun <T> poll(url: String, transform: (GtfsRealtime.FeedMessage) -> T): Flow<ProviderResult<T>> = flow {
        var backoff = pollIntervalMillis
        while (currentCoroutineContext().isActive) {
            val fetchedAt = clock()
            val result: ProviderResult<T> = try {
                val bytes = fetcher.fetch(url)
                val feed = GtfsRealtime.FeedMessage.parseFrom(bytes)
                val sourceTimestamp = feed.header.timestamp.takeIf { it > 0 }
                    ?.let { Instant.ofEpochSecond(it) }
                ProviderResult.Success(
                    data = transform(feed),
                    freshness = FreshnessMetadata(
                        sourceTimestamp = sourceTimestamp,
                        fetchedAt = fetchedAt,
                        age = Duration.between(sourceTimestamp ?: fetchedAt, fetchedAt),
                        providerId = providerId,
                    ),
                )
            } catch (e: com.google.protobuf.InvalidProtocolBufferException) {
                ProviderResult.MalformedResponse(ProviderError("Malformed GTFS-Realtime payload for $url", e))
            } catch (e: IOException) {
                ProviderResult.NetworkFailure(ProviderError("Network failure for $url", e))
            } catch (e: Exception) {
                ProviderResult.MalformedResponse(ProviderError("Unreadable GTFS-Realtime payload for $url", e))
            }
            emit(result)
            val failed = result !is ProviderResult.Success
            delay(if (failed) backoff else pollIntervalMillis)
            if (failed) backoff = (backoff * 2).coerceAtMost(maxBackoffMillis)
        }
    }
}

/** Applies a [DataScope] filter to realtime snapshots fetched for a feed. */
object RealtimeScopeFilter {

    fun filter(vehicles: List<Vehicle>, scope: DataScope): List<Vehicle> =
        vehicles.filter { vehicle ->
            val routes = scope.routes
            val trips = scope.trips
            val scopeVehicles = scope.vehicles
            val routeOk = routes == null || (vehicle.routeKey != null && vehicle.routeKey in routes)
            val tripOk = trips == null || (vehicle.tripKey != null && vehicle.tripKey in trips)
            val vehicleOk = scopeVehicles == null || vehicle.key in scopeVehicles
            val boundsOk = scope.bounds?.contains(vehicle.position) ?: true
            val radiusOk = scope.radius?.let {
                haversineMeters(it.center, vehicle.position) <= it.meters
            } ?: true
            routeOk && tripOk && vehicleOk && boundsOk && radiusOk
        }

    private fun haversineMeters(a: GeoPoint, b: GeoPoint): Double {
        val earthRadius = 6_371_000.0
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val h = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(Math.toRadians(a.lat)) * Math.cos(Math.toRadians(b.lat)) *
            Math.sin(dLon / 2) * Math.sin(dLon / 2)
        return 2 * earthRadius * Math.asin(Math.sqrt(h))
    }
}