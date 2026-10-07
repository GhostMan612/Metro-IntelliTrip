# Phase 08 — Offline

## Objective
Define and implement local static network capability and graceful degradation.

## Prerequisites
- Routing and scope engine stable.

## In scope
- Offline GTFS/static transit cache.
- Stale indicator.
- Offline routing constraints documented/implemented where feasible.
- Map tile strategy researched/selected.

## Out of scope
- Full offline live realtime.
- Guaranteed offline routing if provider cannot support it.

## Components affected
- persistence, provider cache, app shell states.

## Requirements
- Offline snapshots timestamped and versioned.
- No fake realtime when offline.
- Basemap fallback strategy explicit.
- Daily conditional GTFS check via `If-Modified-Since`/`Last-Modified`; 304 keeps current snapshot; 200 validates then atomically swaps activation; snapshots retain fetchedAt, feed validity, version/hash, and swap metadata.

## Tests/gates
- Offline fixture boot tests.
- Stale realtime tests.
- Saved trip/static network availability tests.

## Definition of Done
- Core app remains useful with degraded connectivity.

## Freeze gate
- Offline model stable before SDK extraction.

## Artifacts
- Offline cache manager, staleness model, tile strategy doc.

## Handoff
- Phase 09 can extract stable engine contracts.
