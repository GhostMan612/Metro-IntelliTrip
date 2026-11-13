# WO-015 — Web MapLibre style lifecycle and visual correctness

**Priority:** P1 web rendering. **State:** Partial implementation; browser verification remains open.
**Baseline:** `ccf662c7de6e9b0b34e20994366f5ecfec5d5d40` / `master`. **Corresponds to W-09 (renderer half)**.  
**Source:** `core/atlas-map-web/src/wasmJsMain/kotlin/com/intellitrip/map/web/WebMapRenderer.kt`; `WebRenderBridge.kt`; `core/atlas-web-spike/src/wasmJsMain/kotlin/com/intellitrip/webspike/Platform.kt`; `core/atlas-web-spike/build.gradle.kts`.

## SEED
- `setStyle()` creates/restyles MapLibre, and `setSources()`/`setRadarOverlay()` immediately call JS map `addSource`/`addLayer` without explicit map style-load readiness. Style is asynchronous; a source add before style load can fail.
- Renderer queues source and radar state until the style is ready and replays it after a restyle; frame URL changes replace the raster source instead of retaining stale tiles.
- Source calls are treated as complete desired-state snapshots: omitted layers are explicitly cleared, unchanged payloads are not resent, and radar state is re-applied after a style reset.
- Vendored MapLibre JS/CSS are copied by explicit dev/prod Gradle tasks. All runnable and static packaging paths still need verification.
- Browser bootstrap waits for the global MapLibre library; style lifecycle is managed separately by the renderer. Visible error/retry behavior and real Chromium verification remain open.

## SPEC — deterministic renderer

1. Define a renderer lifecycle `Uninitialized -> Mounted -> StyleLoading -> Ready -> Destroyed` with transitions on MapLibre callbacks. Subscribe to `load`/`styledata` (or equivalent source-ready signal) and hold desired data while loading.
2. `setSources` sets the **desired** payloads for VEHICLES, CLUSTERS, STOPS and SHAPES, including explicit empty feature collections. On Ready, reconcile whole desired state; when a layer is removed from source list, clear its content rather than retaining ghost markers.
3. `setRadarOverlay` replaces the current desired radar overlay; install at Ready, replace source/layer safely on frame URL change, remove both on null. On style reset/reload, reinstall current radar and data layers exactly once.
4. Debounce repeated sources across 200ms glide loop without leaking JS listeners or redrawing every source needlessly. Never override manual pan after initial camera when snapshots update; explicit user recenter remains possible.
5. Each source/layer ID is stable and namespace-prefixed, sourced from a typed known set. Validate map style before invoking APIs; handle load failures with a visible error/retry and no fake marker display.
6. Destroy removes event listeners, pending operations, animation loops, map instance and DOM ownership. A remount after destroy must restore initial camera and desired layers without stale callbacks.
7. Vendored JS/CSS are pinned and locally served; wire their copies into every task that produces a runnable dev/production web bundle. Verify bytes/version and avoid CDN fallback that falsely presents offline support. CARTO tile network remains an independent, clearly labeled online dependency.
8. Weather attribution is visible. An unrenderable NWS alert polygon is never represented by a fictitious point.

## PLAN
**A. Pure bridge tests.** Keep `snapshotToSources()` returning all four layers with empty FeatureCollection for no data. Test desired-state completion and valid GeoJSON.
**B. Stateful adapter.** Add desired sources/radar/style, callbacks and lifecycle guard to `WebMapRenderer`; do not let JavaScript exceptions escape the UI thread unreported.  
**C. Integration.** Update `Platform.kt` host to await both vendored library and MapLibre style-ready; expose loading, no-tile, failure and retry UI.  
**D. Build.** Depend dev/prod bundles on vendor-copy staging; validate bundled asset URLs for local hosting and static deployment.  
**E. Browser tests.** Run Chromium with real MapLibre styles and synthetic render snapshots (no fabricated transit backend); capture DOM/map screenshots and console.

## Expected files
- `core/atlas-map-web/src/commonMain/kotlin/com/intellitrip/map/web/WebRenderBridge.kt`
- `core/atlas-map-web/src/wasmJsMain/kotlin/com/intellitrip/map/web/WebMapRenderer.kt`
- `core/atlas-map-web/src/commonTest/kotlin/com/intellitrip/map/web/WebRenderBridgeTest.kt` + new browser visual tests
- `core/atlas-web-spike/src/wasmJsMain/kotlin/com/intellitrip/webspike/Platform.kt`
- `core/atlas-web-spike/build.gradle.kts` and web packaging task definitions

## VERIFICATION gates
- Apply data and radar **before** style finishes: after Ready, both visible; no addSource-before-style errors in console.
- Restyle light -> dark -> light: every transit and radar layer preserved exactly once, no duplicates or blank sources.
- Vehicle/stops 10 -> 0 -> 7: no ghost features; radar frame A -> B -> Off -> On: correct last tile only.
- Rapid style changes, teardown while loading, remount after destroy: no leaked callbacks, JS exceptions, or camera resets against manual pan.
- Dev/prod artifacts serve vendored JS/CSS offline (the basemap may require network), with exact version receipt; no missing-asset fallback claimed successful.
- JVM bridge tests, `compileKotlinWasmJs`, webpack packaging, Chromium console/video/screenshot and keyboard accessibility. Android tests should remain green.
- Evidence must show actual browser run; compilation alone is insufficient.

## LOCAL IMPLEMENTATION CHECKPOINT
The style-ready source/radar queue, restyle replay, omitted-layer clearing, unchanged-source suppression, and radar-frame replacement are implemented. The browser receipt gates above, including errors, remount behavior, and packaging coverage, remain unverified.

## OUT OF SCOPE / closing rule
Not a real GTFS-Web backend, not Wasm protobuf, not durable IndexedDB (WO-016); not Play release. This PR contains only the specification until the renderer is implemented and separately verified. Do not call "web parity" completed on this spec.
