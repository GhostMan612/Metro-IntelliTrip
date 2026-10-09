# Test Strategy

## Domain
- Pure unit tests for `ScopeState`, `Vehicle`, `JourneyOption`, stale-data rules, LOD bucket selection.
- Identity tests: feed-scoped `RouteKey`/`TripKey`/`StopKey`/`ServiceKey`/`ShapeKey`/`VehicleKey` disambiguate same raw IDs across feeds; shared stops keep one identity within a feed; `AgencyId` does not affect entity identity; `FeedId` survives snapshot version/hash changes (ADR-011).
- GTFS service-time tests: `GtfsServiceTime` parses strict `H:MM:SS`/`HH:MM:SS`, preserves values beyond 24h, never wraps at midnight, formats deterministically, rejects malformed values.
- Journey model tests: `Leg.Transit` field mapping, `TripInstance`/service date, `TransferConnection` buffers and rationale; journey no longer carries a journey-level confidence.

## Provider contracts
- Contract tests that each provider maps DTOs into domain models.
- Malformed/missing fields rejected safely.
- Stale timestamps preserved; `ProviderResult` kinds (success, stale success, unavailable, rate limited, auth failure, malformed, partial, timeout/network) mapped consistently.
- `FreshnessMetadata` (source timestamp, fetchedAt, age, providerId) present on all results.

## Scope resolver
- ScopeResolver maps each Focus (trip, route, vehicle, radius, viewport, region, multiple agencies, system, trip corridor + nearby transit) to expected CameraScope/DataScope/RenderScope.
- Implemented (`core/atlas-scope`): per-focus resolution, continuous radius zoom (closer radius = closer zoom), camera/data/render independence, agency-scoped region filtering, trip-corridor radius, render budget scaling with LOD bucket.
- Adapter-broad-fetch-plus-local-filter behavior tests.

## GTFS / GTFS-RT
- Static (Phase 01, `core/atlas-gtfs-static`): fixture GTFS ZIP parses; optional files absent is tolerated; missing required file/unknown references rejected; stop times ordered by `stop_sequence`; shape points ordered by `shape_pt_sequence`; service times beyond 24h preserved; snapshot activation and conditional-refresh (304 keep, 200 validate+activate, invalid payload keeps previous snapshot) tests; static provider returns `ProviderResult`.
- Realtime (Phase 02, not yet implemented): `.pb` replay fixtures for vehicle positions, trip updates, alerts; feed association and `VehicleKey` resolution tests.
- Shape parsing tests for route polylines.

## Routing
- Golden tests for origin–destination journey options.
- Transfer leg grouping tests.
- `TransferConnection` computation tests (scheduled/predicted buffer, walking duration, confidence, rationale).
- Provider-neutral model mapping tests.
- Implemented (`core/atlas-routing`): multimodal journey shape (walk/transit/walk), transit-leg identity and schedule, direct vs transfer journeys, transfer-connection linkage to its two legs, realtime annotation without schedule rewriting, unreachable-destination `PartialResult`, service-time conversion past midnight, OTP URL construction and unreachable-service behaviour.

## Scope engine
- Focus transitions, viewport/data/render scope separation, continuous radius behavior.

## Rendering state
- Snapshot immutability tests.
- Cluster/LOD bucket tests by zoom.
- Stale vehicle rendering state tests.
- Implemented (`core/atlas-map`): viewport culling, cluster-vs-individual selection, vehicle budget/truncation reporting, interpolation between realtime snapshots (including no-prior-snapshot case), bulk GeoJSON output, update throttling.
- Pipeline order tests: snapshot -> spatial filter -> viewport cull -> LOD -> bulk source update.

## Weather
- Radar frame freshness tests.
- Alert severity mapping tests.
- Provider outage degradation tests.
- Implemented (`core/atlas-weather`): RainViewer index parsing (past + nowcast frames, unusable payload rejected), frame filtering by `since`, network-failure propagation, NWS alert mapping (severity, urgency, areas, timestamps), User-Agent enforcement before any NWS call, layer composition order (weather below transit), default-off behaviour, degraded composition.

## Integration
- End-to-end provider fetch → domain snapshot → render state using fixtures.
- No live network in default tests; optional smoke tests may be explicitly enabled.

## Android/device
- Smoke test app launches, map style loads, permission flow, offline stale indicator.
