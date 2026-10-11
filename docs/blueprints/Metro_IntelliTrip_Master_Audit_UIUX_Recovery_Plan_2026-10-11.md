# Metro IntelliTrip — Repository Audit, Defect Recovery & UI/UX Master Plan

**Audit date:** 2026-10-11  
**Repository:** https://github.com/GhostMan612/Metro-IntelliTrip  
**Audited branch:** `master`  
**Audited revision:** [`50d85321b228325dbf5c91d48e696600c137d270`](https://github.com/GhostMan612/Metro-IntelliTrip/commit/50d85321b228325dbf5c91d48e696600c137d270)  
**Default GitHub branch:** `main` (not the active development branch)  
**Audit method:** Read-only live GitHub connector inspection of repository tree, source files, architecture/blueprints, commit history, CI workflow and failed CI job logs; public primary-source research of competing transit apps and radar API.  
**No repository changes made.**

> **Confidence and scope.** A *confirmed source defect* is directly demonstrated by checked-in code or CI logs. A *probable defect* is supported by code but requires runtime/device verification. A *gap* means functionality is missing or not connected, not necessarily an exception or crash. This is a remote static audit, **not a completed local build, instrumentation run, accessibility test, penetration test, or visual screenshot inspection**. Executor/device test claims in commit messages are not independent proof. Audit findings must be rechecked if `master` advances beyond the revision above.

---

## 1. Executive verdict

**Overall: CONDITIONAL NO-GO for public release; GO for a structured recovery and redesign program.**

IntelliTrip is no longer a Phase-01 prototype. The current repository contains a substantial architecture and implementation: pure Kotlin domain/contracts, static GTFS and GTFS-Realtime adapters, a disk-backed static index, offline registry, MapLibre bulk-layer rendering, NWS alerts/RainViewer radar, local trip planning, an OTP adapter, scope resolution, transfer scoring, a portable Atlas SDK facade, sample host, instrumented test sources, and a Compose Android shell. The existing system is valuable; **do not restart or replace it wholesale**.

However, the **user-facing product is much less complete than the codebase suggests**. Its trip planner is not visibly operable through the current UI; weather controls and permissions contain functional inconsistencies; the map has potential lifecycle/state feedback problems; several data-provider and persistence paths need correction; and CI is red for a concrete, simple tooling reason. The most recent UI pass reduced clutter but has not established a coherent navigation architecture, design system, accessibility baseline, or production-quality rider experience.

### Immediate decisions

1. **First make CI truthful and green.** The latest ten visible workflow runs failed, including the newest run; its logs show `./gradlew: Permission denied` before unit tests or Android tests execute. This is a missing executable bit/invocation defect, not a proven Kotlin test failure.
2. **Unblock the core rider task: planning a trip.** `IntelliTripViewModel.planJourney()` exists, but the current `MapChrome`/`PlanSheet` do not expose a plan action, origin/destination editing, or trip selection. A rider opening “Plan” can see only “Load the static network,” “No journeys yet,” or preexisting results.
3. **Fix safety and integrity bugs before enabling imported files.** `OfflineBasemapStore.safeName()` uses a literal string replacement rather than a regular expression. Package identifiers and imported path handling need a security boundary before installation reaches the UI.
4. **Stabilize the map/location/provider pipeline.** Particularly MapView lifecycle, programmatic-camera/gesture feedback, coarse-only location, GTFS HTTP conditional refresh, and RainViewer tile URL/terms.
5. **Deliver a true consumer-grade redesign on top of Atlas, not instead of Atlas.** Prioritize a fast “Where to?” flow, nearby departures, readable trip cards, actionable stop/vehicle details, favorites, and accessible map/list duality. Preserve offline-first, provider-neutral, no-forced-account principles.
6. **Reopen release-readiness, not finished engine phases.** Docs mark Phases 00–09 closed. The remaining work is product integration, hardening, UI/UX, operational validation, SDK compatibility, and publishing; define explicit post-freeze phases instead of hiding “Phase 10” under Phase 09.

### Priority legend

| Priority | Meaning | Policy |
|---|---|---|
| **P0 — Blocker** | Broken pipeline, essential task unavailable, or unsafe boundary before use | Fix before UI expansion; test and commit separately |
| **P1 — High** | Rider correctness, data integrity, device stability, misleading status | Fix before public beta |
| **P2 — Medium** | Professional UX, feature completion, performance, architecture consistency | Deliver during controlled redesign |
| **P3 — Follow-up** | Advanced enhancements, optional provider integrations, commercialization | Explicitly defer until core acceptance |

---

## 2. What is actually in the repository

### 2.1 Current implementation inventory

| Module / component | Evidenced implementation | Readiness judgment |
|---|---|---|
| `core/atlas-domain` | Feed-scoped IDs, GTFS service time, journeys, weather, geo/scope | Foundation implemented; retain tests |
| `core/atlas-contracts` | Provider result/freshness, transit/routing/weather/location contracts | Foundation implemented |
| `core/atlas-gtfs-static` | ZIP/CSV parser, acquisition, disk-backed stop-time index, snapshot store/refresher | Implemented; conditional-refresh/storage flaws below |
| `core/atlas-gtfs-realtime` | Polling, mapping, vehicle/trip/alert contract adapters | Implemented; live reliability/reconciliation needs tests |
| `core/atlas-map` | GeoJSON, culling, clustering, route geometry, render throttling | Implemented; geometry and update risks below |
| `core/atlas-map-android` | MapLibre rendering, sources/layers, radar and planned routes | Implemented; lifecycle and overlay issues below |
| `core/atlas-weather` | RainViewer radar; NWS alert adapter | Implemented; live API contract and radar rendering flawed/ungated |
| `core/atlas-scope` | Radius/system/trip/route/viewport resolver | Implemented; gestures/programmatic camera conflict |
| `core/atlas-routing` | StaticNetworkPlanner, transfer scoring, hybrid adapter, OTP mapper | Implemented; UI unwired, OTP live compatibility unverified |
| `core/atlas-offline` | Snapshot/staleness/basemap package management | Partial product: no installed/verified working offline basemap |
| `core/atlas-engine` | AtlasEngine and optional providers | Portable facade exists; app often calls adapters directly |
| `core/atlas-map-android` | Android renderer excluded from Maven SDK | Valid portability split |
| `sample/host` | JVM host | Useful boundary proof, not cross-repo Android acceptance |
| `app` | MainActivity, AtlasHost, ViewModel, full map, data/scope/plan sheets, location | Functional skeleton; not finished passenger app |
| `.github/workflows/ci.yml` | Build and emulator jobs | **Failing** due to Gradle wrapper permissions |
| `core/atlas-transit` | Retired/quarantined Android prototype | Still present; preserve protected local edits |

### 2.2 Documented phase status versus reality

- `AGENTS.md` and `docs/blueprints/PHASE-09-sdk-extraction.md` report **Phases 00–09 closed**. Treat them as architecture/engine milestones, not proof of product release readiness.
- `PHASE-09-sdk-extraction.md` embeds a separate **“Phase 10 – App completion”** section rather than tracking it as its own blueprint and freeze gate. It lists open verification items: live OTP integration, offline tile installation, RainViewer risk, retired `atlas-transit`, and release packaging.
- `HUMAN_ACTIONS.md` says static network, live vehicles, planning, radar, alerts, and map work on-device; parts of that claim conflict with the currently inspected UI wiring and radar URL construction. **Mark as “reported” until validated against current revision.**
- `docs/ARCHITECTURE.md` still calls `app/` a **“non-networked placeholder”**, which no longer describes the current Compose map and ViewModel.
- No open GitHub issues or pull requests were returned by the connector's repository issue/PR search. **No tracked issues does not mean no outstanding work.** Create an issue/blueprint backlog after agreeing on this plan.
- The repository default branch is `main`, but CI and documented development target `master`. The branches are not the same revision. Make one clearly authoritative for landing, reviews, and user discovery after preserving history.

### 2.3 CI evidence

- Latest run: https://github.com/GhostMan612/Metro-IntelliTrip/actions/runs/38142122223
- Newest build job: `./gradlew test --console=plain` → `./gradlew: Permission denied`, exit **126**.
- Newest emulator job: emulator boots, but `./gradlew :app:connectedDebugAndroidTest` → `Permission denied`, exit **126**.
- Because the unit test step fails first, lint, assemble and JVM host jobs are skipped. **CI does not independently certify the app's quality at this SHA.**
- Recommended repair: commit the executable bit (`git update-index --chmod=+x gradlew` from an authorized local checkout), verify mode `100755`; alternatively use `bash ./gradlew` in CI as a temporary workaround. Add a regression check. Do not mask an actual failing test once CI begins executing.

---

## 3. Prioritized defect and gap register

### P0-01 — CI cannot run the Gradle wrapper [CONFIRMED]

**Evidence:** `.github/workflows/ci.yml`, latest GitHub Actions job logs above. Both jobs fail with exit 126 before Gradle starts.  
**Impact:** No verifiable merge gate; broken code can ship unnoticed.  
**Action:** Fix Git file mode and runner invocation; rerun `test`, `lint`, `assembleDebug`, `:sample:host:run`, and `:app:connectedDebugAndroidTest`. Record job URLs and test counts. Require green CI before feature PRs.  
**Acceptance:** All intended CI jobs green twice, including clean-cache run; no permissions workaround concealed in a developer-specific environment.

### P0-02 — Trip planner exists in the ViewModel but is not wired into the current UI [CONFIRMED]

**Evidence:** `IntelliTripViewModel.kt` exposes `planJourney`, `setOrigin`, `setDestination`, `selectJourney`; `MainActivity.kt` passes no planner callbacks to `MapChrome`; `MapChrome.kt`’s `PlanSheet(state)` is read-only and `return`s on empty journeys.  
**Impact:** The app's central promise—plan origin → destination—is practically unreachable to an ordinary rider. Previously claimed “journeys drawn on map” cannot be initiated via the inspected UI path.  
**Action:** Introduce a single planner feature contract from Compose → ViewModel, origin/destination selectors, swap action, leave-now/depart/arrive controls, **Plan trip** CTA, results list, selected itinerary state, and explicit **Show on map**. Preserve `StaticNetworkPlanner`; do not rebuild routing.  
**Acceptance:** Fresh install → download/load network → enter destination → plan → select result → see route on map; journey details include boarding stop, alighting stop, departure/arrival, transfer information, and schedule-vs-realtime labels. Instrumented Compose test exercises the actual UI.

### P0-03 — Unsafe offline package filename handling [CONFIRMED IMPLEMENTATION DEFECT; EXPOSURE NOT YET SHOWN]

**Evidence:** `core/atlas-offline/.../OfflineBasemapStore.kt`: `safeName(id) = id.replace(UNSAFE, "_")` with `UNSAFE = "[^A-Za-z0-9._-]"`. Kotlin `String.replace(String, String)` treats the pattern literally; it does **not** sanitize arbitrary characters or `../`. Manifest loading also trusts stored relative paths.  
**Impact:** A host that accepts untrusted package identifiers/files could write outside the intended storage directory; imported tile handling must not be exposed until fixed.  
**Action:** Strict whitelist (`Regex` or explicit validation), reject `.`/`..`, canonicalize and enforce containment, use generated opaque storage names, validate file type/header/size/checksum, stage and atomically rename; verify manifest paths against store root. Threat-model SAF imports and ZIP/path tricks.  
**Acceptance:** Traversal and separator payloads fail; benign names work; manifest tampering cannot escape store; no partial install becomes active. Security tests mandatory before enabling “Install basemap.”

### P1-01 — Conditional GTFS refresh is declared but not wired [CONFIRMED]

**Evidence:** `GtfsFeedAcquirer.acquire(feedId,url,currentEtag,currentLastModified)` supports validators; `StaticFeedRefresher.refreshBlocking()` calls `acquirer.acquire(feedId, sourceUrl)` **without** passing stored values. `StaticFeedSnapshotStore` metadata does not persist ETag or Last-Modified.  
**Impact:** Every refresh is an unconditional GET/download; `304` branch is effectively unreachable through ordinary app operation, wasting data and potentially harming service.  
**Action:** Persist validators with active snapshot; pass them to acquisition; correctly update/retain validators on `200/304`; enforce daily *check* policy separately from manual force refresh; test missing validators, changing ETag, and server ignoring conditions.  
**Acceptance:** Mock server sees `If-None-Match` and/or `If-Modified-Since` on second refresh; 304 does not reparse/replace; corrupted 200 retains prior snapshot.

### P1-02 — RainViewer tile URL and 2026 API policy mismatch [CONFIRMED FORMAT ERROR; RENDER FAILURE NEEDS LIVE TEST]

**Evidence:** `RainViewerRadarProvider.parseFrames()` generates `$host$path/{z}/{x}/{y}/{color}/{options}.png`; RainViewer documents `{path}/{size}/{z}/{x}/{y}/{color}/{options}.png` with concrete `size`, `color`, `options` values; MapLibre only substitutes supported tile coordinates. The adapter leaves non-tile variables unresolved and omits `size`.  
**Policy update:** RainViewer's **2026 transition** states nowcast discontinued January 1, 2026, maximum zoom 7, and a stated 100 requests/IP/min limit; free usage is personal/educational/small-community, not an unrestricted commercial launch license. Code/tests still prioritize a fabricated `nowcast` fixture and ADR-003 describes nowcast as available.  
**Impact:** Radar may be invisible or erroring; incorrect claims about available forecasts and permitted production usage.  
**Action:** Re-evaluate provider terms for intended distribution; construct fixed, valid URL tokens (including size/zoom/scheme), cap source zoom without breaking host zoom, remove unavailable nowcast assumptions; display clickable attribution when layer visible; implement **actual** separate IEM/NWS radar fallback (not NWS *alerts*); do not claim fallback exists merely because ADR lists it.  
**Acceptance:** Network-level tile tests and real-device overlay, max-zoom test, public-domain/terms decision captured in revised ADR; radar gracefully hidden with explanation when unavailable.

Primary sources: https://www.rainviewer.com/api/weather-maps-api.html , https://www.rainviewer.com/api/transition-faq.html , https://www.rainviewer.com/api.html .

### P1-03 — MapView lifecycle handling incomplete [CONFIRMED CALL-SITE GAP; DEVICE SYMPTOMS TO VERIFY]

**Evidence:** `MapCanvas.kt` constructs `MapView`, calls `onCreate(null)`, and calls only `onDestroy()` on disposal; no forwarding of `onStart`, `onResume`, `onPause`, `onStop`, `onLowMemory`, or saved-state restoration is visible.  
**Impact:** Possible GPU/native-resource leaks, black map after background/resume, rotation/state loss.  
**Action:** Wrap MapLibre lifecycle in a lifecycle-aware Compose owner (or view-managed lifecycle) with idempotent disposal, saved state, listener cleanup, app background/foreground tests.  
**Acceptance:** Repeated resume/background, rotation, memory pressure, and navigation away/back preserve usable map; no increased resource leakage.

### P1-04 — Camera gestures and scope resolution may overwrite each other [PROBABLE; STRONG CODE PATH]

**Evidence:** `MapLibreMapRenderer.moveCamera()` updates camera; `addOnCameraMoveListener` immediately calls `onViewportChanged`; `IntelliTripViewModel.onViewportChanged()` schedules `setScope("Map viewport", Focus.Radius(...))`. Conversely, `MapCanvas` listens to `state.scope` and calls `moveCamera()` again. No source-of-camera-change discriminator is visible.  
**Impact:** Selecting **System/Nearby/route**, recentering, or following GPS can be overwritten by a synthetic “user gesture”; jitter/snap-back and scope-label churn.  
**Action:** Distinguish gesture vs programmatic camera changes, render vs actual visible bounds, “fit bounds” vs geometric zoom approximation, and explicit follow vs browse state. Record camera-origin tokens or MapLibre move reasons; prevent feedback loops.  
**Acceptance:** Manual pan respects user gesture; programmatic scope selection stays selected; map does not bounce on tapping System/Nearby; stop following on intentional pan; behavior proven on device.

### P1-05 — Coarse-location permission accepted by UI but rejected by provider [CONFIRMED]

**Evidence:** `MainActivity.kt` considers either fine **or coarse** granted; `AndroidLocationProvider.hasPermission()` requires **fine only**, and `positions()` immediately closes if false.  
**Impact:** Rider granting “Approximate” location may see a permission-granted UI but never get a position.  
**Action:** Treat fine and coarse as distinct capability levels; use network provider when coarse-only, GPS when precise is permitted; handle temporary denial, permanent denial, disabled device location, and permission revoked while following.  
**Acceptance:** Fine-only, coarse-only, denied, disabled GPS, old last-known fixes, indoor network positioning verified through instrumentation/device matrix.

### P1-06 — Radar switch does not actually turn radar off; overlays may linger [CONFIRMED CONTROL GAP / PROBABLE RENDER STATE ERROR]

**Evidence:** `MapChrome.DataSheet` renders `SheetToggle("Radar", state.radarEnabled, onLoadRadar)`, not `onToggleRadar`; `loadRadar()` only attempts a fetch and sets enabled based on frame presence. `MapCanvas` calls `clearWeatherLayers()` only for a limited empty-layer condition; old radar can remain when state is disabled while alert-layer composition is nonempty.  
**Action:** Separate `setRadarEnabled(Boolean)` from `refreshRadarFrame()`, make overlay state declarative and idempotent, remove obsolete raster source on off/failure/style change, show loading/errors/attribution.  
**Acceptance:** Toggle on/off/on works consistently with and without weather alerts; no ghost overlays; controls reflect actual map state.

### P1-07 — NWS alert bounds query uses only southwest corner [CONFIRMED]

**Evidence:** `NwsWeatherAlertProvider.alertUrl(bounds)` produces `/alerts/active?point=${bounds.southWest.lat},${bounds.southWest.lon}`. This is not a bounding-box query; alerts for the remaining extent may be absent. The app currently calls `alerts()` without bounds, which risks retrieving irrelevant national alerts.  
**Action:** Decide on valid NWS point/zone/area strategy; if needing regional coverage, perform correct API-supported querying/aggregation and polygon intersections, dedupe by alert ID; never equate southwest-corner result with full bounds.  
**Acceptance:** Multi-county region fixtures and live Twin Cities results prove alerts for areas not containing SW corner are not silently lost; notices linked to actual impacted area/route.

### P1-08 — GTFS snapshot staging and metadata need integrity hardening [CONFIRMED CONCURRENCY/DATA-INTEGRITY RISKS]

**Evidence:** `StaticFeedSnapshotStore.newStopTimeSink()` writes a shared `root/staging-stop-times` directory for all feeds/refreshes; `StaticFeedRefresher` uses the same store and does not isolate simultaneous operations. The refresher initially copies prior feed `version`/validity onto a new candidate, rather than deriving fresh metadata before activation; `store.activate(...)` errors are not converted to `Rejected` in the same way parse errors are.  
**Action:** Use per-refresh/per-feed unique staging dirs and transaction objects; close sinks in `finally`/`use`; derive metadata from newly validated feed; preserve old snapshot on any IO failure; attach stop-time index atomically; impose snapshot quota/pruning and archive limits; persist validators. Validate resolved `FeedId` storage paths too.  
**Acceptance:** Two concurrent feeds + two overlapping refreshes do not cross-contaminate; failure injection at every write/move leaves last good snapshot valid; metadata corresponds to activated bytes.

### P1-09 — Remote OTP adapter not proven compatible and builds malformed assumptions [CONFIRMED UNVERIFIED INTEGRATION; PROBABLE REQUEST/MAPPING BUGS]

**Evidence:** `OpenTripPlannerRoutingProvider.planUrl` sends `date` as `epochSecond / 86400` and `time` as remainder seconds; stop identifiers can fall back to name or literal `unknown`; route key is made from display `shortName`/`longName` rather than feed `route_id`. The project itself says it has only hand-authored OTP response tests, not a real server test. Legacy `/otp/routers/default/plan` and query shapes may not match the OTP version eventually deployed. `AtlasHost.routingPlanner()` currently returns only `StaticNetworkPlanner` (hybrid not actually wired into the app).  
**Impact:** Cross-feed fallback, route association, service dates, and transfer provenance cannot be trusted for production.  
**Action:** Choose/pin OTP version and actual API contract (do not mix OTP 1 REST and OTP 2.x APIs); validate required GraphQL/REST schema and date/time/timezone format; map stable entity IDs, never display names or invented `unknown` keys; use captured real server fixtures, explicit optional remote config and privacy notice; test merge/fallback errors and fare claims.  
**Acceptance:** Live multi-leg route request to pinned deployed OTP version, captured regression fixtures, identical feed keys, correct departure timezone, explainable fallback. Leave remote disabled until proved.

### P1-10 — Rendering pipeline can draw inaccurate geometry or drop updates [CONFIRMED ALGORITHMIC RISK]

**Evidence:** `TransitRenderPipeline.render` “clips” shapes using `shape.points.filter { bounds.contains(it) }`; this can connect disjoint in-view vertices across an out-of-bounds segment, and discards segments crossing the viewport with no contained vertices. Its `simplify()` uses a decimation step and may emit more than the documented `MAX_SHAPE_POINTS`. `MapCanvas` uses `RenderUpdateThrottle.shouldEmit()` to skip an update but does not explicitly queue the latest skipped state for a later render.  
**Action:** Proper segment clipping (e.g., Cohen–Sutherland/Liang–Barsky or geometry-library equivalent), topology-preserving simplification at zoom, bounded GeoJSON payload; switch render throttle to latest-state coalescing instead of discard; test actual camera bounds.  
**Acceptance:** Edge-crossing and reentering polyline fixtures render correctly; final state after a burst always appears; full-system snapshot stays inside measured payload/frame budgets.

### P1-11 — Missing state/error model for concurrent network actions [CONFIRMED UX ARCHITECTURE GAP]

**Evidence:** `AppState.status` is one mutable string overwritten by static download, live polling, radar, alerts, location, and planning; functions can be started repeatedly, and screens use that shared message to infer feature status. `PlanSheet` and `DataSheet` have no independent loading, error, last-updated, or retry state.  
**Action:** Separate feature state machines (`StaticFeedUiState`, `RealtimeUiState`, `PlannerUiState`, `WeatherUiState`, `LocationUiState`), operations `Idle/Loading/Ready/Stale/Empty/Error`, source timestamp, progress when known, retry. Cancel obsolete requests; prevent duplicate work.  
**Acceptance:** Simultaneous radar + static GTFS operations display separate states and neither erases the other's error; no indefinite spinner; clean cancellation.

### P2-01 — Current UI is a developer control surface rather than a rider-first app [CONFIRMED SOURCE REVIEW]

**Evidence:** `MapChrome` exposes “Data”, “Static network”, “Scope”, “System”, “CLUSTER”, “vehicle budget”, and raw status strings; defaults for `Nearby`/`Area` are hardcoded to downtown Minneapolis even if a user browses elsewhere. `PlanSheet` has no journey entry, “tap a journey” interaction, or primary action. The current visual style mostly inherits uncustomized `MaterialTheme` tokens and a 420dp capped hand-built sheet.  
**Action:** Create a product information architecture and Compose Material 3 design system; promote “Nearby” and “Where to?”; tuck data-source/admin details under Advanced; replace imitation sheet with accessible adaptive Material 3 bottom sheets. See Sections 5–7.

### P2-02 — No explicit route/stop/vehicle inspection journey [GAP]

**Evidence:** MapLibre creates GeoJSON layers, but inspected `MapCanvas`/`MapChrome` do not expose tap selection callbacks for map features, route/stop details, departures, direction, or favorite actions.  
**Action:** Add map feature hit-testing and a **single selection model** with `SelectedStop`, `SelectedRoute`, `SelectedVehicle`, `SelectedJourney`; details in sheets backed by static/realtime contract data; accessible list equivalent.

### P2-03 — Offline basemap is storage-only, not an end-to-end user feature [DOCUMENTED OPEN ITEM]

**Evidence:** `OfflineBasemapStore` manages files; `AtlasHost`/`MapCanvas` apply CARTO online style. No wired importer/MapLibre tile-server path or installation test is demonstrated. Docs expressly say no package installed.  
**Action:** After P0-03, choose supported MBTiles/PMTiles renderer integration and license-compliant tile pipeline; SAF picker, install progress, package manager, offline rendering, download-size expectations, validation, deletion, and airplane-mode tests.

### P2-04 — Accessibility and release engineering are not finished [GAPS]

**Evidence:** UI has 44dp icon controls and nonstandard sheet/handle; many values only differ by color and technical labels; TalkBack semantics/order, large-font adaptation, reduced motion, gesture alternatives, high contrast and screen reader routing have no documented acceptance. `HUMAN_ACTIONS.md` explicitly says release signing, shrinking rules and Play assets are absent.  
**Action:** Make accessibility and release safety first-class gates, not polish at the end. Update Android dependencies in controlled batches only after CI is green and device baseline is captured.

### P2-05 — Docs/branch/configuration drift [CONFIRMED]

**Evidence:** Active work in `master`, default branch `main`; `docs/ARCHITECTURE.md` says placeholder; post-freeze Phase 10 is appended to Phase 09; documentation says radar fallback and offline package features without end-to-end implementation evidence.  
**Action:** Decide canonical default branch; fix README, developer quickstart, feature status matrix and truthful release checklist; make `docs/blueprints/PHASE-10...` through launch explicit after approval.

### P2-06 — SDK extraction exists but downstream integration is not accepted [GAP]

**Evidence:** `publishAtlasToLocalRepo` and `docs/SDK_INTEGRATION.md` exist, along with JVM sample host. No demonstrated Recovery-for-All integration, versioned consumer compatibility suite, SDK API stability guarantee, Android host smoke, or portable artifact manifest check.  
**Action:** Sample consumer in a *separate* temporary workspace, published Maven coordinates, locked dependency graph, real Android/Flutter platform-channel compile/smoke, non-breaking public API version policy. Do not modify Recovery for All under this plan without separate authorization.

### P3 future opportunities (not blockers)

Personalized favorite stops/routes, reliable boarding/alighting reminders, saved places, step-free routing where GTFS accessibility data supports it, high-confidence transfer warnings with explainable margins, multi-feed cross-agency federation, optional traffic, offline tile packs, notification opt-ins, live follow mode, and advanced scope controls. These should **follow** a working basic rider journey and honest freshness indicators.

---

## 4. Competitor benchmark — patterns worth adopting

**Method:** Reviewed official product/support documentation, not proprietary source code or screenshots. The table identifies **verified feature patterns** and the IntelliTrip design response—not a claim that every design detail exists in each app.

| Comparator | Relevant documented strengths | IntelliTrip decision | Avoid copying |
|---|---|---|---|
| **Transit** | Nearby lines/departure countdowns on opening screen; realtime vs scheduled marker; fast route details, pinned favorites; GO trip guidance | **Primary home UX reference:** live nearby departures with ETA and stop distance, calm map underneath; 1-tap route inspect; favorite-first ranking | Subscription gating, dependence on crowd tracking, excessive playful visual copy |
| **Citymapper** | Clear multimodal alternatives, GO step-by-step assistance, stop reminders, step-free journey filters | **Trip results reference:** compare duration, departure, transfers, walking and reliability; detailed leg timeline; later opt-in journey mode | Cramming every mobility mode into v1; claiming step-free without actual data |
| **Moovit** | Live directions, arrival information, alerts, favorites, TalkBack/VoiceOver support | **Accessibility and alerts reference:** readable directions, explicit alerts, full screen-reader equivalent to map | Fare wallet/account/payment layers that are out of scope |
| **OneBusAway** | Nearby stops, real-time departures, favorite stops, reminders, open-source regional coverage | **Operations reference:** fast favorite-stop entry points, nearby stop sheets, reminders when reliable data exists | Requiring a server for core local functions |
| **Metro Transit NexTrip/web** | Official regional timing, trip planner, saved NexTrip links | **Regional accuracy reference:** familiar route labels, trustworthy local source, link to source when ambiguity arises | Reproducing a mobile website inside a native app |

### Official references

- Transit basic flows / nearby: https://help.transitapp.com/article/93-how-to-use-transit and https://help.transitapp.com/article/93-how-to-use-transit
- Transit official arrival interface: https://help.transitapp.com/article/93-how-to-use-transit ; https://help.transitapp.com/article/103-save-your-favorite-transit-lines ; https://help.transitapp.com/article/95-save-your-favorite-locations
- Transit GO: https://help.transitapp.com/article/549-how-to-use-go
- Citymapper GO: https://citymapper.com/news/597/go-your-personal-trip-assistant
- Citymapper step-free: https://citymapper.com/news/2262/step-free-routing
- Moovit features: https://moovit.com/features/
- Moovit accessibility: https://moovit.com/features/accessibility/
- OneBusAway Android: https://github.com/OneBusAway/onebusaway-android
- Metro Transit official apps/resources: https://www.metrotransit.org/resources/apps/

**Differentiation:** IntelliTrip should be *beautiful but informative*: no forced account, no paid feature wall, explicit data provenance, honest stale-data badges, strong Twin Cities coverage, real weather context, local-first planning, and portable Atlas capabilities. “Visually stunning” means legibility, elegant motion and hierarchy—not more floating buttons.

---

## 5. Proposed product information architecture

### 5.1 Four primary destinations, one map

1. **Explore:** default map + nearby live/scheduled departure sheet; search destination, select stop/route, filters.
2. **Plan:** origin/destination + departure controls → itinerary results → journey details/map.
3. **Saved:** favorites, recent places, pinned lines, saved journeys and preferred stops; fully usable without login.
4. **More:** layers, weather, offline data, accessibility, preferences; Advanced/Developer tools nested here, not on the home surface.

On compact phones use Material 3 navigation bar *or* a carefully tested minimal 2–3 destination model; on tablets use navigation rail. The map stays edge-to-edge within its content pane. The exact number of primary destinations should be finalized with a clickable prototype and 5-user task test rather than chosen by ideology.

### 5.2 Default launch screen — visual anatomy

```text
┌──────────────────────────────────┐
│ IntelliTrip       ◉ Live  2m ago │   compact trust/status, not raw debug
│ [ Where to?                  🔍 ]│   single dominant entry action
│                                  │
│          LIVE TRANSIT MAP        │   route colors, soft basemap
│     [Stop]   [Vehicle]  [Stop]   │   legible markers and selections
│                                  │
│             [locate] [layers]    │   thumb-accessible floating actions
│ ╭──────────────────────────────╮ │
│ │ Nearby departures        ⌃ │ │   draggable accessible sheet
│ │ A  Blue Line  4 min  Live │ │
│ │ 21 Westbound  8 min  Sched│ │
│ │ [See all nearby]          │ │
│ ╰──────────────────────────────╯ │
│ Explore   Plan   Saved   More    │
└──────────────────────────────────┘
```

The wireframe is a behavioral direction, not final artwork. When GPS permission is denied, Explore must still work by search/manual map center. When static network is absent, replace empty results with “Download local transit network” and clear file-size/progress copy. **Never show fabricated ETAs**; missing predicted times get a “Schedule” tag.

### 5.3 Rider flows (must be real, not static mock screens)

**A. First launch:** app opens map without forced permission or sign-in → one obvious Explore/Plan action → cached network loads when present → explicit initial download consent or action when absent → friendly offline/error state.  
**B. Nearby stop:** pan or locate → nearby departures ordered by walking distance/time → select route/direction → stop detail with live vs scheduled → save stop.  
**C. Plan journey:** enter/search origin and destination; use My location only on tap; swap; depart/arrive controls → Plan → result cards → detailed itinerary → show route and boarding/alighting stops → choose alternative.  
**D. Live trip:** choose planned itinerary → read next action and connection buffer; *future* opt-in guidance and notifications once timing data verified.  
**E. Weather:** toggle precipitation layer; show last updated, radar provider attribution, opacity/time when available; alert banner only for relevant active alerts; one-tap dismissal that does not disable safety-critical system notifications.  
**F. Offline:** airplane mode → last validated network visible with age/source; scheduled options marked static; offline basemap if installed; otherwise explicit “Map tiles unavailable offline” message, never an unexplained blank map.

### 5.4 Essential screens and sheets

| Surface | Content | Primary action | Empty/error state |
|---|---|---|---|
| Explore | Map, nearby departures, freshness, search | Pick a stop or plan destination | “Find nearby service / choose an area” |
| Place search | Search text, recent, favorites, map pin | Select destination | “No match; choose point on map” |
| Plan builder | From/to, time, mobility prefs | Find routes | “Load schedule to plan” |
| Results | Time, transit badges, transfers, walk, reliability | View trip | “No route at this time; adjust options” |
| Trip detail | Timeline, boarding/alighting, step confidence | Show on map | Partial data / schedule-only |
| Stop detail | Direction-aware departures, alerts | Save stop | Timetable unavailable / retry |
| Route detail | Direction, shape, active vehicles, stops | Follow route | Feed missing / service ended |
| Vehicle detail | Route/trip, current location, last reported | Follow vehicle | Stale / position unknown |
| Weather | Overlay toggle, time, opacity, attribution | Show radar | Provider unavailable / radar license constraint |
| Offline data | Current feed age/version, packages, storage | Refresh/install | Network unavailable / validation failure |
| Settings | Appearance, units, accessibility, dev mode | Apply preferences | Clear safe defaults |

---

## 6. Visual direction and design system

### 6.1 Visual concept: **“Urban Signal”**

**Brand feeling:** precise, calm, metropolitan, reassuring, high-end navigation utility. Target a visually rich cartographic surface paired with quiet translucent control surfaces, meaningful color and confident typography. Avoid glass overlays that obscure text or reduce contrast.

| Token | Light suggestion | Dark suggestion | Intended use |
|---|---|---|---|
| Canvas | `#F4F7FA` | `#0D1522` | Sheet/system background |
| Surface | `#FFFFFF` | `#182434` | Cards and panels |
| Ink | `#122033` | `#F3F7FB` | Primary text |
| Secondary text | `#526478` | `#ABBED0` | Supporting data |
| Brand/navigation | `#006B93` | `#53D1EE` | Focus, CTA, location, selected map state |
| Success/live | `#087F65` | `#5EDCB5` | Live/fresh only, never schedule-only |
| Warning | `#A96B00` | `#FFC66D` | Stale, short transfer, partial data |
| Critical | `#B3261E` | `#FF8A80` | Canceled service and severe alerts |
| Dividers | `#DBE4ED` | `#2A3A4D` | Quiet separation |

These are **candidate tokens**; run contrast calculations and device testing before freezing. Transit route colors remain agency-authored when available, always backed by line number/text/shape so color is never the sole identifier. Offer dynamic color as an optional theme if it does not degrade transit/status semantics.

**Typography:** Material 3 type hierarchy; large ETA and arrival time as the dominant numerical information, secondary route/direction, tertiary service provenance. Use Android system/default font or a redistributable licensed typeface—never bundle unverified font assets. Set a finite type scale with 200% font-size tests.

**Spacing/shape:** 4dp base spacing, 8/12/16/24/32 spacing rhythm, 12–20dp container radii, consistently round action chips, minimum **48×48dp interactive targets**. Respect status/nav bars, display cutouts, gesture inset and small phones.

**Motion:** 180–300ms transitions for sheets and selection; avoid animating the entire map on every state update; no animated flashing for alerts; honor reduced-motion/accessibility preferences; throttle source updates without dropping the last state.

**Map:** CARTO/MapLibre online initially, visually quieter POIs, route-colored lines with halo, bus/train symbol distinction, persistent “selected” emphasis, useful density clustering, underlay radar capped to validated API zoom, strong selected-route prominence. User dot with accuracy halo and visible age. Map must still work with no GPS permission.

### 6.2 Accessible UX is part of the design, not a later checkbox

- TalkBack announces “Route A Blue Line, Downtown direction, 4 minutes, live” rather than only “marker.”
- Every map-only interaction has list/search alternative; keyboard/D-pad focus order and rotor semantics tested.
- Dynamic type, high contrast, color-independent state labels, minimum 48dp hit targets, landscape/tablet/compact support.
- Bottom sheets expose semantics, dismiss actions, proper scrim, focus restoration, drag gesture, and state preservation. Do not fake drag handles with a 4dp clickable line.
- ETA updates should not cause screen-reader announcement storms; urgent safety alerts distinct from routine refreshed numbers.
- Make step-free **only if the transit feed/footpath data actually supports it**; otherwise label preference “best effort / insufficient accessibility data.”

### 6.3 What to remove from the consumer home

Move `Static network`, `CLUSTER`, `vehicle budget`, `feed download`, `System`, and raw parser errors into a developer/diagnostics or Data & Offline screen. Replace with language riders understand: “Nearby,” “All transit,” “Live departures,” “Download schedules,” “Updated 2 min ago,” “Some arrivals are scheduled.” This does not remove advanced capability—only separates consumer tasks from engine administration.

---

## 7. Execution roadmap — dependency-ordered, gated, no broad rewrite

**Work protocol:** Use short, reviewable branches and atomic commits. At every gate: source diff + deterministic tests + Android device evidence + CI result + rollback plan. Maintain the existing `master` until branch-policy changes are deliberate. No force pushes, no touching protected local `core/atlas-transit` changes, and no work across phase gates without acceptance.

### Phase R0 — Baseline and CI recovery (P0)

**Depends:** none.  
**Work:** capture complete `master` SHA/tree; preserve any local dirty work; fix `gradlew` executable mode; rerun both CI jobs; add issue backlog and baseline screenshots/video on current device; record test counts, AGP/Kotlin versions, signed-vs-debug state; measure cold start and map load; choose active default branch (do not silently rewrite history).  
**Files:** `.github/workflows/ci.yml`, `gradlew` file mode, `README.md`, `AGENTS.md`, new `docs/audits`/`docs/blueprints` as approved.  
**Gate R0:** clean CI green, benchmark/baseline evidence captured, protected prototype preserved, changes small and reversible.

### Phase R1 — Security, data truth and map correctness (P0/P1)

**Depends:** R0.  
**Work:** fix offline filename/path containment before exposing imports; persist conditional GTFS validators; isolate snapshot staging + atomic activation + metadata; fix coarse-only GPS behavior; lifecycle-aware MapLibre, camera-source distinction and cancel/final-render handling; NWS region logic; test radar renderer URL/terms and disable unverified nowcast.  
**Tests:** traversal/security; conditional headers; multi-feed concurrent activation; activity background/rotation; user gesture vs programmatic move; approximate permission; weather failure and zoom.  
**Gate R1:** no known P0 bugs remain; no corrupt snapshot replacement; no missing result after rapid updates; expected device transitions verified.

### Phase R2 — Rider navigation skeleton and planner unlock (P0/P2)

**Depends:** R1 stable app.  
**Work:** formal state model for Explore/Plan/Saved/More, journey feature wiring, origin/destination entry and selection, editable time, Plan CTA, journey results + details, route highlight and selected journey, explicit schedule data labels; no speculative routing rewrite. Put legacy Data/Scope tooling under More/Advanced.  
**Tests:** Compose UI end-to-end on clean install; empty/no feed; planning success/no-result; state restoration; TalkBack flow; permission-free search.  
**Gate R2:** a first-time rider can plan a real route without developer tooling in ≤6 meaningful user actions after the network is available; selected result appears on map and detail sheet.

### Phase R3 — Design system + premium-looking Explore screen (P2)

**Depends:** R2 navigation semantics; can prototype visuals in parallel without merging until R2.  
**Work:** `ui/theme/` color/typography/spacing/motion tokens; reusable chips, itinerary cards, departure rows, badges, empty/error panels; accessible Material 3 sheets, haptics where appropriate; user-facing default Explore layout; light/dark, dynamic-type and small-screen verification. Expose `Nearby departures` from existing static/realtime data without invented countdowns.  
**Gate R3:** visual QA across 360dp phone, compact/landscape, tablet, dark/light, 200% fonts; no horizontal clipping; AA text contrast; map remains usable when sheet expanded.

### Phase R4 — Stops, vehicles, real-time trust and personalization (P2)

**Depends:** R2/R3.  
**Work:** MapLibre feature-hit testing, selection detail sheets, route direction and stop lists, real-time vs scheduled ETA provenance, stale/canceled/unknown badges, saved stops/routes/recent places (local storage), opt-in auto-refresh with lifecycle-aware polling, time-to-stop estimates only when data supports them. Remove status-string coupling.  
**Gate R4:** route→stop→departure flow, favorite persistence across restart, stale feed warning, correct direction, no duplicate subscriptions after screen resume.

### Phase R5 — Weather, overlays, advanced scope and offline experience (P1/P2)

**Depends:** R1 provider fixes; R3 UI components.  
**Work:** RainViewer/legal decision and validated radar tiles or IEM/NWS adapter; date/source/attribution on map; alert relevance; layer legend/opacity; true radar on/off; offline feed age; safe tile package import with supported renderer; airplane-mode visual fallback; controlled advanced scope UI for power users.  
**Gate R5:** real-device radar visual, attribution visible, no unsupported nowcast promises, package install/remove/airplane mode proven, graceful no-network behavior.

### Phase R6 — Routing quality, transfers and SDK operational proof (P1/P2)

**Depends:** R2, R4; optionally R5.  
**Work:** deterministic Twin Cities golden itineraries (day/night, weekend, >24h GTFS, multi-leg, canceled/changed trips); explainable transfer confidence; route geometry correctness; real OTP deployment pinned to explicit API version or leave remote disabled and report it; published SDK consumed by an out-of-tree Android sample; external Flutter integration only with separate repo approval.  
**Gate R6:** validated local journey quality and latency, no fabricated IDs/ETAs, remote integration either real tested or unambiguously disabled, published SDK contract tests pass.

### Phase R7 — Beta quality and launch readiness (P1/P2)

**Depends:** all above.  
**Work:** app icons/splash, privacy policy/data safety, provider attribution/licensing, release signing and secure key custody, R8/ProGuard rules, versioning, crash/error redaction, Play target/API checks, offline/device matrix, rollout plan, beta feedback, performance regression suite, fix highest-frequency observed usability failures.  
**Gate R7:** zero open P0/P1 issues, CI green, no known data-integrity holes, accessibility and performance acceptance, clean release candidate smoke, store submission checklist complete. **Do not call beta or release “ready” solely from compile success.**

### Optional Phase R8 — Differentiating next-gen features (P3; separately authorize)

Connection confidence with observed delay distributions and walking buffer, rider guidance/notifications, weather-conditioned route recommendations, interagency GTFS federation, advanced live scope, optional traffic and SDK adapters for Recovery for All. These follow stability and user research.

---

## 8. Implementation tickets — suggested first backlog (ready to issue)

| Order | Ticket | Size | Predecessor | Required proof |
|---:|---|---|---|---|
| 1 | `CI-001` restore executable Gradle wrapper | XS | None | Two green GH Actions jobs |
| 2 | `SAFE-001` harden OfflineBasemapStore paths and ID validation | S | CI-001 | traversal tests |
| 3 | `DATA-001` persist/send GTFS ETag/Last-Modified | S | CI-001 | Mock 200→304→200 cycle |
| 4 | `DATA-002` isolate snapshot staging and metadata | M | DATA-001 | concurrency + power-failure tests |
| 5 | `MAP-001` complete MapView lifecycle | S | CI-001 | device resume/rotate suite |
| 6 | `MAP-002` break camera/scope feedback loop | M | MAP-001 | real gesture/scope tests |
| 7 | `LOC-001` support approximate/coarse location | S | CI-001 | Android permission matrix |
| 8 | `WEATHER-001` repair radar URL and license/API compatibility | M | CI-001 | URL contract + visual smoke |
| 9 | `WEATHER-002` correct NWS spatial matching | M | WEATHER-001 optional | fixture + regional live check |
| 10 | `STATE-001` per-feature UI state machines | M | CI-001 | concurrent operations tests |
| 11 | `TRIP-001` wire Plan action and itinerary selection | M | STATE-001 | Compose UI trip test |
| 12 | `TRIP-002` editable from/to + time selection | M | TRIP-001 | 1st-run planning test |
| 13 | `DESIGN-001` Urban Signal tokens + accessible components | M | STATE-001 | screenshot/contrast matrix |
| 14 | `HOME-001` Explore + nearby departures | L | DESIGN-001, DATA-002 | nearby schedule accuracy |
| 15 | `DETAIL-001` stop/route/vehicle sheets | L | HOME-001 | map-hit-test and list fallback |
| 16 | `FAV-001` on-device favorites/recents | M | DETAIL-001 | restart persistence tests |
| 17 | `MAP-003` safe geometry clipping and coalesced renders | M | MAP-002 | golden geometry/frame bench |
| 18 | `OFF-001` install/render offline basemap end-to-end | L | SAFE-001, DESIGN-001 | airplane-mode device proof |
| 19 | `OTP-001` pin/test real OTP API, no fabricated identifiers | L | TRIP-002 | live integration suite |
| 20 | `SDK-001` external consumer acceptance + versioning | M | CI-001, stabilized facade | independent host build |
| 21 | `REL-001` signing, privacy, licensing and store readiness | L | R6 | release checklist |

Sizes: XS ≈ focused patch; S ≈ single component; M ≈ multi-component; L ≈ cohesive feature. **Not calendar estimates.** Each ticket receives its own `Definition of Done`, tests and rollback guidance before execution.

---

## 9. Testing and quality plan

### 9.1 Build/CI gates

```powershell
# Windows local checkout
.\gradlew.bat test
.\gradlew.bat lint
.\gradlew.bat assembleDebug
.\gradlew.bat :sample:host:run
.\gradlew.bat :app:connectedDebugAndroidTest  # device/emulator
```

```bash
# Linux CI after wrapper executable bit fixed
./gradlew test lint assembleDebug :sample:host:run --console=plain
./gradlew :app:connectedDebugAndroidTest --console=plain
```

Track per-module test counts; do not equate “task up-to-date” with an exercised device test. Add a job that validates publishing the SDK to a temporary local Maven repository and consuming it from a separate sample; avoid publishing to Maven Central unintentionally.

### 9.2 Device/UX acceptance matrix

- **Devices:** representative older Android 8–10 device/minSdk; Android 14–16 physical or emulator; narrow 360dp/low RAM; large-font 200%; tablet landscape.
- **Lifecycle:** cold/warm launch, background/resume x20, rotate x10, force-stop/restart, GPS denial/regrant, network toggle, process recreation.
- **Transit:** clean static download, 304 refresh, broken ZIP, stale GTFS, service date midnight, trip >24h, feed lacking optional files, valid no-result itinerary, multiple results, route selection.
- **Realtime:** fresh/stale/missing updates, interrupted fetch, rate limit, multiple vehicles clustered/singleton, service alerts.
- **Weather:** radar on/off in presence of alerts, tile URLs actually fetch, zoom beyond source 7, attribution, expiry, disabled provider, no weather data.
- **Offline:** airplane mode (network unavailable, locally stored network, install/remove tile pack), no unbounded memory, no misleading “Live.”
- **Accessibility:** TalkBack linear walkthrough, minimum hit targets, text scaling, contrast, keyboard focus and accessible equivalent to map gestures.
- **Performance:** cold start, time-to-first-map, time-to-departures, route plan p50/p95, source GeoJSON size, frame jank/ANR, peak memory during GTFS import, battery/polling frequency. **Set numeric budgets from a measured baseline before declaring pass.**

### 9.3 Acceptance metrics (proposed product targets, to calibrate)

| Metric | Initial target |
|---|---|
| First-time user can initiate a plan | ≤ 3 taps from Explore to edit destination |
| Trip planned once static feed is local | ≤ 6 deliberate steps including destination choice |
| Clearly distinguish live vs scheduled | 100% of visible departure cards |
| Empty/error state | 100% of screens have helpful recovery action |
| Critical map interaction | No bounce/feedback loop across 20 scope changes |
| Touch targets | ≥48dp for all interactive map controls and rows |
| Typography scaling | Core tasks functional at 200% |
| CI | All required checks pass on every change |
| External provider terms | Documented and approved before public launch |
| Unknown location/canceled service | Never displayed as confirmed position or valid departure |
| P0/P1 issues at beta gate | Zero |

---

## 10. Dependencies, risks and guardrails

### Protected work and repository rules

The earlier local working tree contained three protected prototype paths; remote GitHub cannot determine whether that uncommitted state still exists on the user's computer. **Check locally before any write**:

```text
core/atlas-transit/build.gradle.kts
core/atlas-transit/src/main/java/com/intellitrip/transit/GtfsRtClient.kt
core/atlas-transit/src/main/java/com/intellitrip/transit/GtfsStatic.kt
```

Never reset/clean/stash or stage them by accident. The source tree does not prove their local contents are gone. Use explicit Git paths and never force-push. Prefer feature branches/PRs after resolving default-branch policy; maintain portability tests for reusable Atlas core.

### Open commercial/provider decisions

- **RainViewer:** 2026 API limitations and free-tier scope are explicitly documented. Keyless is not identical to commercially licensed. Obtain confirmation for intended distribution or use IEM/NWS-supported alternative. Preserve attribution and source provenance.
- **CARTO basemap:** confirm tile/style usage terms and attribution at projected production volumes; offline caching rights cannot be assumed merely from public URL accessibility.
- **OTP/Oracle Cloud:** no deployed server or proven live schema. “Always Free” depends on provider limits, availability, eligibility and policy changes; no SLA or cost guarantee. Do not represent cron traffic as guaranteed protection against reclaim.
- **NWS:** User-Agent identity and usage requirements; regional filtering and caching.
- **Metro Transit GTFS/GTFS-RT:** current data coverage, agency/feed mapping, endpoint freshness, source outages and terms must be checked before production use.
- **OSM/offline tiles:** appropriate attribution and share-alike/ODbL compliance where applicable; a usable renderer/loader, not just an archive with a manifest.

### Architecture rules retained

- Kotlin/JVM provider-neutral domain/contracts; Android platform-only adapters.
- Native Android/Compose app, MapLibre map renderer, no heavy view per vehicle.
- Camera/data/render scopes separate; continuous scope supported without arbitrary hard radius cap.
- Local-first planning; remote is opt-in/configured, with privacy implications visible.
- Stale, scheduled, unknown, canceled and partial are separate states; no fabricated confidence.
- No mandatory login, billing, ads or network permission requests beyond need.
- Recovery for All and Sovereign Atlas are downstream consumers; **no repo edits in those projects without separate authorization**.

---

## 11. Proposed blueprint/decision files for next execution cycle

Do **not** immediately dump a giant redesign onto current code. After accepting this audit, create the following documentation artifacts in a *docs-only planning commit* (or an unzip-friendly local overlay if that is preferred):

```text
docs/
  audits/
    AUDIT-2026-10-11-repository-uiux.md
  product/
    UX_PRINCIPLES.md
    RIDER_JOURNEYS.md
    SCREEN_INVENTORY.md
    ACCESSIBILITY_MATRIX.md
    DESIGN_SYSTEM-urban-signal.md
    COMPETITOR_BENCHMARK.md
  blueprints/
    PHASE-R0-ci-baseline.md
    PHASE-R1-hardening.md
    PHASE-R2-trip-planner-ux.md
    PHASE-R3-visual-design-system.md
    PHASE-R4-nearby-and-details.md
    PHASE-R5-weather-offline.md
    PHASE-R6-routing-sdk-validation.md
    PHASE-R7-release-beta.md
  decisions/
    OPEN-branch-default-and-pr-policy.md
    OPEN-radar-license-and-fallback.md
    OPEN-otp-api-version-and-deployment.md
    OPEN-offline-tile-renderer.md
```

Each blueprint should include: Objective; frozen prerequisites; exact owned paths/modules; allowed/prohibited changes; tasks in deterministic order; fixtures; acceptance scenarios; verification commands; failure recovery; commit policy; evidence report; external review/freeze gate. Maintain `AGENTS.md` instructions for nested blueprint agents. Existing Phase 00–09 documents remain historical authority unless an accepted ADR explicitly supersedes an element.

### Proposed release stages

- **Engineering baseline:** CI green, existing engine preserved, known P0s eliminated.
- **Functional alpha:** complete trip creation/result/selection and nearby departures, meaningful error states.
- **Design beta:** polished Explore/Plan/Saved/More, accessibility, real-device screenshots, route/stop details.
- **Transit reliability beta:** consistent live provenance, correct location/alerts, radar policy verified, offline user flows.
- **Release candidate:** privacy/licensing, signing, store assets, no P0/P1 blockers, reproducible CI and device suite.

**Do not declare product complete merely because all historical architecture phases say “closed.”**

---

## 12. First implementation order — exact next handoff

**Authorize R0 only, then re-audit its evidence.**

1. Inspect current branch/head and protected uncommitted paths locally; refuse to overwrite unknown work.
2. Commit Gradle wrapper executable mode; if a workflow fallback is needed, use an explicit shell invocation, not a global bypass.
3. Run the whole GitHub CI suite and fix newly revealed *real* test/lint/assemble errors separately; do not suppress checks.
4. Capture device screenshots/video of current Explore, Scope, Data, Plan, permissions, weather, online/offline and rotation; note view hierarchy/interaction traces.
5. Open a backlog for P0/P1 findings with source links and assigned verification method.
6. Approve R1 safety/data/lifecycle fixes; in parallel approve UX wireframes, **not** a simultaneous giant code rewrite.
7. After P0/P1 stabilization, unlock R2 planner interaction, then visual system R3, nearby/detail UX R4, and the remaining integration phases.

**Stop condition:** Any newly discovered crash, source corruption, externally exposed traversal, provider terms violation, or loss of protected work pauses feature work until triaged. Every acceptance report must separate **source-inspected**, **unit-tested**, **instrumented**, **live-provider-tested**, and **human-visual-reviewed** evidence.

---

## 13. Evidence index — audited source paths

All links below reference the exact audited commit rather than a moving branch. Replace `50d853...` in future audits only after recording a new SHA.

| Evidence | Source |
|---|---|
| CI execution | [Workflow](https://github.com/GhostMan612/Metro-IntelliTrip/blob/50d85321b228325dbf5c91d48e696600c137d270/.github/workflows/ci.yml) · [Failed run](https://github.com/GhostMan612/Metro-IntelliTrip/actions/runs/38142122223) |
| Planner callable not wired into UI | [MainActivity](https://github.com/GhostMan612/Metro-IntelliTrip/blob/50d85321b228325dbf5c91d48e696600c137d270/app/src/main/java/com/intellitrip/MainActivity.kt) · [MapChrome](https://github.com/GhostMan612/Metro-IntelliTrip/blob/50d85321b228325dbf5c91d48e696600c137d270/app/src/main/java/com/intellitrip/ui/MapChrome.kt) · [ViewModel](https://github.com/GhostMan612/Metro-IntelliTrip/blob/50d85321b228325dbf5c91d48e696600c137d270/app/src/main/java/com/intellitrip/IntelliTripViewModel.kt) |
| Offline package safety | [OfflineBasemapStore](https://github.com/GhostMan612/Metro-IntelliTrip/blob/50d85321b228325dbf5c91d48e696600c137d270/core/atlas-offline/src/main/kotlin/com/intellitrip/offline/OfflineBasemapStore.kt) |
| Conditional refresh | [GtfsFeedAcquirer](https://github.com/GhostMan612/Metro-IntelliTrip/blob/50d85321b228325dbf5c91d48e696600c137d270/core/atlas-gtfs-static/src/main/kotlin/com/intellitrip/gtfs/GtfsFeedAcquirer.kt) · [StaticFeedRefresher](https://github.com/GhostMan612/Metro-IntelliTrip/blob/50d85321b228325dbf5c91d48e696600c137d270/core/atlas-gtfs-static/src/main/kotlin/com/intellitrip/gtfs/StaticFeedRefresher.kt) · [SnapshotStore](https://github.com/GhostMan612/Metro-IntelliTrip/blob/50d85321b228325dbf5c91d48e696600c137d270/core/atlas-gtfs-static/src/main/kotlin/com/intellitrip/gtfs/StaticFeedSnapshotStore.kt) |
| Weather/radar | [RainViewerRadarProvider](https://github.com/GhostMan612/Metro-IntelliTrip/blob/50d85321b228325dbf5c91d48e696600c137d270/core/atlas-weather/src/main/kotlin/com/intellitrip/weather/RainViewerRadarProvider.kt) · [NwsWeatherAlertProvider](https://github.com/GhostMan612/Metro-IntelliTrip/blob/50d85321b228325dbf5c91d48e696600c137d270/core/atlas-weather/src/main/kotlin/com/intellitrip/weather/NwsWeatherAlertProvider.kt) · [Radar unit tests](https://github.com/GhostMan612/Metro-IntelliTrip/blob/50d85321b228325dbf5c91d48e696600c137d270/core/atlas-weather/src/test/kotlin/com/intellitrip/weather/WeatherProvidersTest.kt) |
| Map lifecycle/render | [MapCanvas](https://github.com/GhostMan612/Metro-IntelliTrip/blob/50d85321b228325dbf5c91d48e696600c137d270/app/src/main/java/com/intellitrip/ui/MapCanvas.kt) · [MapLibre adapter](https://github.com/GhostMan612/Metro-IntelliTrip/blob/50d85321b228325dbf5c91d48e696600c137d270/core/atlas-map-android/src/main/java/com/intellitrip/map/android/MapLibreMapRenderer.kt) · [Render pipeline](https://github.com/GhostMan612/Metro-IntelliTrip/blob/50d85321b228325dbf5c91d48e696600c137d270/core/atlas-map/src/main/kotlin/com/intellitrip/map/TransitRenderPipeline.kt) |
| Location/Scope | [AndroidLocationProvider](https://github.com/GhostMan612/Metro-IntelliTrip/blob/50d85321b228325dbf5c91d48e696600c137d270/app/src/main/java/com/intellitrip/location/AndroidLocationProvider.kt) · [Scope resolver](https://github.com/GhostMan612/Metro-IntelliTrip/blob/50d85321b228325dbf5c91d48e696600c137d270/core/atlas-scope/src/main/kotlin/com/intellitrip/scope/DefaultScopeResolver.kt) |
| OTP | [OpenTripPlanner adapter](https://github.com/GhostMan612/Metro-IntelliTrip/blob/50d85321b228325dbf5c91d48e696600c137d270/core/atlas-routing/src/main/kotlin/com/intellitrip/routing/OpenTripPlannerRoutingProvider.kt) · [Deployment guide](https://github.com/GhostMan612/Metro-IntelliTrip/blob/50d85321b228325dbf5c91d48e696600c137d270/docs/OTP_DEPLOYMENT.md) |
| Phase/readiness assertions | [AGENTS.md](https://github.com/GhostMan612/Metro-IntelliTrip/blob/50d85321b228325dbf5c91d48e696600c137d270/AGENTS.md) · [Phase-09 and post-freeze appendix](https://github.com/GhostMan612/Metro-IntelliTrip/blob/50d85321b228325dbf5c91d48e696600c137d270/docs/blueprints/PHASE-09-sdk-extraction.md) · [Human actions](https://github.com/GhostMan612/Metro-IntelliTrip/blob/50d85321b228325dbf5c91d48e696600c137d270/HUMAN_ACTIONS.md) |

### External sources and date-sensitive checks

1. RainViewer API terms, checked 2026-10-11: https://www.rainviewer.com/api.html
2. RainViewer weather tile syntax: https://www.rainviewer.com/api/weather-maps-api.html
3. RainViewer 2026 transition (nowcast, zoom and rates): https://www.rainviewer.com/api/transition-faq.html
4. Transit official rider features: https://help.transitapp.com/article/93-how-to-use-transit
5. Transit official GO navigation: https://help.transitapp.com/article/549-how-to-use-go
6. Citymapper GO: https://citymapper.com/news/597/go-your-personal-trip-assistant
7. Citymapper step-free: https://citymapper.com/news/2262/step-free-routing
8. Moovit product/accessibility: https://moovit.com/features/ and https://moovit.com/features/accessibility/
9. OneBusAway Android repo: https://github.com/OneBusAway/onebusaway-android
10. Metro Transit official developer/app resources: https://www.metrotransit.org/resources/apps/

---

## 14. Audit limitations and next verification requirements

This audit is intentionally candid about its boundaries. The connected GitHub repository can prove committed code and workflow logs, but it cannot show the user's protected **uncommitted local work**, current installed APK, live on-device UI appearance or private secrets/configuration. No local Gradle execution occurred in this audit. CI currently fails before executing its Gradle tests, so this report does not claim that the current revision compiles or fails to compile. The visual design critique is grounded in checked-in Compose layout/source, not in rendered screenshots. Competitor choices are grounded in public documented workflows, not reverse-engineered proprietary styles.

**Before implementing beyond R0, obtain** device screenshots or a brief screen recording; current `git status` from the actual local checkout; latest CI evidence after permission repair; optional production feed/OTP smoke artifacts; and confirmation of the intended radar distribution/licensing model. Record these as evidence, not assumptions.

---

## 15. Final recommendation

**Preserve the Atlas engine. Repair the broken quality pipeline and rider flows. Then build a cohesive, accessible premium UI around the strongest existing capabilities.**

The shortest path to an app that genuinely rivals leading transit products is not another sprawling architecture project. It is a disciplined sequence: **CI → data/security and map correctness → trip-planning UI → polished nearby/route UX → trustworthy realtime/weather/offline → operational release verification.**

**Next authorized action recommended:** planning acceptance, then **Phase R0 only**. This document is an audit and proposed plan, **not permission to modify the remote repository**.
