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
```

## Modules
- `app/` Android Compose shell
- `core/atlas-domain/` pure Kotlin identity/scope domain
- `core/atlas-contracts/` pure Kotlin provider/scope contracts
- `core/atlas-gtfs-static/` pure Kotlin static GTFS adapter (ZIP, CSV, validation, snapshots)
- `core/atlas-gtfs-realtime/` pure Kotlin GTFS-Realtime adapter (vehicle positions, trip updates, alerts)
- `core/atlas-map/` pure Kotlin render pipeline (cull, LOD, clustering, interpolation, GeoJSON)
- `core/atlas-map-android/` MapLibre renderer adapter (bulk source/layer updates)
- `core/atlas-transit/` protected prototype adapter; not authoritative until migrated

## Docs
- Architecture: docs/ARCHITECTURE.md
- Brief: docs/PRODUCT_BRIEF.md
- Constraints: docs/CONSTRAINTS.md
- Domain: docs/DOMAIN_MODEL.md
- ADRs: docs/ADR/
