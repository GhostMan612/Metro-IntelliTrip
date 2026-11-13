# WO-016 — Wasm transit realtime decoding, durable snapshots and real-feed browser bootstrap

**Priority:** P1 web/platform parity. **Status:** Partial implementation on the local branch; browser end-to-end and offline-file durability remain unverified/incomplete.
**Baseline:** `ccf662c7de6e9b0b34e20994366f5ecfec5d5d40`, `master`. **Existing workchains:** W-03 decoder, W-04 durable snapshot, W-09 production data half.

## SEED
- The original audit found an unavailable Wasm decoder; the local branch now routes Wasm through `PortableFeedDecoder` and has JVM/Wasm parity coverage.
- `PlatformFileStore.wasm.kt` and `OfflineFileStore.wasmJs.kt` still use in-memory maps for ordinary file operations. Durable IndexedDB put/get and static-feed rehydration exist separately; this does not make offline basemap files durable.
- Startup rehydrates static-feed payload state through the durable store, but complete offline-map file restoration and all quota/corruption/recovery cases are not established.
- The Android host now derives the engine's offline capability from the static feed's usable-snapshot state; this does not establish durable browser basemap storage or offline map rendering.
- `PlatformDurable.wasm.kt` reports successful put on request success, not explicit transaction `oncomplete`, and pending coroutine maps need cancellation cleanup. Browser HTTP `PlatformFetch.wasm.kt` hand-assembles JSON header strings without escaping and does not document forbidden headers or CORS failures.
- `WebDemo`/`Platform.kt` create a fixed 3-stop synthetic network and moving demonstration vehicle. Demo is not a production Metro Transit application.

## SPEC — full browser data lifecycle

### GTFS-RT protobuf (W-03)
- Implement an actual portable GTFS-RT protobuf decoder for VEHICLE_POSITION, TRIP_UPDATE and ALERT feed messages, with wire parity versus the JVM decoder. Prefer a generated Kotlin/Wasm-supported decoder or rigorously bounded small wire parser; no Java protobuf class in common.
- Bound message length, nesting depth, entity counts and repeated fields. Skip safely unknown fields; reject malformed lengths and overflows without hanging.
- Preserve actual trip/route/vehicle/stop identity and timestamps. Vehicle ID can be absent; FeedEntity id must not substitute as a stable vehicle identifier without an explicit policy. No fabricated live reports on decode failure.
- Retain provider failure taxonomy and staleness, calendar-zone alignment and feed-scoped keys.

### Browser transport (W-03)
- Use suspending browser fetch with valid HTTP status and CORS behavior. Never try to set forbidden `User-Agent` request header. Build headers with a safe JS object or correct JSON escaping; validate auth headers only when configured.
- Add timeout/abort semantics with cancellation removing pending continuation map entries, bounded response sizes, retry/backoff for 429s, and distinct error classes for CORS/network/auth/malformed protobuf.
- Document whether Metro Transit GTFS and GTFS-RT endpoints permit direct browser cross-origin requests; if not, choose a privacy-reviewed same-origin proxy. Never silently work around CORS in insecure mode.

### IndexedDB snapshot durability (W-04)
- Stage validated data under (logical feed ID, content hash, format version) with snapshot manifest, integrity hash, service period, timestamps and index metadata.
- Treat a write as committed only after IndexedDB transaction `oncomplete`; on abort/quota failure, preserve previous active generation, report an explicit recoverable error, and clean orphan parts safely.
- On startup, read the active manifest then *rehydrate and validate* archive/index/cache before marking offline data usable. If bytes missing or corrupt, rollback to last good and show degraded status; do not call memory-only file store "durable".
- Provide quota estimates, capped cache/prune policy, multitab concurrent-writer policy (generation/compare-and-swap or lock), and migration/format versioning.

### Production browser bootstrap (W-09)
- Separate `Demo` entrypoint from `Production` entrypoint/build flag; demonstration network, historical radar URL and animated fake vehicle can never load in public product mode.
- Fetch or restore real Metro Transit static feed on first real-use path, show actual progress and licensing/privacy notices, then route and render from validated data.
- Poll GTFS-RT only after configured browser decode/transport works; stale/failed periods show Schedule/no live data, never a fake moving vehicle.
- Tie live data to WO-015 map lifecycle: only display after renderer StyleReady, and clear stale layers.

## PLAN / sequencing
1. Define portability/parity fixture matrix (real valid, unknown fields, malformed, large payload, absent identity), independent from browser UI.
2. Build safe decoder; wire `PollingGtfsRealtimeClient` to asynchronous fetch while preserving JVM adapter behavior.
3. Harden browser fetch and IndexedDB continuation/cancellation semantics, then implement durable manifest activation/read recovery.
4. Wire a real-production browser shell behind explicit build mode, preserve demo only as isolated example.
5. Integrate renderer only after WO-015; no cross-source changes to Android presentation except shared contract adjustments.
6. Run complete integration/negative gate only after phase code is complete.

## Expected paths
- `core/atlas-gtfs-realtime/src/wasmJsMain/kotlin/com/intellitrip/gtfsrt/WasmFeedDecoder.kt`
- `core/atlas-gtfs-realtime/src/commonMain/kotlin/com/intellitrip/gtfsrt/PollingGtfsRealtimeClient.kt`
- `core/atlas-contracts/src/wasmJsMain/kotlin/com/intellitrip/contracts/PlatformFetch.wasm.kt` / `PlatformDurable.wasm.kt`
- `core/atlas-gtfs-static/src/commonMain/kotlin/com/intellitrip/gtfs/StaticFeedRefresher.kt` and `src/wasmJsMain/.../PlatformFileStore.wasm.kt`
- `core/atlas-offline/src/wasmJsMain/kotlin/com/intellitrip/offline/OfflineFileStore.wasmJs.kt` if basemap support is in scope
- `core/atlas-web-spike/src/commonMain/kotlin/com/intellitrip/webspike/WebDemo.kt` (must remain demo only) and production entrypoint files
- New Wasm + Chromium integration tests

## Mandatory proof
- JVM vs browser fixture parity for all three entity kinds, incomplete/malformed messages, big-endian/varint boundary, unknown-field handling and duplicate IDs.
- Real browser fetch through documented CORS or authenticated same-origin proxy, no secrets in frontend; no User-Agent forbidden-header errors.
- Seed once, reload tab **offline**, produce valid scheduled route/board from IndexedDB; clear cache and confirm correct no-data screen; quota error preserves previous generation.
- Two parallel browser tabs, canceled requests, rapid restarts, malformed manifests, expired RT data; correct fallback and no memory/continuation leak.
- Production browser has no `S1`/`S2`/`S3` mock data or fabricated radar/vehicles; demo remains clearly labeled separate.
- `jvmTest`, `wasmJsTest` where supported, webpack, actual Chromium end-to-end, performance/memory budgets, accessibility, and SDK KMP publication checks; receipts link exact SHA.

## REVIEW / CLOSURE
Targeted local Wasm tests for `atlas-gtfs-realtime`, `atlas-web-spike`, and `atlas-map-web` passed after fixing common-test portability and timezone initialization. These tests are not proof of live browser/CORS or offline-reload behavior. No claim of full web realtime or offline parity before real-feed fetch/decode/render and offline reload verification. Independent review must examine malformed input, storage atomicity and privacy. **Dependencies:** WO-015 for map integration and WO-012 for consistent snapshot-generation semantics. No new Android UI overhaul or Play signing.
