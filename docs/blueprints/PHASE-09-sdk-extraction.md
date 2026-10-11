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

Not a blueprint phase; recorded here because it changed module behavior after the
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

### UI/UX pass

The map is the product, so it fills the viewport edge to edge with no control
panel permanently laid over it. What remains on screen is a right-hand column of
three floating actions (plan, data, recenter) and one quiet scope chip. Everything
else lives in a bottom sheet that is hidden until asked for. Verified on-device:
scope switching reframes the camera and swaps the render bucket live
(System/CLUSTER/6000 -> Nearby/INDIVIDUAL/500).

### Open items carried forward

All four previously-open items now have decisions recorded. What remains is
verification, not specification:

- **Live OTP verification** (ADR-007). The adapter, mapper and hybrid policy are
  implemented and tested, but only against a hand-authored OTP 2.x payload. A live
  call against a running instance has not been made, so field drift between OTP
  versions is unproven either way. Needs a deployment; `docs/OTP_DEPLOYMENT.md`
  has the recipe.
- **Offline basemap package.** `OfflineBasemapStore` manages packages and
  `BasemapPolicy` reports a missing one, but no package is bundled and none has
  been installed on a device. The install path is therefore untested end to end.
- **RainViewer** (ADR-003). Resolved to "RainViewer primary with mandatory
  attribution, IEM/NWS fallback", on the grounds that it is free and key-free. Its
  usage limits are still not contractually guaranteed; that risk is documented
  rather than eliminated.
- **`core/atlas-transit`** (ADR-014). Verified to have zero dependents and zero
  imports, and formally retired. The files remain on disk because they are
  protected user work; deleting them is the user's call.

Also outstanding, and not open items but unfinished features: there is no release
signing config, no ProGuard rules and no Play store listing, so the app builds and
runs but is not publishable as-is.
