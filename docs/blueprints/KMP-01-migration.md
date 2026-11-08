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

- Any engine behavior change, new providers, new UI.
- Browser UI beyond the spike (M3).
- Touching `core/atlas-transit` (quarantined, ADR-014) or the published
  Maven coordinates (still `0.1.0` JVM).

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

Implemented. Gate: `:core:atlas-web-spike:jvmTest` + `compileKotlinWasmJs` green.
Browser run (`wasmJsBrowserDevelopmentRun`) needs a Chromium check by the user.
`atlas-domain` conversion is M2 (needs API-breaking `java.time → kotlinx.datetime`
decision recorded above).
