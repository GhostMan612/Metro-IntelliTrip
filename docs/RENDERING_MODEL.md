# Rendering Model

System-wide rendering is an architectural target. Scale is handled by the pipeline, not by limiting transit data to a fixed radius.

## Pipeline
```text
live snapshot
  -> spatial filtering
  -> viewport culling
  -> LOD/clustering
  -> bulk renderer / source update
```

## Renderer boundary
`MapRenderer`/AtlasMapEngine (MapLibre adapter) is separate from `BasemapProvider`. MapLibre is the rendering runtime; CARTO/offline PMTiles/future providers supply style and tile resources through `BasemapProvider`.

## Techniques
- Spatial index/filter for vehicles, stops, alerts, traffic.
- Viewport culling before MapLibre layer updates.
- Level-of-detail by zoom bucket: cluster, simplified symbols, individual vehicles.
- Clustering at city/system zooms; declutter individual vehicles at local zooms.
- Update throttling so realtime refreshes do not redraw excessively.
- Interpolation between vehicle updates for smooth motion.
- Weather/transit layer composition order: basemap -> weather radar -> transit shapes/stops -> vehicles -> alerts.

## System-scale guidance (implementation-safe)
- Use MapLibre-native source/layer data updates (GeoJSON sources, symbol/circle layers, clustering) or other GPU-efficient bulk rendering.
- Do NOT create one Android View per vehicle, one Compose object per marker, or heavyweight per-marker UI at system scale.
- Vehicle updates should mutate source feature collections, not rebuild the view hierarchy.

## Rendering states
Renderer consumes immutable scope/snapshot state from the engine. It must not call providers directly.

## Implementation status
- `core/atlas-map`: `TransitRenderPipeline` performs viewport culling, zoom-bucket LOD (CLUSTER / SIMPLIFIED / INDIVIDUAL), grid clustering, snapshot interpolation with dateline handling, staleness marking, and a rendered-vehicle budget with truncation reporting.
- `core/atlas-map`: `RenderUpdateThrottle` rate-limits source updates; `GeoJsonWriter` emits one FeatureCollection per layer.
- `core/atlas-map-android`: `MapLibreMapRenderer` pushes those FeatureCollections into MapLibre sources. No Android view or Compose object is created per vehicle.

## Constraints
- GTFS-Realtime protobuf bindings must remain Android-compatible; bindings published with newer JVM bytecode cannot be loaded by Android runtimes.
- Realtime acquisition must run off the main thread.

## Failure behavior
If realtime data is stale, show stale markers with reduced confidence styling rather than silently jumping to old locations.
