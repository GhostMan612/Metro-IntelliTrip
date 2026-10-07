# IntelliTrip — Authoritative Architecture

## Vision
IntelliTrip is one Android application backed by the Atlas sovereign geospatial engine. The engine combines live transit, trip planning, realtime vehicle intelligence, weather/radar, and scalable map scope, with offline capability as an eventual target.

## Layers
```text
App Shell (Kotlin + Jetpack Compose)
  - map screen, scope controls, weather layers, trip planner, settings, dev/advanced mode

Atlas Engine (portable, provider-neutral)
  - domain/: pure Kotlin models and rules
  - providers/: transit, routing, weather, basemap, traffic contracts
  - scope/: camera/data/render scope coordination
  - render/: viewport culling, LOD, clustering, layer composition
  - platform/: persistence, cache, permissions, scheduling

Providers
  - Metro Transit, NOAA/NWS/MRMS/IEM, RainViewer, CARTO, MapLibre, future agencies/traffic
```

## Core principles
- Domain modules must not import Android framework types.
- Providers implement generic contracts; provider-specific payloads stay at the edge.
- Camera scope, data scope, and render scope are independent.
- System-wide rendering is a target; scalability is solved with filtering, culling, LOD, and clustering, not artificial radius caps.
- Default UX is simple; advanced capabilities remain available in the same app.

## Routing as core
Routing is a core capability. Initial implementation may use a provider such as OpenTripPlanner, but the domain contract must support multimodal journeys: walking, transit legs, transfers, realtime updates, and later transfer-confidence intelligence.

## Provider contracts
See `PROVIDER_CONTRACTS.md`.

## Data and offline
See `OFFLINE_MODEL.md`.

## Security/privacy
See `SECURITY.md`.

## Rendering
See `RENDERING_MODEL.md`.

## Scope
See `SCOPE_MODEL.md`.

## Testing
See `TEST_STRATEGY.md`.

## Phase gates
See `blueprints/`. No phase begins until the prior freeze gate passes.
