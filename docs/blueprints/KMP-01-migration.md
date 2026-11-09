# Phase M1 — KMP Migration Groundwork

## Objective

Sequence the Kotlin Multiplatform pivot so the Atlas engine runs in browsers
without a rewrite. This phase is scaffolding + proof, no behavior change.

## Prerequisites

- V0/V1 frozen; `PortabilityGuardTest` green; BLU snapshot verified.
- Decision recorded (2026-11-08): **single-codebase KMP (Option A)**, sequenced
  Android-first. React/TS duplicate is rejected (two planners will disagree on
  Saturday). Android-first keeps Play on track while seams land.

## In scope

### 1. Blueprint (this file)

Full seam inventory with file:line owners, Wasm map interop plan,
compatibility-mode decision, and spike results. The migration order is:

| Module | Platform deps today | M1 | M2 | M3 |
|---|---|---|---|---|
| `atlas-domain` | `java.time`, `java.util.Locale` | Spike + seams | Convert | — |
| `atlas-contracts` | `java.time` via domain, Flow | — | Convert | — |
| `atlas-scope` | java.time via domain | — | Convert | — |
| `atlas-gtfs-static` | `java.nio`, `java.net`, `java.io` | — | Seams | Streaming |
| `atlas-gtfs-realtime` | protobuf, `java.io` | — | Seams | — |
| `atlas-map` (pipeline) | none (pure) | — | Convert | — |
| `atlas-routing` | `java.time`, coroutines | — | Convert | — |
| `atlas-offline` | `java.nio` | — | Seams | — |
| `atlas-weather` | `java.net`, coroutines | — | Seams | — |
| `atlas-engine` | composition only | — | Convert | — |
| `atlas-map-android` | MapLibre Android | — | — | `expect/actual` renderer |
| `app` | Compose Android, Android APIs | — | — | Remains Android shell |

### 2. The three seams (M2)

1. **Time** — `java.time.{Instant,LocalDate,Duration,ZoneId,DayOfWeek}` →
   `kotlinx.datetime` (`Instant`, `LocalDate`, `Duration`, `TimeZone`) in
   `commonMain`. `DayOfWeek`/`ZoneId` need `expect/actual` (JVM keeps IANA db;
   Wasm has no IANA db — store zone as id string, resolve to fixed offsets,
   document that wall-clock DST rules on web are best-effort).
   *Breaking:* public API `Instant` changes JVM→common. Android app + published
   SDK (Flutter consumer) must migrate. This is M2's big bang — do it once,
   for all modules, with a migration guide.
2. **Files** — `java.nio.file`, `java.io` → `expect class PlatformStore`
   (JVM/Android `java.nio`, Wasm IndexedDB via `actual`).
3. **HTTP** — `java.net.HttpURLConnection` → Ktor engine (`expect`/`actual`:
   `OkHttp` / `DarwinGum` / `Js` fetch). `GtfsRealtimeFetcher` already funnels
   through `HttpTransport` — the seam exists.

### 3. Wasm map interop (M3)

- `MapRenderer` stays the contract. `wasmJsMain` actual renders via
  MapLibre GL JS (`@JsExport` + `jsFun`) or Leaflet for the first screen.
- `Icon`/`GeoJSON` bridge: `GeoJsonWriter` is already pure strings — reuse it
  verbatim on web. No renderer code in `commonMain`.
- Browser support: Compose Multiplatform compatibility fallback (Wasm → JS)
  for older browsers that lack WasmGC. No IE11.

### 4. Spike (this phase, proof only)

- New `core/atlas-web-spike` KMP module (`jvm` + `wasmJs` targets) with
  `commonMain` `SpikeTime` using `kotlinx-datetime` and matching
  `jvmMain`/`wasmJsMain` `actual`s. Proves both targets compile/run without
  touching production `atlas-domain`.
- `atlas-domain` itself is **not** converted yet: its public API is
  `java.time`-typed (Android app + published SDK consume it). Converting now
  without an API migration would break `./gradlew assembleDebug`.

## Out of scope

