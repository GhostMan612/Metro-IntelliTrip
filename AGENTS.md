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

## Docs
- Architecture: docs/ARCHITECTURE.md
- Brief: docs/PRODUCT_BRIEF.md
- Constraints: docs/CONSTRAINTS.md
- Domain: docs/DOMAIN_MODEL.md
- ADRs: docs/ADR/
