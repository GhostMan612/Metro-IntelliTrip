# Phase 01 — Transit Static

## Objective
Model and load static transit network data.

## Prerequisites
- Phase 00 freeze cleared.

## In scope
- Static GTFS download/cache/parse for routes, stops, shapes, trips.
- Transit static provider-neutral model: agencies, routes, stops, trips, stop_times, calendars, calendar_dates, shapes, frequencies, transfers, feed metadata.
- Transit static provider contract.
- Namespaced identity mapping (`RouteKey`, `TripKey`, `StopKey`, `AgencyId`, `FeedId`).
- Daily lightweight conditional refresh check (`If-Modified-Since`/`Last-Modified`, 304 keep, 200 validate + atomic swap).
- Fixture-based parsing tests.

## Out of scope
- Realtime vehicles/trip updates/alerts.
- Map rendering beyond debug list/preview.
- Routing.

## Components affected
- `core/atlas-transit`, domain transit models.

## Requirements
- Provider DTO mapping stays at edge.
- Parsed feed carries feedVersion/fetchedAt/hash and feed validity window.
- Malformed rows handled safely.
- Model supports visualization, trip lookup, stop sequencing, service-day validity, routing, transfers, offline planning.
- Static identities use `AgencyId`/`RouteKey`/`TripKey`/`StopKey`, not raw GTFS IDs.

## Tests/gates
- Fixture GTFS zip parses.
- Shape sequence ordered correctly.
- Stale/missing feed metadata handled.

## Definition of Done
- Static routes/stops/shapes available to engine.
- Tests deterministic with fixtures.

## Freeze gate
- Static provider API accepted before realtime work.

## Artifacts
- `TransitStaticProvider` implementation, sample fixture, docs update.

## Handoff
- Phase 02 can add realtime streams using same static identities.
