# Phase 05 — Scope Engine

## Objective
Implement continuous scope as a first-class engine concept.

## Prerequisites
- Live transit and weather layer composition working.

## In scope
- `ScopeState` model.
- Focus targets: trip/route/vehicle/radius/viewport/region/system/none.
- Camera/data/render scope separation.
- Scope-driven data requests and render LOD.

## Out of scope
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
