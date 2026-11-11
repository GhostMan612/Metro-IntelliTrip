# IntelliTrip

Live transit + live weather on one sovereign geospatial engine.

Android app (Kotlin + Compose + MapLibre) backed by a portable pure-Kotlin
engine in `core/atlas-*` (no Android imports in portable modules, enforced by
test). See `docs/ARCHITECTURE.md` for the authoritative design and `AGENTS.md`
for build commands.

## Quick start

Requires Android SDK + `local.properties` (see `AGENTS.md`). Nothing else: no
API keys or accounts. `HUMAN_ACTIONS.md` covers optional OTP self-hosting,
offline basemap packages, and Play publishing.

```powershell
.\gradlew.bat assembleDebug
.\gradlew.bat test
.\gradlew.bat lint
.\gradlew.bat :sample:host:run   # JVM portability proof, no Android needed
```

## Layout

- `app/` — Android Compose shell. `AtlasHost.kt` is the composition root.
- `core/atlas-domain/`, `atlas-contracts/` — feed-scoped domain + provider contracts.
- `core/atlas-gtfs-static/`, `atlas-gtfs-realtime/` — GTFS static (streaming CSV, disk stop-time index) and GTFS-RT adapters.
- `core/atlas-routing/`, `atlas-scope/`, `atlas-map/`, `atlas-weather/`, `atlas-offline/` — planner, scope, render pipeline, weather, offline staleness.
- `core/atlas-engine/` — `AtlasEngine` SDK facade. `sample/host/` proves JVM hosting.
- `core/atlas-transit/` — retired and deleted (ADR-014); Git history preserves the prototype.
- `core/atlas-map-android/` — MapLibre renderer adapter (bulk GeoJSON only).

Publish the SDK to `build/atlas-maven` (`com.intellitrip.atlas:0.2.0`) with
`.\gradlew.bat publishAtlasToLocalRepo`. See `docs/SDK_INTEGRATION.md`.

## Status

Phases 00-09 are closed. Recent KMP/browser work has unresolved release gates:
real-feed CI, browser realtime decoding/durable restore, complete rider controls,
and external OTP, RainViewer and Play verification. `atlas-transit` is deleted.
See `docs/audits/2026-11-10-closure-audit.md` and `docs/workchains/README.md`.
The active development branch is `master`; GitHub's older default `main` still
contains only the original README. Do not target `main` for application PRs.

## License

Apache 2.0. See `LICENSE`. Verify the choice with yourself before publicly
publishing the SDK or submitting to Play; switching licenses later is painful.
