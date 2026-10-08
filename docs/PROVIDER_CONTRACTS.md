# Provider Contracts

All provider-specific SDKs map into these contracts at the edge. Core domain remains provider-neutral.

## Result and freshness semantics
Providers return a coherent result type equivalent to `ProviderResult<T>`:
- `Success(data, freshness)` — fresh data.
- `StaleSuccess(data, freshness)` — usable but older than preferred; never silently treated as fresh.
- `Unavailable` — provider cannot serve data right now.
- `RateLimited(retryAfter?)`
- `AuthFailure`
- `MalformedResponse` — rejected/logged safely.
- `PartialResult(data, missing)` — subset of requested entities.
- `Timeout` / `NetworkFailure`

Common metadata on every result (`FreshnessMetadata`):
- `sourceTimestamp` — when the provider produced the data, if known.
- `fetchedAt` — when this client fetched it.
- `age`/`freshness` — derived staleness indication.
- `providerId` — which provider produced it.

Streaming contracts use `Flow<ProviderResult<T>>`; one-shot contracts use `suspend` functions returning `ProviderResult<T>`.

Central rule:
- one-shot provider call -> `suspend` -> `ProviderResult<T>`
- continuous provider stream -> `Flow<ProviderResult<T>>`

## TransitStaticProvider
All operations are one-shot: `suspend` functions returning `ProviderResult<T>`.

- `suspend fun agencies(): ProviderResult<List<Agency>>`
- `suspend fun routes(agencyId: AgencyId? = null): ProviderResult<List<Route>>`
- `suspend fun stops(agencyId: AgencyId? = null): ProviderResult<List<Stop>>`
- `suspend fun trips(routeKey: RouteKey? = null): ProviderResult<List<Trip>>`
- `suspend fun stopTimes(tripKey: TripKey): ProviderResult<List<StopTime>>`
- `suspend fun calendar(): ProviderResult<List<Calendar>>`
- `suspend fun calendarDates(): ProviderResult<List<CalendarDate>>`
- `suspend fun shapes(routeKey: RouteKey? = null): ProviderResult<List<Shape>>`
- `suspend fun frequencies(): ProviderResult<List<Frequency>>`
- `suspend fun transfers(): ProviderResult<List<Transfer>>`
- `suspend fun feedMetadata(): ProviderResult<FeedMetadata>`
- Provider DTO mapping stays at the adapter edge.
- `AgencyId` is Atlas-normalized and globally collision-safe; it is not assumed to equal raw GTFS `agency_id`. Raw agency IDs remain at adapter edge.

## TransitRealtimeProvider
- `vehiclePositions(scope: DataScope): Flow<ProviderResult<List<Vehicle>>>`
- stale data marked via freshness metadata; malformed entities rejected/logged
- optional 5s refresh target; client may throttle; adapter may fetch broader and filter locally to the scope

## TripUpdateProvider
- `tripUpdates(scope: DataScope): Flow<ProviderResult<List<TripUpdate>>>`

## ServiceAlertProvider
- `alerts(): Flow<ProviderResult<List<ServiceAlert>>>`

## RoutingProvider
- `suspend fun plan(request: TripPlanRequest): ProviderResult<List<JourneyOption>>`
- provider-agnostic; OTP is a strong candidate, not baked into domain
- supports multimodal legs, `TransferConnection` objects, realtime updates
- routing topology is unresolved — see ADR-007

## WeatherForecastProvider
- `suspend fun forecast(location: GeoPoint): ProviderResult<Forecast>`

## WeatherAlertProvider
- `alerts(bounds: LatLngBounds?): Flow<ProviderResult<List<WeatherAlert>>>`

## RadarProvider
- `suspend fun latestFrame(): ProviderResult<RadarFrame?>`
- `suspend fun frames(since: Instant): ProviderResult<List<RadarFrame>>`

## BasemapProvider
- `styleUrl(): String` or local style asset
- tile/style metadata, attribution, and terms surfaced
- distinct from `MapRenderer`: `BasemapProvider` supplies CARTO/offline PMTiles/future tile styles; `MapRenderer` (MapLibre adapter) renders them

## TrafficProvider (future)
- `incidents(bounds: LatLngBounds?): Flow<ProviderResult<List<TrafficIncident>>>`
- `speeds(bounds: LatLngBounds?): Flow<ProviderResult<List<TrafficFlowSegment>>>`

## Provider identity / user agent
HTTP clients must identify themselves with an appropriate `User-Agent` including an application contact identifier per provider requirements (notably NWS). The identity is adapter-configurable; no false identity is hardcoded and no personal contact data or secrets are committed.

## Auth/credential rule
Providers declare `requiresAuth: Boolean`. If true, credentials come from secure local storage/env, never committed.
