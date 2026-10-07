# Domain Model (draft)

```kotlin
enum class Scope { MY_TRIP, ROUTE, REGION, SYSTEM }

data class Vehicle(
    val id: String,
    val routeId: String?,
    val tripId: String?,
    val position: LatLng,
    val bearing: Float?,
    val speedMps: Float?,
    val updatedAt: Instant,
    val vehicleType: VehicleType, // BUS, RAIL, OTHER
)

data class Route(val id: String, val shortName: String, val longName: String, val color: Int?)

data class Stop(val id: String, val name: String, val location: LatLng)

data class Trip(
    val id: String,
    val plannedStart: Instant,
    val legs: List<Leg>, // walk / ride segments
)

data class WeatherOverlay(val kind: RadarFrame | Alert | Forecast)
data class RadarFrame(val tileUrlTemplate: String, val timestamp: Instant)
data class WeatherAlert(val headline: String, val severity: String, val areas: String)

data class ScopeState(val scope: Scope, val followedVehicleId: String?, val followedRouteId: String?)
```

Rules:
- Domain types are pure Kotlin — no Android imports.
- Map rendering consumes immutable snapshots of these models.
