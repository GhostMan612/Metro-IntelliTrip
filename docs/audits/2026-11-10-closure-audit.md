# Metro IntelliTrip — closure audit (2026-11-10)

**Baseline:** `master` at `15009e67cd94ddcc129f10ac87e6a36dc1bc2a2d`. **Review target:** `master`, not the almost-empty default `main`.
**Status:** RELEASE NO-GO pending independently successful CI, real-feed validation and targeted runtime checks. This audit is based on source inspection, repository metadata and GitHub Actions logs; it is not a statement that the modified branch has been compiled or run on a phone.

## Evidence and immediate corrections

| ID | Finding | Evidence | Action in this PR | Gate |
| --- | --- | --- | --- | --- |
| A-01 | CI calls legacy `:core:atlas-gtfs-static:test` after KMP migration; the November 9 workflow fails with "task 'test' not found". 29 MB feed download succeeded, parser tests did not run. | [Failed run 42681727598](https://github.com/GhostMan612/Metro-IntelliTrip/actions/runs/42681727598), `.github/workflows/ci.yml` | Change real-feed tasks to `jvmTest`, supply absolute fixture path, fail absent fixtures under CI | Both named tests execute, then lint, assembleDebug and sample host execute |
| A-02 | Fresh Android installation can seed from bundled `debug-gtfs.zip` without checking build type | `AtlasHost.kt`, `IntelliTripViewModel.kt` | Reject bootstrap for non-debuggable installations; retain opt-in debug experience | Release cold start never activates synthetic feed |
| A-03 | Saved screen supplies an empty callback for "Back to map" | `MainActivity.kt` | Wire `onCloseToExplore` | UI tap returns to map |
| A-04 | Latitude bounds clamp the northern edge to >=0, breaking southern-hemisphere radius | `DefaultScopeResolver.kt` | Replace with northbound max-90 clamp and regression test | Radius bounds contain -33.86 at Sydney |
| A-05 | Web bridge omits empty layers, so stale features remain in existing GeoJSON sources | `WebRenderBridge.kt`, `WebMapRenderer.kt` | Send all layer payloads including empty collections; update bridge test | Nonempty -> empty removes map feature |
| A-06 | Web radar reapply removes the existing layer without re-adding it | `WebMapRenderer.kt` | Recreate source and layer on frame update | Two successive frames show second frame |
| A-07 | Documents claim a non-existent Android offline basemap import path and refer to deleted atlas-transit | `HUMAN_ACTIONS.md`, `README.md`, `AGENTS.md` | Correct claims and link deferred execution | Docs no longer promise unavailable UI |

**Note on A-01:** Both real-feed fixture-based tests previously printed `SKIPPED` and returned when data was absent. They now fail if `CI=true` or an explicit fixture property was supplied. Local unconfigured tests remain optional. Validate the Gradle test filtering and KMP task availability in CI; a green suite that never executed the named tests does not count.

## Confirmed missing, disconnected or explicitly deferred behavior

These are **NOT claimed fixed** by this PR. Each has a receipt-backed workchain item under `docs/workchains/`.

| ID | Severity | Behavior and precise boundary | Primary files | Chain |
| --- | --- | --- | --- | --- |
| D-01 | High | Android `Track this` sets `Focus.Vehicle`, but scope resolver uses system-wide camera bounds; no follow-target position/camera loop and no genuine stop-tracking toggle | `IntelliTripViewModel.kt`, `DefaultScopeResolver.kt`, `MapFeatureSheet.kt`, `MapCanvas.kt` | W-01 |
| D-02 | High | Offline basemap package store exists, but Android Data/More sheets have no safe import/install action or renderer style binding | `OfflineBasemapStore.kt`, `MapChrome.kt`, `SecondaryScreens.kt`, `MapCanvas.kt` | W-02 |
| D-03 | High (web) | wasm GTFS-Realtime protobuf decoder explicitly throws; there is no live GTFS-RT in web shell | `WasmFeedDecoder.kt`, `PollingGtfsRealtimeClient.kt` | W-03 |
| D-04 | High (web) | wasm file stores are process-only maps; durable IndexedDB payload writes lack an active snapshot restoration path | `PlatformFileStore.wasm.kt`, `OfflineFileStore.wasmJs.kt`, `PlatformDurable.wasm.kt`, `StaticFeedRefresher.kt` | W-04 |
| D-05 | Medium | Walk-only control gives a marked straight-line estimate; Plan button still uses transit planner | `MapChrome.kt`, `IntelliTripViewModel.kt`, `StaticNetworkPlanner.kt` | W-05 |
| D-06 | Medium | Android weather alert markers are composed, but `MapCanvas` handles `AlertMarkers` as `Unit`; renderer's `applyAlerts` is not invoked | `WeatherLayerComposer.kt`, `MapCanvas.kt`, `MapLibreMapRenderer.kt` | W-06 |
| D-07 | Medium | Local OTP fallback adapter exists, but Android's composition root only injects `StaticNetworkPlanner`. Remote trip-to-local ID reconciliation and live endpoint remain open | `AtlasHost.kt`, `HybridRoutingProvider.kt`, `OpenTripPlannerRoutingProvider.kt` | W-07 |
| D-08 | Medium | Nearby/Area scope presets use a fixed downtown Minneapolis center even when map or user is elsewhere | `MapChrome.kt`, `IntelliTripViewModel.kt` | W-08 |
| D-09 | Medium (web) | Web shell is an explicit 3-stop demonstration with synthetic vehicle and hard-coded historic radar frame; MapLibre async style-ready lifecycle remains unverified | `WebDemo.kt`, `Platform.kt`, `WebMapRenderer.kt` | W-09 |

## Gates required before merge or release

1. CI on this PR must independently pass `test`, **both actual real-feed KMP JVM tests**, `lint`, `assembleDebug`, `:sample:host:run`, and instrumented Android tests. Browser `jvmTest`, `compileKotlinWasmJs`, dev webpack and vendor copy must be separately checked.
2. Capture JUnit evidence showing that fixture-based Metro Transit tests executed rather than skipped, with a nonempty feed and planner journeys. Use an absolute fixture path and check actual test reports.
3. Release safety: fresh non-debuggable install with no saved snapshot starts empty/asks to download full GTFS; debug builds may opt into synthetic seeding; a previously valid real snapshot is unaffected.
4. Saved screen Back button, southern-hemisphere scope test and browser nonempty-to-empty/radar-frame updates pass.
5. Device checks: low-memory Android parsing and departure lookup, GPS/vehicle follow behavior (deferred until W-01), map style restoration and no synthetic production data. Do not conflate emulator tests with physical-device sign-off.
6. After CI, run a fresh audit of the changed paths and compare against the completion criteria. Any unverified gate remains **OPEN**, not “green by documentation.”

## Repository housekeeping (not in PR)

The remote default remains `main` containing only the initial README; active development and CI target `master`. Change default branch in GitHub repository settings only after confirming the owner's preferred canonical branch. Do **not** merge this PR into `main`.

## How to read this audit

A source-backed defect is **confirmed by code**, but the runtime result of a fix is not established until a test/CI/device receipt exists. No claims of external OTP availability, production-safe offline basemap imports, full browser realtime parity, or Play readiness are made.
