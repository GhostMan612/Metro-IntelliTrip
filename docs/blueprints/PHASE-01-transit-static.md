# Phase 01 — Transit Static

## Objective
Model and load static transit network data.

## Prerequisites
- Phase 00 freeze cleared.

## In scope
- Static GTFS download/cache/parse for routes, stops, shapes, trips.
- Transit static provider contract.
- Fixture-based parsing tests.

## Out of scope
- Realtime vehicles/trip updates/alerts.
- Map rendering beyond debug list/preview.
- Routing.

## Components affected
- `core/atlas-transit`, domain transit models.

## Requirements
- Provider DTO mapping stays at edge.
- Parsed feed carries feedVersion/fetchedAt.
- Malformed rows handled safely.

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
