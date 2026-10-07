# Phase 04 — Weather

## Objective
Add radar/weather alerts through provider contracts while preserving simple default UX.

## Prerequisites
- Phase 03 layer composition stable.

## In scope
- Radar provider MVP.
- Weather alerts.
- Layer toggle/composer.
- RainViewer/NWS adapter boundaries.

## Out of scope
- Weather-driven routing.
- Offline radar guarantees.
- Complex advanced radar products unless wired through contracts.

## Components affected
- weather module, app layer controls.

## Requirements
- Weather layers compose with transit layers.
- Provider outage degrades gracefully.
- Tile/radar terms/attribution surfaced.

## Tests/gates
- Radar freshness tests.
- Alert mapping tests.
- Degradation tests.

## Definition of Done
- Radar overlay and alerts available without overwhelming default UX.

## Freeze gate
- Weather provider contracts stable before scope/routing expansion.

## Artifacts
- Weather module/provider adapters, layer composer update, tests.

## Handoff
- Phase 05 can formalize scope engine across transit/weather.
