# Domain Model

```kotlin
// Pure Kotlin. No Android imports.

data class GeoPoint(val lat: Double, val lon: Double)

data class LatLngBounds(val southWest: GeoPoint, val northEast: GeoPoint)

enum class VehicleType { BUS, RAIL, FERRY, OTHER }

// Identity: raw GTFS/provider IDs are NOT assumed globally unique (ADR-011).
// Static GTFS entity keys are FEED-SCOPED, not agency-scoped.
// `FeedId` is the stable logical feed namespace, unchanged across versions/hashes.
// `AgencyId` is a separate Atlas-normalized agency identity; it never namespaces
// static entity keys and is not assumed to equal raw GTFS `agency_id`.
@JvmInline value class AgencyId(val value: String)
@JvmInline value class FeedId(val value: String)

data class RouteKey(val feedId: FeedId, val routeId: String)
data class TripKey(val feedId: FeedId, val tripId: String)
data class StopKey(val feedId: FeedId, val stopId: String)
data class VehicleKey(val feedId: FeedId, val vehicleId: String)
data class ServiceKey(val feedId: FeedId, val serviceId: String)
data class ShapeKey(val feedId: FeedId, val shapeId: String)

// GTFS schedule time: nonnegative seconds from the service-day start.
// Hours above 24 are valid and must never be wrapped at midnight.
data class GtfsServiceTime(val secondsSinceServiceDayStart: Int)

data class Vehicle(
    val key: VehicleKey,
    val feedId: FeedId?,
    val routeKey: RouteKey?,
    val tripKey: TripKey?,
    val position: GeoPoint,
    val bearing: Double?,
    val speedMps: Double?,
    val updatedAt: Instant,
    val vehicleType: VehicleType,
)

data class Agency(
    val id: AgencyId,
    val feedId: FeedId,
    val name: String,
    val url: String?,
    val timezone: String?,
)

data class Route(
    val key: RouteKey,
    val agencyId: AgencyId?,
    val shortName: String?,
    val longName: String?,
    val routeType: Int,
    val color: Int?,
)

data class Stop(val key: StopKey, val name: String, val location: GeoPoint, val parentStation: StopKey?)

data class Trip(
    val key: TripKey,
    val routeKey: RouteKey,
    val serviceKey: ServiceKey,
    val headsign: String?,
    val directionId: Int?,
    val shapeKey: ShapeKey?,
)

data class StopTime(
    val tripKey: TripKey,
    val stopKey: StopKey,
    val stopSequence: Int,
    val arrivalTime: GtfsServiceTime?,
    val departureTime: GtfsServiceTime?,
)

data class Calendar(
    val serviceKey: ServiceKey,
    val daysOfWeek: Set<DayOfWeek>,
    val startDate: LocalDate,
    val endDate: LocalDate,
)

data class CalendarDate(
    val serviceKey: ServiceKey,
    val date: LocalDate,
    val exceptionType: ServiceExceptionType, // ADDED / REMOVED
)

data class Shape(val key: ShapeKey, val points: List<GeoPoint>)

data class Frequency(
    val tripKey: TripKey,
    val startTime: GtfsServiceTime,
    val endTime: GtfsServiceTime,
    val headwaySecs: Int,
)

data class Transfer(
    val fromStopKey: StopKey,
    val toStopKey: StopKey,
    val type: TransferType,
    val minTransferSecs: Int?,
)

data class FeedMetadata(
    val feedId: FeedId,
    val agencies: List<Agency>,
    val version: String?,
    val hash: String?,
    val fetchedAt: Instant,
    val validFrom: LocalDate?,
    val validTo: LocalDate?,
    val sourceUrl: String?,
)

enum class ServiceExceptionType { ADDED, REMOVED }
enum class TransferType { RECOMMENDED, TIMED, TRANSFER, NO_TRANSFER }

data class ServiceAlert(
    val id: String,
    val headline: String,
    val severity: AlertSeverity,
    val affectedRoutes: Set<RouteKey>,
    val affectedStops: Set<StopKey>,
    val affectsAgency: AgencyId?,
)

enum class AlertSeverity { INFO, WARNING, SEVERE }

data class TripPlanRequest(
    val origin: GeoPoint,
    val destination: GeoPoint,
    val departAt: Instant?,
    val arriveBy: Instant?,
    val maxTransfers: Int?,
)

data class JourneyOption(
    val id: String,
    val legs: List<Leg>,
    val transfers: List<TransferConnection>, // 0..n; see TransferConnection
    val departure: Instant,
    val arrival: Instant,
    val transferCount: Int,
)

sealed interface Leg {
    data class Walk(val from: GeoPoint, val to: GeoPoint, val duration: Duration) : Leg
    data class Transit(
        val agencyId: AgencyId,
        val feedId: FeedId?,
        val routeKey: RouteKey,
        val tripKey: TripKey,
        val serviceDate: LocalDate,
        val tripInstanceId: String?, // identifies a concrete trip on a service day
        val fromStopKey: StopKey,
        val toStopKey: StopKey,
        val scheduledDeparture: Instant,
        val scheduledArrival: Instant,
        val predictedDeparture: Instant?,
        val predictedArrival: Instant?,
        val realtimeDelaySeconds: Int?,
        val realtimeStatus: RealtimeStatus,
        val vehicleKey: VehicleKey?, // assigned/live vehicle
        val duration: Duration,
    ) : Leg
}

enum class RealtimeStatus { SCHEDULED, ON_TIME, DELAYED, EARLY, CANCELED, NO_DATA }

data class TransferConnection(
    val arrivingLeg: Leg,
    val departingLeg: Leg,
    val transferStop: StopKey,
    val scheduledBuffer: Duration,
    val predictedBuffer: Duration?,
    val walkingDuration: Duration?,
    val confidence: TransferConfidence,
    val rationale: String?,
)

enum class TransferConfidence { HIGH, MEDIUM, LOW, UNKNOWN }

data class WeatherAlert(val id: String, val headline: String, val severity: String, val areas: List<String>, val effective: Instant?)

data class RadarFrame(val timestamp: Instant, val tileUrlTemplate: String?, val provider: String)

data class CameraState(val center: GeoPoint, val zoom: Double, val bearing: Double, val tilt: Double)

data class ScopeState(
    val focus: Focus?,
    val cameraScope: CameraScope,
    val dataScope: DataScope,
    val renderScope: RenderScope,
)

sealed interface Focus {
    data class Trip(val key: TripKey) : Focus
    data class Route(val key: RouteKey) : Focus
    data class Vehicle(val key: VehicleKey) : Focus
    data class Radius(val center: GeoPoint, val meters: Double) : Focus
    data class Viewport(val bounds: LatLngBounds) : Focus
    data class Region(val agencyScopes: Set<AgencyId>, val bounds: LatLngBounds) : Focus
    data class TripCorridor(val tripKey: TripKey, val corridorRadiusMeters: Double) : Focus
    object System : Focus
}

data class CameraScope(val bounds: LatLngBounds?, val zoom: Double?)

data class DataScope(
    val agencies: Set<AgencyId>?,      // null = all known agencies
    val routes: Set<RouteKey>?,
    val trips: Set<TripKey>?,
    val vehicles: Set<VehicleKey>?,
    val bounds: LatLngBounds?,
    val radius: RadiusFilter?,
    val timeWindow: TimeWindow?,
    val maxAge: Duration,
    val includeAlerts: Boolean,
    val systemWide: Boolean,
)

data class RadiusFilter(val center: GeoPoint, val meters: Double)
data class TimeWindow(val from: Instant, val to: Instant)

data class RenderScope(val viewport: LatLngBounds, val zoomBucket: ZoomBucket, val maxRenderedVehicles: Int)

enum class ZoomBucket { CLUSTER, SIMPLIFIED, INDIVIDUAL }
```

Rules:
- `ScopeState` is continuous/scalable; do not collapse it into rigid app modes.
- Camera scope controls what is visible; data scope controls requests/retention; render scope controls detail level.
- Raw GTFS IDs (`routeId`, `tripId`, `stopId`, `serviceId`, `shapeId`, `vehicleId`) are scoped to the published feed; use `RouteKey`/`TripKey`/`StopKey`/`ServiceKey`/`ShapeKey`/`VehicleKey` for cross-provider references (ADR-011).
- `AgencyId` is agency identity only; it never namespaces static entity keys, and a stop served by multiple agencies keeps one `StopKey`.
- GTFS schedule times use `GtfsServiceTime` (service-day-relative seconds), never `LocalTime`; conversion to instants requires the service date and agency timezone.
- Focus uses namespaced identities; region/system focus spans multiple agencies.
- Journey-level `confidence` is removed; transfer confidence lives on `TransferConnection` with rationale.
- Provider-specific DTOs are mapped into these models at the edge.
