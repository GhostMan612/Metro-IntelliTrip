# Provider Contracts

All provider-specific SDKs map into these contracts at the edge. Core domain remains provider-neutral.

## TransitStaticProvider
- `routes(): List<Route>`
- `stops(): List<Stop>`
- `shapesForRoute(routeId: String): List<GeoPoint>`
- `calendarInfo(): FeedValidity`

## TransitRealtimeProvider
- `vehiclePositions(scope: DataScope): Flow<List<Vehicle>>`
- stale data marked; malformed entities rejected/logged
- optional 5s refresh target; client may throttle

## TripUpdateProvider
- `tripUpdates(scope: DataScope): Flow<List<TripUpdate>>`

## ServiceAlertProvider
- `alerts(): Flow<List<ServiceAlert>>`

## RoutingProvider
- `plan(request: TripPlanRequest): List<JourneyOption>`
- provider-agnostic; OTP is candidate, not baked into domain
- supports multimodal legs, transfers, realtime updates

## WeatherForecastProvider
- `forecast(location: GeoPoint): Forecast`

## WeatherAlertProvider
- `alerts(bounds: LatLngBounds?): Flow<List<WeatherAlert>>`

## RadarProvider
- `latestFrame(): RadarFrame?`
- `frames(since: Instant): List<RadarFrame>`

## BasemapProvider
- `styleUrl(): String` or local style asset
- provider metadata/attribution must be surfaced

## TrafficProvider (future)
- `incidents(bounds: LatLngBounds?): Flow<List<TrafficIncident>>`
- `speeds(bounds: LatLngBounds?): Flow<List<TrafficFlowSegment>>`

## Auth/credential rule
Providers declare `requiresAuth: Boolean`. If true, credentials come from secure local storage/env, never committed.