- Any engine behavior change, new providers, new UI (done in M2b).
- Browser UI beyond the spike (M3).
- Touching `core/atlas-transit` (quarantined, ADR-014). Published Maven
  coordinates bumped to `0.2.0` in M2b (breaking time API).

## Tests/gates — zero tests until complete

No tests during implementation. At phase-complete, **one** gate:

1. `:core:atlas-web-spike:compileKotlinWasmJs` + `:core:atlas-web-spike:jvmTest` green
2. `assembleDebug` + `lint` + `test` (whole project) green — spike must not
   break the Android build
3. Browser run of the spike (`:atlas-web-spike:wasmJsBrowserDevelopmentRun`) —
   prints a timestamp, no map yet

## Definition of Done

M2/M3 are unblocked: the seam inventory is accepted, Wasm tooling is proven
end-to-end, and the Android build is untouched.

## Freeze gate

Spike wasm bundle loads in Chromium with no console WasmGC error.

## Artifacts

- This blueprint
- `core/atlas-web-spike/` (KMP jvm + wasmJs, kotlinx-datetime)
- Updated `settings.gradle.kts` (spike included, excluded from publish)

## Status

Implemented (M1 spike + M2a bridge + M2b breaking rename + M3b KMP domain +
M3a portable-stack KMP + M3c I/O seams + M4a wasm renderer/web shell).
Gate: spike `jvmTest` + `compileKotlinWasmJs` + all KMP modules
`jvmTest` + `compileKotlinWasmJs` + `compileCommonMainKotlinMetadata` +
`test` + `lint` + `assembleDebug` + `assembleRelease` (with R8) +
`publishAtlasToLocalRepo` + `:sample:host:run` all green. Browser run still
needs a Chromium check by the user; shell parity (polling, radar, IndexedDB)
is M4b.

`TimeBridge` (M2a) was removed in M2b when the production code itself moved
to `kotlinx.datetime` — the bridge's tested conversions were absorbed as the
new API.

## M2a delivered (2026-11-08)

- `core/atlas-domain/src/main/.../TimeBridge.kt`: `java.time ↔ kotlinx.datetime`
  + `kotlin.time.Duration` conversions (kotlinx `Duration` moved to `kotlin.time`
  in 0.6.2 — first attempt failed to resolve, fixed).
- `core/atlas-domain/src/test/.../TimeBridgeTest.kt`: 4 round-trip tests.
- `atlas-domain` depends on `kotlinx-datetime:0.6.2` alongside `java.time`
  (non-breaking).

## M2b delivered (2026-11-08) — kotlinx.datetime on JVM (+ app)

Breaking rename, all modules + Android `app` in one commit (legacy `java.time`
build preserved on branch `archive/android-legacy`, tag
`archive/android-legacy-2026-11-08`):

- `java.time.{Instant,LocalDate,Duration,ZoneId,DayOfWeek}` → `kotlinx.datetime`
  (`Instant`, `LocalDate`, `TimeZone`, `DayOfWeek`) + `kotlin.time.Duration`
  everywhere (187 hits, 50 files). `Instant.now()` → `Clock.System.now()`,
  `Duration.between(a,b)` → `b-a`, `.seconds` → `.inWholeSeconds`,
  `LocalDate.of(y,m,d)` → `LocalDate(y,m,d)`, `plusDays/minusDays` →
  `plus(n, DateTimeUnit.DAY)`, `isBefore/isAfter` → `<`/`>`, `monthValue` →
  `month.number`, `dayOfMonth` kept.
- `AgencyId`/`FeedId` changed from `@JvmInline value class` to `data class`
  (portable; source-compatible — construction and `.value` unchanged,
  binary-incompatible).
- Every JVM module + `app` declares `kotlinx-datetime:0.6.2` +
  `kotlinx-serialization-core:1.8.0` (R8 release needs serialization-core for
  datetime serializers). `atlas-domain` exposes both as `api`.
