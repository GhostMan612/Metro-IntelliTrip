# WO-017 — Offline basemap packages: secure import and MapLibre rendering

**Priority:** P2 platform completion; high severity when advertised as an installed/offline map. **Status:** Partial source implementation; permitted archive provenance, offline rendering/restart, coverage behavior, and device proof remain open.
**Baseline:** `ccf662c7de6e9b0b34e20994366f5ecfec5d5d40` / `master`. **Existing workchain:** W-02.  
**Source:** `core/atlas-offline/src/commonMain/kotlin/com/intellitrip/offline/OfflineBasemapStore.kt`; `OfflineFileStore.kt`; `BasemapPolicy.kt`; `core/atlas-map/src/commonMain/kotlin/com/intellitrip/map/BasemapProvider.kt`; `core/atlas-map-android/src/main/java/com/intellitrip/map/android/MapLibreMapRenderer.kt`; `app/.../MapCanvas.kt`.

## SEED
- Store accepts staged file path, package ID, style JSON, size and metadata; it can list packages and report `hasPackage()`. This is a registry, not evidence that tile bytes are valid or that MapLibre has a tile source.
- Host instantiates the basemap store, but there is no complete rider-driven SAF picker/importer, local tile protocol handler, MapLibre style binding and no-network restart acceptance.
- Current Android source accepts PMTiles v3 or validated MBTiles plus a MapLibre style JSON. PMTiles is bound to its installed file URI; MBTiles is served through an app-owned loopback TileJSON/tile server. Formats are **not interchangeable**.
- A corrupt or attacker-controlled archive/style could create path traversal, unsafe external URL requests, excessive disk use or malicious/offline dependency surprises.

## SPEC
**Supported input:** PMTiles v3 or Android-host-validated MBTiles archive plus a MapLibre style JSON. Validate PMTiles headers or MBTiles SQLite schema/metadata/tile presence and style structure/source requirements; reject unknown formats and invalid inputs with visible reasons. Do not infer file validity from an extension.

**SAF and file custody:** picker opens user-chosen content URI; persist only permission needed for copy; stream to an app-private transaction-stage file, enforce byte-count limit and timeout, hash as copied, reject symlinks/path escape/untrusted filenames. No untrusted ID flows into filesystem paths. Clean partial files on cancel/failure; active package remains intact.

**Style security:** accept structurally validated MapLibre style JSON tied to the selected archive marker, rewrite archive URLs to the installed PMTiles file or loopback MBTiles TileJSON endpoint, and reject remote HTTP(S) `url`, `tiles`, `sprite`, and `glyphs` resources in MBTiles styles. Full schema validation, telemetry prohibition, and renderer-level no-network behavior remain unverified. Attribution/license stored and displayed.

**MapLibre Android binding:** current source binds PMTiles sources to the installed local archive URI and passes the imported style to MapLibre Native. Verify that every supported style asset (including glyphs and sprites) stays offline; an archive source alone does not establish that the style has no network fallback. Layer stack reattaches after style switch, but runtime and restart behavior remain unverified.

**Atomic package lifecycle:** install or update one immutable package at a time; manifest activation only after archive and style validation, rollback when reader fails. On deletion, unbind first and remove underlying package/style and matching manifest entry. Detect missing/corrupt package on startup, show accurate "Offline map unavailable" without losing transit schedule data.

**Geographic coverage:** current viewport outside package bounds shows an explicit no-coverage state rather than blank unlabeled map. Display package area/size and storage usage, allow remove, enforce app-private quota.

**License:** don't bundle provider data unless distribution rights are confirmed. A user-imported archive requires documented third-party attribution and legitimate acquisition; reject unauthorized product claim of full region offline by default.

## PLAN
1. Keep PMTiles v3 and Android-host-validated MBTiles as implemented formats; document supported style resources and verify styles cannot silently depend on external assets or tile sources.
2. Maintain the pure archive/style validator and secure import staging/manifest transaction; extend corruption and security coverage as runtime evidence reveals gaps.
3. Audit the existing Android SAF picker and import progress/cancel/error paths; close remaining gaps while keeping the map usable during import.
4. Verify MapLibre Native's local PMTiles source behavior, including style assets, bounded resource use, and coverage fallback; wire basemap policy.
5. Verify a small licensed test archive and one real permitted Twin Cities package in airplane mode, including restart and out-of-coverage fallback.
6. After implementation complete, execute integrated CI and device gate, attach screenshot/memory/tile receipts.

## Files expected
- `core/atlas-offline/src/commonMain/kotlin/com/intellitrip/offline/OfflineBasemapStore.kt` and PMTiles/style validators
- `core/atlas-map-android/src/main/java/com/intellitrip/map/android/MapLibreMapRenderer.kt` for the local PMTiles source handoff
- `app/src/main/java/com/intellitrip/IntelliTripViewModel.kt` and `ui/MapChrome.kt` for active-package state and invalid-package reporting
- `core/atlas-offline/src/jvmTest/kotlin/com/intellitrip/offline/OfflineBasemapStoreSecurityTest.kt` for store and startup-validation coverage
- Remaining work: full style-schema and renderer network validation, viewport coverage behavior, licensed fixture attribution, and device/runtime verification

## VERIFICATION
- Import a legitimately sourced PMTiles archive, terminate network, pan/zoom/restart: matching tiles still render; schedules and journey overlays unaffected; reboot, resume and rapid map style switches work.
- Wrong extension, truncated/corrupt PMTiles header, invalid style, unsupported layers, huge archive/quota failure, process kill mid-import, bad URI, invalid style URL and path traversal all fail closed with old package preserved.
- Removing package reverts map to "basemap unavailable" offline (or online if connected) and clears disk files; no stale falsely "installed" status.
- Zoom in/out, rotation/tilt, stress memory on BLU, Android file permissions, TalkBack and real attribution verified. Attach screenshot and logcat proof.
- Release config cannot leak mock tile packages, network fallback or forbidden archive assets without consent.

## LOCAL CHECKPOINT
Android SAF import, atomic PMTiles/MBTiles package storage, local style binding, and MapLibre handoff are present. Android validates MBTiles SQLite tables and required metadata before installation and serves its tiles over loopback, translating XYZ requests to MBTiles TMS rows. On startup or selection, the host revalidates package content before exposing it to the renderer; invalid or missing content is marked unavailable and the UI explains that the online basemap is shown when available. This is not a full archive-integrity check and does not prove offline rendering, style-asset isolation, restart behavior, or out-of-coverage handling. The Android host reports engine offline capability only when its static network has a usable snapshot; that flag does not claim a basemap is installed or renderable. The Wasm `OfflineFileStore` remains memory-only. Keep this workchain open until licensed archive and end-to-end/device evidence exist.

## OUT OF SCOPE
Live OTP, geocoder, Wasm IndexedDB tile persistence, and any basemap whose redistribution terms are unknown. Status remains BLOCKED on permitted sample/source and offline runtime evidence.
