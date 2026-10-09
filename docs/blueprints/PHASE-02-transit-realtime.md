# Phase 02 — Transit Realtime

## Objective
Ingest GTFS-RT vehicles, trip updates, and alerts through provider contracts.

## Prerequisites
- Phase 01 static identities available.

## In scope
- GTFS-RT vehicle positions, trip updates, service alerts.
- Polling/backoff.
- Stale/malformed handling.
- Replay fixtures.

## Out of scope
- Live map polish.
- Routing.
- Weather.

## Components affected
- A new pure Kotlin/JVM realtime GTFS-RT adapter module (e.g. `core/atlas-gtfs-realtime`), `core/atlas-domain`, `core/atlas-contracts`. The legacy `core/atlas-transit` prototype stays quarantined and is not extended.

## Requirements
- Poll conservatively; no assumed websocket.
- Every payload has timestamp/age and carries `FreshnessMetadata`.
- Malformed entities isolated and logged without personal data.
- Realtime feeds are explicitly associated with the static `FeedId` they extend (ADR-011); `FeedEntity.id` is not interchangeable with static `FeedId`.
- `VehicleKey` uses the feed-scoped source namespace; `VehicleDescriptor.id` may be absent, and no persistent `VehicleKey` may be fabricated from trip ids or coordinates.

## Tests/gates
- `.pb` replay tests pass.
- Stale data marked.
- Provider outage does not crash engine.

## Definition of Done
- Realtime transit snapshots available with freshness metadata.

## Freeze gate
- Realtime contracts stable before map rendering integration.

## Artifacts
- GTFS-RT client, fixture replay tests, freshness model.

## Handoff
- Phase 03 can render live map layers.
