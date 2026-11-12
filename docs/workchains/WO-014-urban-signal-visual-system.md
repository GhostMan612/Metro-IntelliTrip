# WO-014 — Urban Signal visual system and intuitive rider UX

**Priority:** P1, *after* ride-data trust gates. **Status:** SPECCED; design not implemented by this PR.  
**Baseline:** `ccf662c7de6e9b0b34e20994366f5ecfec5d5d40`, `master`.  
**Sources:** current `app/src/main/java/com/intellitrip/ui/theme/Theme.kt`, `MapChrome.kt`, `ExploreScreen.kt`, `DepartureBoard.kt`, `MapFeatureSheet.kt`, `RiderNavigation.kt`, and `docs/blueprints/VISUAL-00/01/02/03`.

## Product objective
Deliver a polished Twin Cities transit companion that immediately answers:
**Where am I? What departs near me? How do I get there? What is verified live versus scheduled? What should I do next?**

Use the *information architecture*, not the artwork or proprietary component shapes, of comparable applications: Transit for prominent departures, Citymapper for itinerary clarity, Moovit for accessible guidance, OneBusAway for predictable favorites, and modern mapping apps for map-first gesture behaviors.

## Success criteria
- A first-time rider can reach **nearby departures in ≤2 deliberate taps**, and start a journey plan with a single dominant Where to? control. A completed route must be explorable without leaving the map.
- Important signals are visually differentiated: scheduled vs live prediction vs stale vs canceled; alert severity vs generic weather; online vs offline cached. Text always accompanies color/icons.
- No dummy tabs, no spinners without completion/failure, no fake geocoder, no modal dead ends, no accidental zoom/tilt surprises, no fabricated walk routes.
- Fluid on BLU-class low-memory hardware, dark/light system modes, 200% font scaling, TalkBack, reduced-motion settings and high-contrast maps.
- UI defaults to the **current area**, not a hard-coded downtown Minneapolis destination once a user has panned or granted location.

## Information architecture / screens

### Explore (primary, map first)
- Full-screen MapLibre map with lightweight translucent overlays, route/trip geometry, readable stop/vehicle selection and attribution in lower-right. Avoid thick borders and a stack of competing floating controls.
- Top: one fixed searchable destination entry (Where to?) over map, opening an honest preset/stop chooser until genuine address search is available.
- Bottom peek: next actual nearby departures, compact ETA/route/direction/provenance. Drag opens full departure board. A no-data case offers Download schedules/Use map area.
- Corner controls: a refined compass/location rose, separate layers control and scope chip; double tap compass resets bearing; two-finger vertical drag changes tilt while pinch retains zoom.
- Keep map gestures uninterrupted under peek sheets; bottom navigator gets safe-area insets.

### Plan
- Full-height or draggable sheet: From/To (swap), Depart at/Arrive by, walking allowance, wheelchair preference if supported by provider, Plan.
- Results show departure, arrival, total time, transfer count, walking distance, operator/route colors, stop names and provenance. Expanded timeline has actionable boarding/alighting. Selecting one draws *matching* map geometry.
- On no route: concise reason plus useful next action; no generic "0 journeys" or precision pretending a failed query was a real search.
- Route details visually distinguish solid transit strokes, dashed walk connectors, transfer stop dots and directions where validated.

### Saved
- Large readable stop favorites and local availability; add/remove works offline, navigation returns to map; no forced account.
- Show stale feed badge, not false "live"; every removal accessible and undoable if practical.

### More / Layers / Data
- Give understandable names and status ("Schedule updated 3h ago", "Radar unavailable", "Map needs internet") rather than engine jargon or debug budgets.
- Advanced diagnostic data (scope budget, provider internals) moved to explicit developer details, not primary rider chrome.
- No controls for offline package installation, live OTP or routed walking until their actual capability gates are met.

