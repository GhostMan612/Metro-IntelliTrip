# WO-017 — Offline basemap packages: secure import and MapLibre rendering

**Priority:** P2 platform completion; high severity when advertised as an installed/offline map. **Status:** SPECCED only.  
**Baseline:** `ccf662c7de6e9b0b34e20994366f5ecfec5d5d40` / `master`. **Existing workchain:** W-02.  
**Source:** `core/atlas-offline/src/commonMain/kotlin/com/intellitrip/offline/OfflineBasemapStore.kt`; `OfflineFileStore.kt`; `BasemapPolicy.kt`; `core/atlas-map/src/commonMain/kotlin/com/intellitrip/map/BasemapProvider.kt`; `core/atlas-map-android/src/main/java/com/intellitrip/map/android/MapLibreMapRenderer.kt`; `app/.../MapCanvas.kt`.

## SEED
- Store accepts staged file path, package ID, style JSON, size and metadata; it can list packages and report `hasPackage()`. This is a registry, not evidence that tile bytes are valid or that MapLibre has a tile source.
- Host instantiates the basemap store, but there is no complete rider-driven SAF picker/importer, local tile protocol handler, MapLibre style binding and no-network restart acceptance.
- Store currently expects `.mbtiles` archive extension; other project docs discuss PMTiles as a future alternative. Formats are **not interchangeable**; do not label one as supported until renderer and validation are implemented.
- A corrupt or attacker-controlled archive/style could create path traversal, unsafe external URL requests, excessive disk use or malicious/offline dependency surprises.

## SPEC
**Supported input:** documented MBTiles format and metadata schema; SQLite validation of required tiles, scheme XYZ/TMS transform, supported PNG/JPEG/vector tile data, reasonable zoom/bounds, tile count and archive size. Reject unknown/unsupported format with visible reason. Never infer file validity from `.mbtiles` suffix alone.

**SAF and file custody:** picker opens user-chosen content URI; persist only permission needed for copy; stream to an app-private transaction-stage file, enforce byte-count limit and timeout, hash as copied, reject symlinks/path escape/untrusted filenames. No untrusted ID flows into filesystem paths. Clean partial files on cancel/failure; active package remains intact.

**Style security:** accept only a validated MapLibre style schema tied to package source/layer definitions, rewrite tile URLs to a strictly local tile source, prohibit unconsented third-party asset/telemetry calls, and provide font/sprite fallbacks that do not silently fetch online. Attribution/license stored and displayed.

**MapLibre Android binding:** implement a dedicated local MBTiles tile provider/protocol (not file:// injection into remote style); decode tiles off main thread, maintain bounded LRU caches, support backpressure/cancel on pans, disable online tile fallbacks in the Offline map mode. Layer stack reattaches after style switch and app restore.

**Atomic package lifecycle:** install or update one immutable package at a time; manifest activation only after archive and style validation, rollback when reader fails. On deletion, unbind first and remove underlying package/style and matching manifest entry. Detect missing/corrupt package on startup, show accurate "Offline map unavailable" without losing transit schedule data.

**Geographic coverage:** current viewport outside package bounds shows an explicit no-coverage state rather than blank unlabeled map. Display package area/size and storage usage, allow remove, enforce app-private quota.

**License:** don't bundle provider data unless distribution rights are confirmed. A user-imported archive requires documented third-party attribution and legitimate acquisition; reject unauthorized product claim of full region offline by default.

## PLAN
1. Select and document MBTiles-only minimum deliverable, tile matrix conversion and supported map styles; separately evaluate PMTiles as a later format, not a combined speculative implementation.
2. Build pure validator and secure import staging/manifest transaction with injected SQLite/byte source seams; security & corruption tests.
3. Add Android SAF picker and progress/cancel/error UI in More/Map layers; keep map usable during import.
4. Implement MapLibre native local-tile protocol style integration with bounded decoding/cache, maintain correct renderer lifecycle; wire basemap policy.
5. Verify a small licensed test archive and one real permitted Twin Cities package in airplane mode, including restart and out-of-coverage fallback.
6. After implementation complete, execute integrated CI and device gate, attach screenshot/memory/tile receipts.

## Files expected
- `core/atlas-offline/src/commonMain/kotlin/com/intellitrip/offline/OfflineBasemapStore.kt` and storage/seams
- New Android MBTiles validator/tile provider in `core/atlas-map-android` (or separate Android adapter module)
- `app/src/main/java/com/intellitrip/AtlasHost.kt`, `ui/MapCanvas.kt`, `ui/MapChrome.kt`, `ui/SecondaryScreens.kt`
- `docs/OFFLINE_MODEL.md`, `docs/SECURITY.md`, `docs/PLAY_TRACK.md` and license attribution; test fixtures and security tests

## VERIFICATION
- Import valid licensed MBTiles, terminate network, pan/zoom/restart: matching tiles still render; schedules and journey overlays unaffected; reboot, resume and rapid map style switches work.
- Wrong extension, truncated SQLite, invalid metadata, unsupported layers, huge archive/quota failure, process kill mid-import, bad URI, invalid style URL and path traversal all fail closed with old package preserved.
- Removing package reverts map to "basemap unavailable" offline (or online if connected) and clears disk files; no stale falsely "installed" status.
- Zoom in/out, rotation/tilt, stress memory on BLU, Android file permissions, TalkBack and real attribution verified. Attach screenshot and logcat proof.
- Release config cannot leak mock tile packages, network fallback or forbidden archive assets without consent.

## OUT OF SCOPE
Live OTP, geocoder, Wasm IndexedDB tile persistence, PMTiles format unless separately decided, and any basemap whose redistribution terms are unknown. Status remains BLOCKED on licensed sample/source until evidence.
