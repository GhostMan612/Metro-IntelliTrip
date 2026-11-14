# AGENTS.md

## Build
```
gradlew.bat assembleDebug
```
(First run requires Android SDK + `local.properties` pointing to it; install Android Studio or command-line tools.)
Requires a JDK 17 toolchain (`jvmToolchain(17)`): the Foojay resolver in `settings.gradle.kts` auto-downloads one on first sync if the machine has none (e.g. Android Studio's bundled JBR 21 alone is not enough — without it, Studio fails with `NoToolchainAvailableException`).

## Publish the SDK for another app
The portable `core/atlas-*` modules publish to `build/atlas-maven` (group
`com.intellitrip.atlas`, version from `gradle.properties` `atlasVersion`) so a
separate repository can consume them:
```
gradlew.bat publishAtlasToLocalRepo
```
Released versions are staged and verified into the immutable `atlas-maven`
branch by `.github/workflows/atlas-publish.yml`. Do not hand-edit that branch.
`atlas-map-android` (renderer) and the deleted legacy `atlas-transit` module are
not published. Integration steps: `docs/SDK_INTEGRATION.md`; distribution
contract: `docs/ATLAS_MAVEN_DISTRIBUTION.md`.

## Test / lint
```
gradlew.bat test
gradlew.bat lint
gradlew.bat :app:connectedDebugAndroidTest   # requires a connected device/emulator
```

## Real-feed verification (optional)
The static GTFS parser can be verified against the live Metro Transit feed:
```
curl -o gtfs-metro-transit.zip https://svc.metrotransit.org/mtgtfs/gtfs.zip
gradlew.bat -Dintellitrip.gtfs.fixture=%CD%\gtfs-metro-transit.zip :core:atlas-gtfs-static:jvmTest --tests "*RealFeedSmokeTest" --rerun-tasks
```
Planner latency against the same feed, through the disk-index path the app uses:
```
gradlew.bat -Dintellitrip.gtfs.fixture=%CD%\gtfs-metro-transit.zip :core:atlas-routing:jvmTest --tests "*RealFeedPlanTimeTest" --rerun-tasks
```
The tests may skip in optional local runs when no fixture is configured. In CI
(or with an explicit fixture property), missing feed data fails the gate.

## JVM sample host
```
gradlew.bat :sample:host:run
```

## Modules
- `app/` Android Compose shell
- `core/atlas-domain/` pure Kotlin identity/scope domain
- `core/atlas-contracts/` pure Kotlin provider/scope contracts
- `core/atlas-gtfs-static/` pure Kotlin static GTFS adapter (ZIP, CSV, validation, snapshots)
- `core/atlas-gtfs-realtime/` pure Kotlin GTFS-Realtime adapter (vehicle positions, trip updates, alerts)
- `core/atlas-map/` pure Kotlin render pipeline (cull, LOD, clustering, interpolation, GeoJSON)
- `core/atlas-map-android/` MapLibre renderer adapter (bulk source/layer updates)
- `core/atlas-scope/` pure Kotlin scope engine (focus -> camera/data/render resolution)
- `core/atlas-routing/` pure Kotlin routing (journey planner + OTP adapter spike)
- `core/atlas-offline/` pure Kotlin offline capability (snapshots, staleness, basemap policy)
- `core/atlas-engine/` public SDK facade + composition root (`AtlasEngine`)
- `sample/host/` plain JVM host proving the engine runs without Android
- `core/atlas-weather/` pure Kotlin weather adapters (radar, NWS alerts, layer composer)
- `core/atlas-transit/` deleted (ADR-014 executed; history in docs/ADR)

## Optional setup
`HUMAN_ACTIONS.md` covers signups, costs and optional configuration. Nothing there
is required to build or run the app: no API keys, no accounts.

## Phase status
Phases 00-09 are closed; `docs/blueprints/PHASE-09-sdk-extraction.md` records the
post-freeze app completion work and the open items carried forward (live OTP
verification, offline basemap package, RainViewer licensing,
`core/atlas-transit` migration, now superseded by ADR-014). See
`docs/audits/2026-11-10-closure-audit.md` for the current release blockers and
`docs/workchains/README.md` for deferred execution artifacts.

## Docs
- Architecture: docs/ARCHITECTURE.md
- Brief: docs/PRODUCT_BRIEF.md
- Routing topology + OTP deploy: docs/OTP_DEPLOYMENT.md
- Constraints: docs/CONSTRAINTS.md
- Domain: docs/DOMAIN_MODEL.md
- Visual polish (3 phases, tests at gate only): docs/blueprints/VISUAL-00-overview.md,
  VISUAL-01-foundation.md, VISUAL-02-rider-tasks.md, VISUAL-03-motion-ship.md
- ADRs: docs/ADR/
