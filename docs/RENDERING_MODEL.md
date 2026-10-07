# Rendering Model

System-wide rendering is an architectural target. Scale is handled by the pipeline, not by limiting transit data to a fixed radius.

## Pipeline
```text
All live data
  -> spatial index
  -> data-scope filter
  -> viewport intersection
  -> LOD/clustering
  -> renderer
```

## Techniques
- Spatial index/filter for vehicles, stops, alerts, traffic.
- Viewport culling before MapLibre layer updates.
- Level-of-detail by zoom bucket: cluster, simplified symbols, individual vehicles.
- Clustering at city/system zooms; declutter individual vehicles at local zooms.
- Update throttling so realtime refreshes do not redraw excessively.
- Interpolation between vehicle updates for smooth motion.
- Weather/transit layer composition order: basemap -> weather radar -> transit shapes/stops -> vehicles -> alerts.

## Rendering states
Renderer consumes immutable scope/snapshot state from the engine. It must not call providers directly.

## Failure behavior
If realtime data is stale, show stale markers with reduced confidence styling rather than silently jumping to old locations.