- SDK version bumped `0.1.0` → `0.2.0` (see `docs/SDK_INTEGRATION.md`).
- Toolchain stays `17` everywhere (Android requires 17). M2b attempt at full
  KMP `atlas-domain` (jvm+wasmJs) was reverted to JVM-only: KMP common-metadata
  compilation needs Kotlin 2.1+ (the environment's 2.0.20 compiler rejects the
  2.1.0 stdlib klib), and `java-library` publishing is incompatible with KMP.
  Full domain KMP + KMP Maven publishing is M3b (with the Kotlin/AGP upgrade).
  The `atlas-web-spike` (wasmJs) remains the web proof.

Gate (all green): `test` + `lint` + `assembleDebug` + `assembleRelease` +
`publishAtlasToLocalRepo` + `:sample:host:run` + spike `jvmTest` +
`compileKotlinWasmJs`.

## M3b delivered (2026-11-09) — toolchain upgrade + KMP domain

- Toolchain upgraded together (single commit, green at each step): Kotlin
  `2.0.20` → `2.1.20`, AGP `8.5.2` → `8.7.3` (Gradle stays `8.10`, toolchain
  stays `17`). All JVM modules + `app` re-verified before the KMP move.
- `atlas-domain` is now Kotlin Multiplatform (`jvm` + `wasmJs`): sources in
  `src/commonMain`/`src/commonTest` (`src/main`/`src/test` removed). IDs stay
  `data class` (`@JvmInline` value classes still do not compile for
  wasmJs; portability over inlining — same call as M2b).
- KMP Maven publishing works: `publishAtlasToLocalRepo` publishes
  `atlas-domain` + `atlas-domain-jvm` + `atlas-domain-wasm-js` (Gradle
  `.module` metadata) alongside the JVM-only sibling artifacts. The root
  `java-library` publication stays disabled for this module (`java-library`
  is incompatible with KMP); `atlas-domain` configures its own publications
  + `atlasLocal` repo. Remaining M3 work: Files/HTTP seams + converting the
  other core modules to KMP (engine first per the table above); `app` stays
  the Android shell.

Gate (all green): domain `jvmTest` + `compileKotlinWasmJs` +
`compileCommonMainKotlinMetadata` + full `test` + `lint` + `assembleDebug` +
`assembleRelease` + `publishAtlasToLocalRepo` + `:sample:host:run` + spike
`jvmTest` + `compileKotlinWasmJs`.

## M3a delivered (2026-11-09) — portable stack KMP (contracts/scope/map/routing/engine)

Pure-Kotlin modules converted to KMP (`jvm` + `wasmJs`, `src/commonMain` /
`src/commonTest`; `atlas-routing` keeps its real-feed test in `src/jvmTest` —
it needs `java.nio` temp files + the still-JVM-only gtfs-static):

- `atlas-contracts`, `atlas-scope`, `atlas-map` were already pure — straight
  move + KMP publishing (same `withType` template as the domain).
- `atlas-routing`: `Dispatchers.IO` → `Dispatchers.Default`,
  `IOException`-specific catch merged into generic `Exception`
  (NetworkFailure unchanged), `URLEncoder` → percent-encode helper (identical
  output), `%04d` date formatting → `padStart`, `Math.toRadians` →
  `kotlin.math.PI` math. Planner logic untouched.
- `atlas-engine` decoupled from JVM-only `atlas-offline` (blocked engine
  wasm): replaced `offlineRegistry: OfflineRegistry?` with portable
  `offlineAvailable: Boolean` and removed the `offlineCapabilities()` delegate
  (its only callers were tests; app `ViewModel` already queries
  `host.offlineRegistry` directly). Engine now has zero offline dependency;
  offline detail remains host-side until the offline Files seam lands.
  App `AtlasHost` updated (`offlineAvailable = true`).

All six KMP modules publish `*-jvm` + `*-wasm-js` variants at `0.2.0`.
Remaining JVM-only (need Files/HTTP seams next): `atlas-gtfs-static`
(`java.nio`/`java.io`), `atlas-gtfs-realtime` (protobuf/`java.io`),
`atlas-offline` (`java.nio`), `atlas-weather` (`java.net`).

