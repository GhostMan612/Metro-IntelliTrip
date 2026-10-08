# IntelliTrip — Authoritative Architecture

## Vision
IntelliTrip is one Android application backed by the Atlas sovereign geospatial engine. The engine combines live transit, trip planning, realtime vehicle intelligence, weather/radar, and scalable map scope, with offline capability as an eventual target.

## Layers
```text
App Shell (Kotlin + Jetpack Compose)
  - map screen, scope controls, weather layers, trip planner, settings, dev/advanced mode

Atlas Engine (portable, provider-neutral)
  - domain/: pure Kotlin models and rules
  - providers/: transit, routing, weather, basemap, traffic contracts
  - scope/: camera/data/render scope coordination
  - render/: viewport culling, LOD, clustering, layer composition
  - platform/: persistence, cache, permissions, scheduling

Providers
  - Metro Transit, NOAA/NWS/MRMS/IEM, RainViewer (provisional), CARTO, MapLibre, future agencies/traffic
```

## Core principles
- Domain modules must not import Android framework types.
- Providers implement generic contracts; provider-specific payloads stay at the edge.
- Camera scope, data scope, and render scope are independent.
- System-wide rendering is a target; scalability is solved with filtering, culling, LOD, and clustering, not artificial radius caps.
- Default UX is simple; advanced capabilities remain available in the same app.

## Identity
Raw GTFS/provider IDs are not assumed globally unique. Cross-provider references use namespaced identities: `AgencyId`, `FeedId`, `RouteKey(agencyId, routeId)`, `TripKey(agencyId, tripId)`, `StopKey(agencyId, stopId)`, `VehicleKey(agencyId, vehicleId)`. `AgencyId` is Atlas-normalized and globally collision-safe; it is not assumed to equal raw GTFS `agency_id`. Raw provider identifiers remain at the adapter edge. See `DOMAIN_MODEL.md`.

## Static transit
The provider-neutral model covers agencies, routes, stops, trips, stop_times, calendars, calendar_dates, shapes, frequencies, transfers, and feed metadata — sufficient for visualization, trip lookup, stop sequencing, service-day validity, routing, transfers, and offline planning. Phase 01 implements this model.

## Scope resolution
Scope is resolved as `Focus -> ScopePolicy/ScopeResolver -> CameraScope/DataScope/RenderScope`. Phase 00/01 establish scope primitives and the resolver contract; Phase 03 consumes them; Phase 05 expands policy/UI/advanced behavior. See `SCOPE_MODEL.md`.

## Journey/transfer model
`Leg.Transit` carries agency/feed, route key, trip key, from/to stop keys, scheduled/predicted departure/arrival, realtime delay/status, assigned/live vehicle key, and service date/trip instance. Transfer confidence lives on `TransferConnection` (arrivingLeg, departingLeg, transferStop, scheduledBuffer, predictedBuffer, walkingDuration, confidence, rationale); a `JourneyOption` has 0..n `TransferConnection`s. Phase 07 scores transfers via connection objects.

## Routing as core
Routing is a core capability. Initial implementation may use a provider such as OpenTripPlanner, but the domain contract must support multimodal journeys: walking, transit legs, transfers, realtime updates, and later transfer-confidence intelligence. Topology is unresolved (see ADR-007): app → `RoutingProvider` → self-hosted OTP service / trusted public service / local offline engine / hybrid. Research gate before implementation.

## SDK portability
`core/` modules are pure Kotlin with no Android framework imports; `app/`, `platform/`, and provider adapters are isolated. Prototype code that violates this architecture must be audited, quarantined, or refactored before it is treated as authoritative. See ADR-004 and Phase 09.

## Provider contracts
See `PROVIDER_CONTRACTS.md`. Providers return a coherent `ProviderResult<T>` (success, stale success, unavailable, rate limited, auth failure, malformed response, partial result, timeout/network failure) with `FreshnessMetadata` (source timestamp, fetchedAt, age/freshness, provider identity).

## Data and offline
See `OFFLINE_MODEL.md`. Static GTFS refreshes via daily conditional check (`If-Modified-Since`/`Last-Modified`, 304 keeps current, 200 validates then atomically swaps).

## Security/privacy
See `SECURITY.md`. Provider HTTP clients identify with an adapter-configurable `User-Agent`/contact identifier; no fabricated identity, secrets, or personal contact data committed.

## Rendering
See `RENDERING_MODEL.md`. `MapRenderer` (MapLibre adapter) is separate from `BasemapProvider` (CARTO/offline PMTiles/future styles). System-scale rendering uses MapLibre-native source/layer bulk updates, not one View/Compose marker per vehicle.

## Scope
See `SCOPE_MODEL.md`.

## Testing
See `TEST_STRATEGY.md`.

## Phase gates
See `blueprints/`. No phase begins until the prior freeze gate passes.
