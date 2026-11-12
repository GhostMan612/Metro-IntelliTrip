# Kotlin/Wasm browser host

This module is the browser proof of the same engine the Android app uses. It is
not a second implementation: the static GTFS parser, realtime decoder, planner,
scope engine and render pipeline are the portable modules.

## Run the live shell

```powershell
# Build the bundle, then copy the HTML shell and the vendored MapLibre dist
# next to it. The copy task depends on the webpack task.
.\gradlew.bat :core:atlas-web-spike:vendorMapLibreDev

# Serve the output directory with any static server. Python is on every
# developer machine; the path has no runtime dependency on Python.
python -m http.server 8765 --directory core/atlas-web-spike/build/kotlin-webpack/wasmJs/developmentExecutable
```

Open <http://127.0.0.1:8765/>. The first load downloads the Metro Transit static
GTFS feed (about 30 MB), validates it, writes it to IndexedDB, and only then draws
the map. Subsequent loads rehydrate the cached feed and revalidate it with
ETag/Last-Modified.

Use <http://127.0.0.1:8765/?demo=1> for a synthetic demo network with no network
calls. Demo data is never used by the production path and is clearly labelled in
the shell.

## Production bundle

```powershell
.\gradlew.bat :core:atlas-web-spike:vendorMapLibreProd
python -m http.server 8765 --directory core/atlas-web-spike/build/kotlin-webpack/wasmJs/productionExecutable
```

## What the shell exercises

- Real static-feed bootstrap with explicit fatal states (no demo fallback).
- IndexedDB durable cache and background revalidation.
- Live vehicle positions and service alerts, polled from Metro Transit.
- Stop selection and on-device planning through the same `AtlasEngine`.
- MapLibre style-ready source replay, including a style reload after a basemap
  change.
- `?demo=1` synthetic vehicles, intentionally separate from production.

## Limits

- The browser host is a proof and integration surface, not the shipped product.
  The Android app is the primary host.
- There is no service worker; a reload still uses IndexedDB but the app shell
  itself must be served from this directory.
- WebGL is required for MapLibre. Headless CI must enable SwiftShader.
- Radar is not rendered in this host. RainViewer licensing is unresolved, and
  its tiles do not cover the demo's zoom level; the renderer keeps the overlay
  API for a future licensed provider rather than painting placeholder tiles.
