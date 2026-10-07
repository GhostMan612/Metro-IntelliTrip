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
- `core/atlas-transit`, realtime provider models.

## Requirements
- Poll conservatively; no assumed websocket.
- Every payload has timestamp/age.
- Malformed entities isolated and logged without personal data.

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
