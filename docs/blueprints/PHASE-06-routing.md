# Phase 06 — Routing

## Objective
Plan origin→destination multimodal journeys through provider-neutral contracts.

## Prerequisites
- Scope engine and live transit available.

## In scope
- `RoutingProvider` contract.
- Walking and transit legs.
- Transfers.
- Realtime trip update integration hooks.
- Selected routing-provider adapter/spike (OpenTripPlanner remains a candidate; topology research gate determines implementation).

## Out of scope
- Turn-by-turn navigation.
- Transfer confidence scoring beyond placeholder.
- Fare payment.

## Components affected
- routing module, trip planner UI later, provider adapters.

## Requirements
- Provider-neutral `JourneyOption` with `Leg.Transit` carrying agency/feed, route key, trip key, from/to stop keys, scheduled/predicted departure/arrival, realtime delay/status, assigned/live vehicle key, service date/trip instance.
- Realtime updates can annotate journeys.
- Clear failure when routing unavailable.
- Topology research gate: decide app -> `RoutingProvider` -> self-hosted OTP service / trusted public service / local offline engine / hybrid before implementing the selected routing-provider adapter (ADR-007). OTP alone on Android is not assumed.
- Transfer confidence is modeled via `TransferConnection` objects, scored in Phase 07.

## Tests/gates
- Golden routing fixtures.
- Multimodal transfer mapping tests.
- Provider outage behavior tests.

## Definition of Done
- App can request journey options for an origin/destination.

## Freeze gate
- Routing contract stable before transfer intelligence.

## Artifacts
- Routing models/provider, selected routing-provider adapter/spike, tests.

## Status
Implemented for the static-network provider. `core/atlas-routing` contains `RoutingNetwork` (routes/stops/trips/stop times/transfers), `StaticNetworkPlanner` (walking legs, transit legs, transfer connections, service-day time conversion with agency timezone, realtime annotation that never rewrites the schedule) and an `OpenTripPlannerRoutingProvider` spike behind an injectable transport. Journey-level confidence was removed in favour of per-connection `TransferConnection`. The OTP topology research gate (ADR-007) remains OPEN: the adapter assumes only that an OTP-compatible service is reachable, and a full OTP itinerary mapper is deferred until that gate closes and a response fixture is captured.

## Handoff
- Phase 07 can score transfer confidence.
