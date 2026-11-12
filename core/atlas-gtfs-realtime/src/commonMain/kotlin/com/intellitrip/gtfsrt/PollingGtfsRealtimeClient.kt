package com.intellitrip.gtfsrt

import com.intellitrip.contracts.FreshnessMetadata
import com.intellitrip.contracts.ProviderError
import com.intellitrip.contracts.ProviderResult
import com.intellitrip.domain.AlertSeverity
import com.intellitrip.domain.DataScope
import com.intellitrip.domain.FeedId
import com.intellitrip.domain.GeoPoint
import com.intellitrip.domain.GeoMath
import com.intellitrip.domain.GtfsServiceTime
import com.intellitrip.domain.RouteKey
import com.intellitrip.domain.ScheduleRelationship
import com.intellitrip.domain.ServiceAlert
import com.intellitrip.domain.StopKey
import com.intellitrip.domain.StopTimeRelationship
import com.intellitrip.domain.StopTimeUpdate
import com.intellitrip.domain.TripKey
import com.intellitrip.domain.TripUpdate
import com.intellitrip.domain.Vehicle
import com.intellitrip.domain.VehicleKey
import com.intellitrip.domain.VehicleType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class GtfsRealtimeException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Fetches a GTFS-Realtime payload. Acquisition is always explicit. */
fun interface GtfsRealtimeFetcher {
    fun fetch(url: String): ByteArray

    /**
     * Async variant on the browser `fetch` bridge (M4c). The default keeps
     * JVM behavior (sync read on the caller's pool); wasmJs overrides it.
     */
    suspend fun fetchAsync(url: String): ByteArray =
        kotlinx.coroutines.withContext(ioDispatcherForFetch()) { fetch(url) }

    private fun ioDispatcherForFetch(): kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.Default
}

