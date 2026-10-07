# Test Strategy

## Domain
- Pure unit tests for `ScopeState`, `Vehicle`, `JourneyOption`, stale-data rules, LOD bucket selection.

## Provider contracts
- Contract tests that each provider maps DTOs into domain models.
- Malformed/missing fields rejected safely.
- Stale timestamps preserved.

## GTFS / GTFS-RT
- Fixture zips and `.pb` samples.
- Replay tests for vehicle positions, trip updates, alerts.
- Shape parsing tests for route polylines.

## Routing
- Golden tests for origin→destination journey options.
- Transfer leg grouping tests.
- Provider-neutral model mapping tests.

## Scope engine
- Focus transitions, viewport/data/render scope separation, continuous radius behavior.

## Rendering state
- Snapshot immutability tests.
- Cluster/LOD bucket tests by zoom.
- Stale vehicle rendering state tests.

## Weather
- Radar frame freshness tests.
- Alert severity mapping tests.
- Provider outage degradation tests.

## Integration
- End-to-end provider fetch → domain snapshot → render state using fixtures.
- No live network in default tests; optional smoke tests may be explicitly enabled.

## Android/device
- Smoke test app launches, map style loads, permission flow, offline stale indicator.
