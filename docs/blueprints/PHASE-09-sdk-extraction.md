# Phase 09 — SDK Extraction

## Objective
Extract the reusable Atlas engine boundary for other apps.

## Prerequisites
- All prior phases frozen.

## In scope
- Public API boundary.
- Provider contract packaging.
- Domain portability checks.
- Recovery for All integration spike.

## Out of scope
- New product features.
- Rewriting in another language.

## Components affected
- core modules, docs, packaging.

## Requirements
- Domain modules remain Android-free pure Kotlin; `app/`, `platform/`, and adapters isolated.
- App shell depends only on public engine APIs.
- Provider adapters replaceable.
- Prototype code violating the portability rule is audited/quarantined/refactored before extraction.

## Tests/gates
- Portability tests.
- API compatibility tests.
- Smoke integration in second app or sample host.

## Definition of Done
- Atlas engine can be consumed independently of IntelliTrip UI.

## Freeze gate
- Public API accepted.

## Artifacts
- SDK docs, sample host, packaging config.

## Status
Implemented. `core/atlas-engine` is the public SDK boundary: `AtlasEngine` composes providers, scope resolution, rendering, journey planning and offline capability behind a single entry point, and hosts can adopt capabilities incrementally (every provider is optional). A `PortabilityGuardTest` fails the build if any reusable module imports Android or declares an Android plugin. `sample/host` runs the engine as a plain JVM program (verified output: capabilities, scope resolution at zoom 15.55 / INDIVIDUAL, and a clear "routing unavailable" when no provider is configured).

## Handoff
- Product teams can build on Atlas Engine.

## Phase 10 - App completion (added after Phase 09 closed)

Not a blueprint phase; recorded here because it changed module behaviour after the
Phase 09 freeze.

- App shell split into a composition root (`AtlasHost`), a `ViewModel`, and focused
  Compose panels for scope, data, planner and status.
- Explicit network actions only. No provider is contacted at startup.
- Static GTFS, trip planning, live transit, radar and alerts are reachable from the
  UI and verified on-device against the real Metro Transit feed.
- Planned journeys are drawn on the map as their own bulk GeoJSON layer, following
  each trip's GTFS shape trimmed to the portion ridden.
- Map gestures drive scope; the viewport is adopted as the active `Focus.Radius`
  after a debounce.
- Planner search is bounded (departure window, nearest-stop cap, candidate cap) and
  reads stop times through an LRU over the on-disk index, so planning on a
  system-wide feed is responsive rather than unbounded.
- `OfflineRegistry.loadStaticFeed` discards stop times rather than collecting them,
  so a cold start does not rebuild the whole table in memory.
- CI runs build, test, lint, the JVM sample host, and the emulator instrumented suite.

### Routing topology: resolved

ADR-007 is closed. Routing is hybrid: the in-process `StaticNetworkPlanner`
answers first, and `HybridRoutingProvider` consults OTP only when local returns
nothing. `OpenTripPlannerRoutingProvider` now maps real OTP plan responses into
domain journeys (walk and transit legs, feed-scoped stop keys, epoch-millisecond
times), and `JsonValue` provides a dependency-free JSON reader so the published
SDK module does not impose a serialization library on consumers.

Hosting targets Oracle Cloud Always Free as the only durable free tier; see
`docs/OTP_DEPLOYMENT.md`. No endpoint is committed and none is required — with no
URL configured the app runs local-only.

### Open items carried forward (not phase work)

These remain genuinely open and are not claimed as done:

- **Live OTP verification.** The mapper is tested against a hand-authored OTP 2.x
  payload, not a live capture from a running instance. Field drift between OTP
  versions is possible and untested.
- **Offline vector tile package** (Phase 08) is not bundled. `BasemapPolicy` and the
  hook exist; no package ships yet, so offline is data-only.
- **RainViewer** (ADR-003) is PROVISIONAL / RESEARCH-GATED on terms, limits and
  licensing.
- **`core/atlas-transit`** remains a quarantined prototype, not authoritative, until
  authorized migration.
- **Realtime at startup** is deliberately not wired: live data loads only on
  explicit user action or the auto-refresh toggle.
