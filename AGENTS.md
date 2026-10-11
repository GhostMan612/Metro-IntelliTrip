# AGENTS.md

## Build
```
gradlew.bat assembleDebug
```
(First run requires Android SDK + `local.properties` pointing to it; install Android Studio or command-line tools.)

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
gradlew.bat :core:atlas-gtfs-static:test --tests "*RealFeedSmokeTest" --rerun-tasks ^
  -Dintellitrip.gtfs.fixture=gtfs-metro-transit.zip
```
Planner latency against the same feed, through the disk-index path the app uses:
```
gradlew.bat :core:atlas-routing:test --tests "*RealFeedPlanTimeTest" --rerun-tasks ^
  -Dintellitrip.gtfs.fixture=gtfs-metro-transit.zip
```
Both skip when the fixture is absent rather than passing silently.

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
- `core/atlas-transit/` protected prototype adapter; not authoritative until migrated

## Optional setup
`HUMAN_ACTIONS.md` covers signups, costs and optional configuration. Nothing there
is required to build or run the app: no API keys, no accounts.

## Phase status
Phases 00-09 are closed; `docs/blueprints/PHASE-09-sdk-extraction.md` records the
post-freeze app completion work and the open items carried forward (live OTP
verification, offline basemap package, RainViewer licensing,
`core/atlas-transit` migration). Nothing in that list is claimed as done.

## Docs
- Architecture: docs/ARCHITECTURE.md
- Brief: docs/PRODUCT_BRIEF.md
- Routing topology + OTP deploy: docs/OTP_DEPLOYMENT.md
- Constraints: docs/CONSTRAINTS.md
- Domain: docs/DOMAIN_MODEL.md
- ADRs: docs/ADR/
