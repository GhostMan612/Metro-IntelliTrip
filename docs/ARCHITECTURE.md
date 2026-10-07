# IntelliTrip — Master Architecture Blueprint

> Live transit + live weather + geospatial rendering, as one sovereign modular engine.
> This document is a living spec. Sections marked **[TBD — improvement room]** are open decisions.

## 1. Product Vision

One app, one engine: transit live data (Metro Transit), live weather (NOAA/MRMS/RainViewer),
and a geospatial renderer, composable so the same engine can power other apps (e.g. Recovery for All).

Core UX idea: a **scope dial** from "just my trip" → system-wide view. Default keeps it simple;
advanced/dev controls live behind a setting.

## 2. High-Level Architecture

```
┌─────────────────────────────────────────────────────────┐
│  App Shell (Android, Jetpack Compose)                   │
│  Map screen, scope dial, layer toggles, settings        │
├─────────────────────────────────────────────────────────┤
│  Atlas Engine (modular core, portable SDK)              │
│  ├─ data/        transit GTFS-RT + weather clients      │
│  ├─ render/      map style, layers, view-scope logic    │
│  ├─ domain/      trip state, vehicles, alerts models    │
│  └─ platform/    cache, offline tiles, permissions      │
└─────────────────────────────────────────────────────────┘
```

## 3. Data Sources (audited links)

### Transit — Metro Transit (Twin Cities)
- API base / Swagger: https://svc.metrotransit.org/swagger/index.html
- GTFS static + extensions: https://www.metrotransit.org/resources/gtfs-extensions/
- GTFS archive: https://svc.metrotransit.org/mtgtfs/archive/
- Vehicle/route info endpoints: https://svc.metrotransit.org/
- Realtime endpoint: https://rts.metrotransit.org/

### Routing (optional, phase 2+)
- OpenTripPlanner: https://www.opentripplanner.org/ · https://github.com/opentripplanner/OpenTripPlanner

### Weather / Radar
- NOAA MRMS radar mosaics: https://www.nssl.noaa.gov/projects/mrms/MRMS_data.php
- MRMS QPE ImageServer: https://mapservices.weather.noaa.gov/raster/rest/services/obs/mrms_qpe/ImageServer
- NOAA radar base reflectivity: https://mapservices.weather.noaa.gov/eventdriven/rest/services/radar/radar_base_reflectivity/MapServer
- RainViewer API (simplest radar tiles): https://www.rainviewer.com/api/weather-maps-api.html
- Iowa State IEM NEXRAD composites: https://mesonet.agron.iastate.edu/docs/nexrad_composites/
- Radar GIS services: https://mesonet.agron.iastate.edu/docs/radmapserver/?
- NWS Web API: https://www.weather.gov/documentation/services-web-api

### Basemap / Tiles
- CARTO basemaps: https://www.carto.com/ (API keys already owned — start here)
- MapLibre GL for Android: https://github.com/maplibre/maplibre-native

### Traffic (phase 3, investigated via MnDOT later)
- MnDOT freeway sensors — TBD, find official API/developer docs.

## 4. Tech Stack (proposed defaults)

| Layer | Choice | Rationale |
|---|---|---|
| Language | Kotlin | Android standard |
| UI | Jetpack Compose | Modern, single-activity |
| Map | MapLibre GL Native Android | Free, vector tiles, CARTO-compatible |
| DI | Hilt | Standard |
| Network | Retrofit + OkHttp, coroutines | Standard |
| GTFS-RT | gtfs-realtime-bindings (protobuf) | Official Google bindings |
| Local store | Room | Cache GTFS static, favorites |
| Weather tiles | RainViewer (MVP), MRMS/IEM later | Load-bearing simplicity first |

**[TBD — improvement room]**: Flutter vs native; MapLibre vs Mapbox; CARTO vs protomaps offline.

## 5. Module Layout (target)

```
app/                     # Compose shell
core/atlas-engine/       # portable SDK: data + render + domain
core/atlas-transit/      # Metro Transit client (GTFS-RT, nextrip)
core/atlas-weather/      # RainViewer/MRMS client
core/atlas-map/          # MapLibre wrapper, style, layers, scope dial
docs/                    # this blueprint + ADRs
```

## 6. Phased Roadmap

1. **Phase 0 — Foundations**: scaffold, MapLibre+CARTO basemap renders.
2. **Phase 1 — Transit live layer**: GTFS-RT vehicles on map, route shapes from static GTFS.
3. **Phase 2 — Weather overlay**: RainViewer radar tiles, alerts from NWS API.
4. **Phase 3 — Scope dial + trip mode**: "just my trip" follow mode vs system view.
5. **Phase 4 — Routing with OTP** (optional), offline tiles, MnDOT traffic.

## 7. Open Decisions (improvement room)

- [ ] Offline-first tile strategy (protomaps/PMTiles vs CARTO online)
- [ ] GTFS-RT polling cadence vs websockets/ALERTS handling
- [ ] Domain model for "scope" (trip, route, quadrant, system)
- [ ] SDK extraction path for reuse in Recovery for All
- [ ] MnDOT traffic API investigation
- [ ] Testing strategy for realtime/map code