## Design system contract
- **Color:** neutral warm gray/charcoal surfaces, high-contrast typography, restrained transit blue for primary action; route colors sourced from GTFS but paired with a route badge. Green only for verified fresh live predictions; amber for schedule/stale, red only for meaningful disruption. No color alone communicates status.
- **Type:** one accessible display hierarchy: destination/title ~22–26sp; ETA hero ~30–40sp; route headings ~16–18sp; details 13–16sp; all responsive to fontScale, avoid ellipsis hiding stop names at 200%.
- **Spacing:** 4dp base scale: 4/8/12/16/24dp. Interactive targets ≥48dp; cards radii ~16dp; chips ~12dp; bottom sheets top radius ~24dp; consistent screen-edge alignment and map safe-area insets.
- **Elevation:** translucent cards with elevation and sufficient contrast, minimal dividers/borders, no noisy gradients behind important text; verify light and dark contrast (WCAG AA equivalent 4.5:1 for body and 3:1 large).
- **Motion:** one tokenized system: tap 150–180ms, sheet 240–300ms, map 450–650ms; respect reduced-motion (instant when disabled). Never animate stale vehicles as if live.
- **Iconography:** consistent Material/Lucide-like vector language; accurate bus/train/walk/transfer icons. No copyrighted third-party app screenshot/design assets in production.
- **Map:** follow user's chosen tile style, visible route at different zoom, attribution, contrast-balanced stop/vehicle glyphs, no basemap brightness that masks warning text.

## IMPLEMENTATION PLAN (separate follow-up implementation PR)
1. Freeze UX states (Explore, Plan, Saved, More, Offline, Alert); map screen semantics and transitions; document no-state-loss invariants.
2. Refactor Compose components into independently previewable sections; theme tokens become single authority (remove per-screen random paddings, shadow values and bespoke colors).
3. Implement Explore map chrome/peek departure board, then planner sheet/results and map route selection, then Saved/More.
4. Integrate accessibility semantics and focus order while building components rather than after. Every clickable element needs an intentional behavior.
5. Add motion and map controls only after functional semantics; use no visual-only fake states.
6. Complete entire phase's code implementation before testing per project policy; run one consolidated CI + physical-device gate and capture screenshots for all states.

## Files and boundaries
- `app/src/main/java/com/intellitrip/ui/theme/Theme.kt` and new palette/spacing/motion tokens
- `app/src/main/java/com/intellitrip/ui/ExploreScreen.kt`, `MapChrome.kt`, `MapFeatureSheet.kt`, `RiderNavigation.kt`, `SecondaryScreens.kt`, `DepartureBoard.kt`
- `app/src/main/java/com/intellitrip/MainActivity.kt`, `ui/MapCanvas.kt`, MapLibre Android adapter only for genuine gesture/style behavior
- New accessibility screenshot tests; preserve `core/atlas-domain`, routing and GTFS parser logic.

## Acceptance / visual gates
- Screenshots with annotated visual states: light/dark Explore nearby, expanded departures, plan input, 1- and 2-transfer itinerary, no feed, offline/stale, saved empty/populated, severe alert, error.
- 3 Android widths (compact phone, large phone, tablet where available), 200% font, portrait/landscape where supported; no overlap with navigation/system bars.
- TalkBack can read and operate every interactive item; route colors accessible in grayscale; focus returns to expected element after sheet dismissal.
- 60fps target where device allows; no frame churn from recomposition, no map-camera ping-pong, safe animations on low-end BLU, memory and cold-start timing recorded.
- At least two independently tested rider journeys with real stored GTFS, not the debug subset; primary tasks complete without unsupported backend.
- Every screenshot and video must correspond to checked-in code at a documented SHA. Do not claim "stunning" from theme code alone.

## REVIEW / CLOSURE
This is a design and implementation plan, NOT a UX completion receipt. Existing VISUAL blueprints are retained as historical context. Any screenshot reference must distinguish inspiration from actual IntelliTrip UI. Requirements are contingent on correct schedule/live provenance and completed action wiring.

**Dependencies:** WO-011, WO-013 (critical rider truth and no-op controls), WO-012 for offline status integrity. **Out of scope:** actual offline tile installer, server OTP, browser parity.
