# Phase 05 — Scope Engine

## Objective
Expand continuous scope into first-class policy/UI/advanced behavior on the Phase 00/01 primitives.

## Prerequisites
- Live transit and weather layer composition working.
- Phase 00/01 scope primitives and resolver contract established.

## In scope
- Scope policy/UI controls and advanced behavior built on the established `ScopeState` model.
- Focus targets: trip/route/vehicle/radius/viewport/region/multiple agencies/system/trip corridor + nearby transit/none.
- Camera/data/render scope separation rules and resolver cases documented/implemented.
- Scope-driven data requests and render LOD.

## Out of scope
- Inventing new scope architectures (primitives were established in Phase 00/01; Phase 03 consumes, Phase 05 expands).
- Full routing UI.
- Offline tile packs.
- Transfer intelligence.

## Components affected
- domain, scope engine, render pipeline, app shell controls.

## Requirements
- No discrete rigid app modes.
- Default scope simple; advanced actions optional.
- Data scope may exceed render scope.

## Tests/gates
- Scope transition tests.
- Camera/data/render independence tests.
- LOD bucket tests.

## Definition of Done
- Scope controls data/render without rearchitecting providers.

## Freeze gate
- Scope engine contract stable before routing.

## Artifacts
- Scope models/engine, tests, app controls.

## Handoff
- Phase 06 can plan within scope boundaries.