Gate (all green): every KMP module `jvmTest` + `compileKotlinWasmJs` +
`compileCommonMainKotlinMetadata` + full `test` + `lint` + `assembleDebug` +
`assembleRelease` + `publishAtlasToLocalRepo` + `:sample:host:run` + spike
`jvmTest` + `compileKotlinWasmJs`.

## M3c delivered (2026-11-09) — I/O seams (files + HTTP), every core module KMP

The last JVM-only modules converted with `expect`/`actual` seams; wasm.js
stubs are explicit (throw with TODO for IndexedDB/async-fetch) rather than
fake data:

- `atlas-gtfs-static`: `PlatformFileStore` seam (JVM `java.nio`, wasm
  in-memory), portable DEFLATE ZIP reader over `ByteArray`, portable
  SHA-256. Byte-identical on-disk formats (golden test). New
  `StaticFeedSnapshotStore.readCache()` (byte fast path, used by offline).
- `atlas-gtfs-realtime`: portable decoded model + `FeedDecoder` expect seam
  (JVM Java-protobuf actual, wasm stub); `UrlGtfsRealtimeFetcher` expect
  (JVM `HttpURLConnection`, wasm stub). Mapping/polling fully portable.
- `atlas-offline`: `OfflineFileStore` expect seam; pure `StalenessModel` /
  `BasemapPolicy` / digest in common. `OfflineRegistry` takes portable
  `OfflineSnapshotPort`; `loadStaticFeed` is a jvmMain extension.
- `atlas-weather`: `WeatherHttpClient` expect seam (JVM `HttpURLConnection`,
  wasm stub); providers already pure JSON.

Host migrations (app): `StaticFeedSnapshotStore(PlatformFileStore(path))`,
`OfflineRegistry(JvmOfflineSnapshotPort(store))` + `loadStaticFeed` import,
`OfflineBasemapStore(String)`, stop-time reader read without `use`
(in-memory, no longer `Closeable`).

All ten core modules now publish `*-jvm` + `*-wasm-js` at `0.2.0`.
Next: `atlas-map-android` renderer `expect`/`actual` (M4 web shell) +
IndexedDB persistence follow-ups.

Gate (all green): every KMP module `jvmTest` + `compileKotlinWasmJs` +
`compileCommonMainKotlinMetadata` + full `test` + `lint` + `assembleDebug` +
`assembleRelease` + `publishAtlasToLocalRepo` + `:sample:host:run` + spike
`jvmTest` + `compileKotlinWasmJs`.

## R1 delivered (2026-11-09) — correctness sweep (audit-driven)

From the repo audit (architecture/quality/production):

- Stop-time index cached per snapshot dir (`StaticFeedSnapshotStore`,
  `SimpleLock`, 4-dir bound) so the departure board no longer re-reads both
  files per call; haversine computed once per stop in `nearbyDepartures`.
- `SavedPlacesStore.load()` falls back to the backup generation on corruption
  (previously sat unused — a bad write now costs the last change at worst).
- ViewModel `_state` mutations all atomic (`update{}`); the IO-dispatcher
  move was **reverted**: `Dispatchers.IO` does not resolve on wasmJs, so
  `Default` is the portable choice (documented in code).
- Planner/board frequency caps documented as deliberate (300 search vs 20
  board/2h horizon) + stale planner comment fixed; OTP `RouteKey` identity
  limitation pinned with KDoc (blocks live OTP until a mapping lands);
  hybrid `UNKNOWN_FRESHNESS` rationale documented vs `StalenessModel`.
- NWS missing-User-Agent already surfaces as `AuthFailure` — verified, no
  change.

Gate (all green): `test` + `lint` + `assembleDebug` + `assembleRelease`.

## R2 delivered (2026-11-09) — docs + guards

- Doc drift sweep: ADR-007 topology closed in `ARCHITECTURE.md` /
  `DATA_SOURCES.md` / `RoutingProvider.kt`; `AtlasEngine` offline comments
  corrected (facade boundary, not platform); `atlas-transit` phrasing
  updated to ADR-014 (retired) in `AGENTS.md`/`README.md`.
