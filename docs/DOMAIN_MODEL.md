# Domain Model

```kotlin
// Pure Kotlin. No Android imports.

data class GeoPoint(val lat: Double, val lon: Double)

data class LatLngBounds(val southWest: GeoPoint, val northEast: GeoPoint)

enum class VehicleType { BUS, RAIL, FERRY, OTHER }

data class Vehicle(
    val id: String,
    val routeId: String?,
    val tripId: String?,
    val position: GeoPoint,
    val bearing: Double?,
    val speedMps: Double?,
    val updatedAt: Instant,
    val vehicleType: VehicleType,
)

data class Route(val id: String, val shortName: String?, val longName: String?, val color: Int?)

data class Stop(val id: String, val name: String, val location: GeoPoint)

data class ServiceAlert(val id: String, val headline: String, val severity: AlertSeverity, val affectedRoutes: Set<String>)

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
    val departure: Instant,
    val arrival: Instant,
    val transfers: Int,
    val confidence: TransferConfidence?,
)

sealed interface Leg {
    data class Walk(val from: GeoPoint, val to: GeoPoint, val duration: Duration) : Leg
    data class Transit(val routeId: String, val fromStopId: String, val toStopId: String, val vehicleIds: List<String>, val duration: Duration) : Leg
}

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
    data class Trip(val id: String) : Focus
    data class Route(val id: String) : Focus
    data class Vehicle(val id: String) : Focus
    data class Radius(val center: GeoPoint, val meters: Double) : Focus
    data class Viewport(val bounds: LatLngBounds) : Focus
    data class Region(val id: String, val bounds: LatLngBounds) : Focus
    object System : Focus
}

data class CameraScope(val bounds: LatLngBounds?, val zoom: Double?)
data class DataScope(val bounds: LatLngBounds?, val maxAge: Duration, val includeAlerts: Boolean)
data class RenderScope(val viewport: LatLngBounds, val zoomBucket: ZoomBucket, val maxRenderedVehicles: Int)

enum class ZoomBucket { CLUSTER, SIMPLIFIED, INDIVIDUAL }
```

Rules:
- `ScopeState` is continuous/scalable; do not collapse it into rigid app modes.
- Camera scope controls what is visible; data scope controls requests/retention; render scope controls detail level.
- Provider-specific DTOs are mapped into these models at the edge.