/**
 * Maps a decoded GTFS-Realtime feed into feed-scoped domain models.
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

    /** Parses a GTFS `yyyyMMdd` date (formerly `BASIC_ISO_DATE`); also accepts ISO `yyyy-MM-dd`. */
    private fun parseGtfsDate(value: String): LocalDate {
        if (value.contains('-')) return LocalDate.parse(value)
        val year = value.substring(0, 4).toInt()
        val month = value.substring(4, 6).toInt()
        val day = value.substring(6, 8).toInt()
        return LocalDate(year, month, day)
    }

    fun vehicles(feed: RtFeedMessage): List<Vehicle> =
        feed.entities.mapNotNull { entity ->
            val vehicle = entity.vehiclePosition ?: return@mapNotNull null
            val position = vehicle.position ?: return@mapNotNull null
            val latitude = position.latitude ?: return@mapNotNull null
            val longitude = position.longitude ?: return@mapNotNull null
            val vehicleId = vehicle.vehicle?.id?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val trip = vehicle.trip
            val timestamp = vehicle.timestamp.takeIf { it > 0 }?.let { Instant.fromEpochSeconds(it) }
                ?: feed.headerTimestamp.takeIf { it > 0 }?.let { Instant.fromEpochSeconds(it) }
            val routeId = trip?.routeId?.takeIf { it.isNotBlank() }
            Vehicle(
                key = VehicleKey(feedId, vehicleId),
                routeKey = routeId?.let { RouteKey(feedId, it) },
                tripKey = trip?.tripId?.takeIf { it.isNotBlank() }?.let { TripKey(feedId, it) },
                position = GeoPoint(latitude, longitude),
                bearing = position.bearing,
                speedMps = position.speed,
                updatedAt = timestamp ?: Instant.DISTANT_PAST,
                vehicleType = vehicleType(routeId?.let { routeTypes[it] }),
            )
        }

    fun tripUpdates(feed: RtFeedMessage): List<TripUpdate> =
        feed.entities.mapNotNull { entity ->
            val update = entity.tripUpdate ?: return@mapNotNull null
            val trip = update.trip ?: return@mapNotNull null
            val tripId = trip.tripId.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            TripUpdate(
                tripKey = TripKey(feedId, tripId),
                routeKey = RouteKey(feedId, trip.routeId),
                vehicleKey = update.vehicle?.id?.takeIf { it.isNotBlank() }?.let { VehicleKey(feedId, it) },
                directionId = trip.directionId,
                startDate = trip.startDate.takeIf { it.isNotBlank() }?.let {
                    runCatching { parseGtfsDate(it) }.getOrNull()
                },
                startTime = trip.startTime.takeIf { it.isNotBlank() }?.let {
                    runCatching { GtfsServiceTime.parse(it) }.getOrNull()
                },
                timestamp = update.timestamp?.let { Instant.fromEpochSeconds(it) },
                scheduleRelationship = ScheduleRelationship.SCHEDULED,
                stopTimeUpdates = update.stopTimeUpdates.map { stopUpdate ->
                    StopTimeUpdate(
                        stopKey = StopKey(feedId, stopUpdate.stopId),
                        arrivalDelaySeconds = stopUpdate.arrival?.delay,
                        departureDelaySeconds = stopUpdate.departure?.delay,
                        arrivalTime = stopUpdate.arrival?.time?.let(Instant::fromEpochSeconds),
                        departureTime = stopUpdate.departure?.time?.let(Instant::fromEpochSeconds),
                        scheduleRelationship = stopTimeRelationship(stopUpdate.scheduleRelationship),
                    )
                },
            )
        }

    fun alerts(feed: RtFeedMessage): List<ServiceAlert> =
        feed.entities.mapNotNull { entity ->
            val alert = entity.alert ?: return@mapNotNull null
            val headline = alert.headerText.translations.firstOrNull()
                ?: alert.descriptionText.translations.firstOrNull()
                ?: return@mapNotNull null
            ServiceAlert(
                id = entity.id,
                headline = headline,
                severity = alertSeverity(alert.severityLevelNumber),
                affectedRoutes = alert.informedEntities.mapNotNull { selector ->
                    selector.routeId.takeIf { it.isNotBlank() }?.let { RouteKey(feedId, it) }
                }.toSet(),
                affectedStops = alert.informedEntities.mapNotNull { selector ->
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
        SEVERITY_SEVERE -> AlertSeverity.SEVERE
        SEVERITY_WARNING -> AlertSeverity.WARNING
        else -> AlertSeverity.INFO
    }

    /**
     * GTFS-Realtime StopTimeUpdate.ScheduleRelationship:
     * SCHEDULED=0, SKIPPED=1, NO_DATA=2, UNSCHEDULED=3. Unknown wire values
     * are treated as NO_DATA, never as a live prediction.
     */
    private fun stopTimeRelationship(value: Int): StopTimeRelationship = when (value) {
        0 -> StopTimeRelationship.SCHEDULED
        1 -> StopTimeRelationship.SKIPPED
        2 -> StopTimeRelationship.NO_DATA
        3 -> StopTimeRelationship.UNSCHEDULED
        else -> StopTimeRelationship.NO_DATA
    }

    private companion object {
        // GTFS-Realtime Alert.SeverityLevel: UNKNOWN_SEVERITY=1, INFO=2,
        // WARNING=3, SEVERE=4. The generated bindings use this numbering.
        const val SEVERITY_SEVERE = 4
        const val SEVERITY_WARNING = 3
    }
}

/** Marks a failure thrown by [GtfsRealtimeFetcher] (as opposed to decoding). */
private class FetchFailure(cause: Throwable) : RuntimeException(cause)

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
    private val clock: () -> Instant = { Clock.System.now() },
    // `Dispatchers.IO` is JVM-only (unresolved on wasmJs); Default is the
    // portable choice. Fetch+decode are short and bounded, so the CPU pool
    // is acceptable (an expect/actual IO dispatcher is a later refinement).
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.Default,
    /** Defaults to the platform decoder (JVM: Java protobuf; wasmJs: stub). */
    private val decoder: FeedDecoder = platformFeedDecoder(),
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

    private fun <T> poll(url: String, transform: (RtFeedMessage) -> T): Flow<ProviderResult<T>> =
        flow {
            var backoff = pollIntervalMillis
            while (currentCoroutineContext().isActive) {
                val fetchedAt = clock()
                val result: ProviderResult<T> = try {
                    // acquisition and parsing run off the caller's thread so callers
                    // may collect from the main dispatcher without blocking it.
                    withContext(ioDispatcher) {
                        val bytes = try {
                            fetcher.fetchAsync(url)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            throw FetchFailure(e)
                        }
                        val feed = decoder.decode(bytes)
                        val sourceTimestamp = feed.headerTimestamp.takeIf { it > 0 }
                            ?.let { Instant.fromEpochSeconds(it) }
                        ProviderResult.Success(
                            data = transform(feed),
                            freshness = FreshnessMetadata(
                                sourceTimestamp = sourceTimestamp,
                                fetchedAt = fetchedAt,
                                age = fetchedAt - (sourceTimestamp ?: fetchedAt),
                                providerId = providerId,
                            ),
                        )
                    }
                } catch (e: FetchFailure) {
                    ProviderResult.NetworkFailure(ProviderError("Network failure for $url", e.cause))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    ProviderResult.MalformedResponse(ProviderError("Malformed GTFS-Realtime payload for $url", e))
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

    private fun haversineMeters(a: GeoPoint, b: GeoPoint): Double = GeoMath.distanceMeters(a, b)
}