- File-seam decision recorded in KDoc on both separators (`PlatformFileStore`
  vs `OfflineFileStore`): containment/security vs snapshot/index needs —
  keep both. Freshness contracts documented as related-not-identical.
- Guards: `PortabilityGuardTest.moduleDependenciesAreAcyclic` (build-file
  graph, catches routing↔offline-style cycles); new unit tests for
  `backoffMs`, `formatterTime`/`journeyHeadline`/`legSummary`; plus a fix to
  the stale `RealFeedPlanTimeTest` (bytes-based index API from M3c), which
  the audit's fresh compile surfaced.

Gate (all green): `test` + `lint` + `assembleDebug` + `assembleRelease`.

## M4a delivered (2026-11-09) — wasm MapRenderer + first browser screen

- New `core/atlas-map-web` (KMP): `WebRenderer` contract + pure
  `snapshotToSources` bridge (JVM-tested GeoJSON per WebLayer) + wasmJs
  `WebMapRenderer` (MapLibre GL JS via JS interop; host page loads a pinned
  CDN build, webpack-free). Each layer = one source + one layer, mirroring
  the Android bulk-update pipeline. First render centers; later renders
  refresh data only (never fight a pan).
- `atlas-web-spike` is the web shell: production `AtlasEngine` +
  `StaticNetworkPlanner` over a tiny in-memory demo network (3 stops, Blue
  Line, 2 trips), plans First → Third, renders the demo snapshot via
  `TransitRenderPipeline` → `WebMapRenderer`. MapLibre pinned 5.0.4; offline
  vendoring + `IntlTripViewModel` UI parity stay later work.

Gate (all green): `atlas-map-web` + spike `jvmTest` + `compileKotlinWasmJs`
+ full `test` + `lint` + `assembleDebug` + `assembleRelease` +
`publishAtlasToLocalRepo` + `:sample:host:run`.

## M4b delivered (2026-11-09) — shell parity (glide loop + radar overlay)

- `WebRenderer.setRadarOverlay(RadarOverlay?)`: raster-tile source/layer
  (`radar-layer`, `setData`-style replacement on re-set, clean removal).
  The demo shell mounts the RainViewer past-frame overlay; providers hand
  it `RadarFrame.tileUrlTemplate` directly.
- Demo vehicle glides S1 → S3: `WebDemo.demoSnapshot(progress)` interpolates
  the fix; the wasm host polls `setSources` every 200 ms (Android glide
  parity), and the renderer still never recenters after the first frame.
- Spike + `atlas-map-web` covered by `jvmTest` + `compileKotlinWasmJs`.

Gate (all green): `atlas-map-web` + spike `jvmTest` + `compileKotlinWasmJs`
+ full `test` + `lint` + `assembleDebug` + `assembleRelease` +
`publishAtlasToLocalRepo` + `:sample:host:run`.

## M4c delivered (2026-11-09) — async fetch seam (browser fetch bridge)

- Shared seam in `atlas-contracts`: `expect suspend fun platformFetch`
  (JVM `HttpURLConnection` on the IO pool; wasmJs `fetch` + `arrayBuffer()`
  via `@JsExport`d completion bridged into coroutines).
- Weather `getAsync`, realtime `fetchAsync`, and static-GTFS
  `openAsync`/`acquireAsync` (refresher now fetches async end-to-end);
  legacy sync fetchers stay for tests/JVM hosts. Providers call the async
  variants, so wasm has a real transport (`WeatherNetworkException` only on
  genuinely failed requests).
- Web shell gains origin/destination selects + Plan button (re-plan between
  demo stops through the production engine).

Remaining: IndexedDB persistence (in-memory wasm stores today), vendored
MapLibre (CDN pinned 5.0.4 today), and UI parity with Android.

Gate (all green): affected `jvmTest` + `compileKotlinWasmJs` + full
`test` + `lint` + `assembleDebug` + `assembleRelease` +
`publishAtlasToLocalRepo` + `:sample:host:run`.
