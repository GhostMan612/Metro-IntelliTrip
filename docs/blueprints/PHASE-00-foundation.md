# Phase 00 — Foundation

## Objective
Establish project structure, docs freeze, build hygiene, and app shell skeleton.

## Prerequisites
- Authoritative docs present.
- ADRs accepted.

## In scope
- Gradle/Kotlin/Android module boundaries.
- Core docs and blueprint chain.
- Basic app launch placeholder.
- CI/build/test/lint commands documented.

## Out of scope
- Transit/weather clients.
- Routing.
- Offline tiles.
- Advanced map rendering.

## Components affected
- `app/`, `core/`, `docs/`, Gradle config.

## Requirements
- `core/` modules do not import Android framework types; pure Kotlin domain/contracts only.
- Namespaced identities are established in the domain model: `AgencyId`, `FeedId`, `RouteKey`, `TripKey`, `StopKey`, `VehicleKey`. Raw GTFS IDs are not treated as globally unique. (Later refined by ADR-011: static GTFS entity keys are feed-scoped, and `ServiceKey`/`ShapeKey` were added in Phase 01.)
- Scope primitives (`ScopeState`, `Focus`, `CameraScope`, `DataScope`, `RenderScope`) and the `Focus -> ScopePolicy/ScopeResolver -> CameraScope/DataScope/RenderScope` resolver contract are established here; Phase 03 consumes them, Phase 05 expands policy/UI/advanced behavior.
- Prototype code violating the pure-Kotlin/portability rule is audited/quarantined/refactored before being authoritative.
- No secrets committed.
- `AGENTS.md` documents build/test/lint.

## Tests/gates
- `gradlew.bat assembleDebug` passes.
- `gradlew.bat test` and `lint` baseline pass or documented exceptions.

## Definition of Done
- Build scaffold works.
- Docs chain is authoritative.
- Feature code is not started.

## Freeze gate
- No Phase 01 until docs review closes contradictions.

## Artifacts
- Updated `AGENTS.md`, docs set, passing scaffold build.

## Handoff
- Phase 01 may parse static GTFS and models using namespaced identities and established scope primitives.
