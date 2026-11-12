# WO-013 — Android rider interaction and dead-end closure

**Priority:** P1 rider control correctness. **Status:** SPECCED, no runtime changes.  
**Baseline:** `ccf662c7de6e9b0b34e20994366f5ecfec5d5d40` on `master`. **Existing workchain IDs:** W-01 vehicle follow, W-05 walk mode, W-06 weather alerts, W-08 scope anchoring.

## SEED — disconnected actions visible in source

- `IntelliTripViewModel.trackVehicle()` only applies `Focus.Vehicle`. `DefaultScopeResolver` currently resolves that focus with system-wide camera bounds; it does not track changing vehicle coordinates.
- `MapChrome.ScopeSheet` uses `DEFAULT_LAT/DEFAULT_LON` for Nearby and Area regardless of user or camera position.
- `PlanSheet` toggles a `modeWalkOnly` local boolean but its Plan button still calls the same transit planner; straight-line walking estimates are explicitly not street routes.
- `FilterChipRow` renders Now as selected and does not open a time picker. ViewModel `planJourney()` always uses now+120s and `arriveBy=null`.
- `MapCanvas` composes weather layers; alert-markers path is incomplete. Existing NWS viewport fetch can produce data without drawn map geometry.
- `AppState.status` is one string overwritten by unrelated asynchronous operations, causing transient UI messages to hide errors.

## SPEC — no deceptive or dead-end controls

### Vehicle follow (W-01)
- Track control enters `Tracking(vehicleKey, lastFreshPosition?, startedAt)`, with map camera moving only from **fresh matching** vehicle updates. Verify feed + ID match; do not track the first vehicle in a cluster.
- On stale/disappeared vehicle: freeze last verified position, label stale with report age, and offer Resume/Stop; never interpolate an unbounded location.
- On manual pan or explicit Stop: cancel following and programmatic camera updates, but leave transit polling unaffected. Re-tapping Track is idempotent and replaces the previous tracked target.
- Do not silently reuse approximate user location as the vehicle position. Ensure camera-feedback suppression does not swallow genuine manual pan.
- Persist tracking state only as long as session requires; lifecycle stop/restart must not leak observers.

### Scope anchoring (W-08)
- Document unambiguous priority: when rider deliberately panned map, Nearby and Area use the visible viewport center; otherwise prefer a fresh consented location; otherwise use current map center; only cold start may use configured metro overview.
- Nearby = 805m; Area = 3218m. Clamp at poles and dateline; keep gestures and scope inverse zoom formulas stable.
- Never teleport back to Minneapolis when exploring Saint Paul, the suburbs or another city. Compass control: single tap recenters on live location, double tap recenters and resets bearing to north when needed; preserve tilt via two-finger pitch gesture, separate from pinch zoom.

### Walking and departure-time controls (W-05 + planner)
- Until street-network walking routing exists, label Walk only as a **distance estimate**, not a route; disable the transit Plan action while this chip is selected or offer a clearly labeled Switch to transit. No fabricated line drawn along roads.
- If walk routing later implemented, use a real routing adapter with walkable graph, turn directions and accessibility constraints, not haversine straight lines.
- Now opens actual Depart at / Arrive by selection with date, time and zone visible. Reflect state in a typed planning request; defaults should be explicit. On each change invalidate or mark the old journey stale and re-run only on rider action.
- Accessibility: 48dp targets, readable names, explicit chip selection and descriptive error states.

### Weather and status states (W-06)
- Pick one truthful display contract: a) valid area polygons if NWS geometry + clipping implemented; or b) high-visibility alert list/status with area description and no fake polygon/point layer. A point is not equivalent to affected alert area.
- Ensure viewport requests are canceled/replaced after pan; report partial regional results and stale timestamps rather than "clear" for failed requests.
- Replace overloaded `status` as the only error channel with typed per-feature state (loading/success/stale/unavailable/error), and a single transient snackbar channel. A planner failure must not be hidden by a radar update.

## PLAN / integration sequence
1. Create pure reducer/state types for tracking and explicit scope-anchor selection; test with synthetic viewport/vehicle sequences.
2. Wire ViewModel tracking target and camera movement to renderer lifecycle, with feedback suppression and explicit user interruption.
3. Implement typed planner input and genuine date/time controls; give Walk only truthful estimate-only behavior.
4. Define weather alert presentation contract and wire it through map or alert list; expose provenance/failures.
5. Refactor cross-feature status messages into feature-scoped state, then polish screens/semantics. Avoid simultaneous edits to shared Compose roots by assigning one integration owner.
6. Perform full phase completion before test execution per project policy; do not mark gate PASS before device evidence.

## Anticipated files
- `app/src/main/java/com/intellitrip/IntelliTripViewModel.kt`; `MainActivity.kt`
- `app/src/main/java/com/intellitrip/ui/MapChrome.kt`, `MapCanvas.kt`, `MapFeatureSheet.kt`
- `core/atlas-scope/src/commonMain/kotlin/com/intellitrip/scope/DefaultScopeResolver.kt`
- `core/atlas-map-android/src/main/java/com/intellitrip/map/android/MapLibreMapRenderer.kt`
- `core/atlas-weather/src/commonMain/kotlin/com/intellitrip/weather/WeatherLayerComposer.kt`
- New feature-state and calendar UI files, pure tests, Compose instrumented tests

## VERIFICATION and release gates
- Track live vehicle A -> camera follows A; B never hijacks; stale A freezes/labels; user pan stops follow; reselect resumes; onPause/onResume no duplicate observers. Repeat 15s updates and rapid taps.
- Scope presets use actual visible center after manual pan; current location consent honored; latitude/pole tests; double-tap north and two-finger tilt verified physically.
- Walk-only never submits a transit request; time selection changes planner request; overnight date/time honored; Arrive by unavailable state never silently becomes Depart at.
- Real NWS alert is shown with honest region, time, attribution; missing geometry does not fabricate a marker; failed provider not labeled clear.
- Concurrent status updates preserve feature-specific errors; large font (200%), TalkBack traversal, no clipped primary controls; 13 baseline instrumented tests plus new suites, real-feed rider journey on BLU.
- CI, lint, debug/release build, accessibility and map video receipts before ready-for-review.

## OUT OF SCOPE / dependencies
No redesign of transit topology, remote OTP, new background location permission, fake walk route, or speculative live ETA. WO-011 schedule truth and WO-012 snapshot correctness are release gates, but this PR's implementation is source-separable.
