# Phase 03 — Live Map

## Objective
Render basemap, static network, and live vehicles with scope-aware performance.

## Prerequisites
- Phase 02 realtime snapshots available.

## In scope
- MapLibre wrapper.
- Basemap provider.
- Route shapes/stops rendering.
- Vehicle layer with LOD/clustering/throttling/interpolation basics.
- Viewport culling.

## Out of scope
- Weather radar.
- Routing.
- Full offline tiles.

## Components affected
- `app/`, map/render modules.

## Requirements
- Provider contracts remain at edge.
- Camera/data/render scopes separated.
- System-wide view remains target; no fixed radius cap.

## Tests/gates
- Rendering-state unit tests.
- Device smoke test: map loads, vehicles appear/update, stale style works.

## Definition of Done
- Live vehicles and static network visible on map with stable updates.

## Freeze gate
- Map layer composition and scope behavior accepted before weather.

## Artifacts
- Map view, snapshot-to-layer pipeline, render-state tests.

## Handoff
- Phase 04 can add weather layers.
