# Phase V0 — Design System, Chrome & Map

## Objective

Replace the current FAB column + ad-hoc sheets with the transit-app chrome
described in `VISUAL-00-overview.md`, and give the map a real visual language:
oriented vehicles, route-colored shapes, a selected-journey band. This phase is
*structure*, not choreography — nothing animates yet beyond Material defaults.

## Prerequisites

- `Theme.kt` tokens (Urban Signal) frozen; `ServiceCalendar`, planner, and
  scope fixes from the prior session merged.

## In scope

### 1. Design system (`ui/theme/Theme.kt`, `res/values/`)

- **Color:** keep Urban Signal; add `surfaceContainer*` ramp, `scrim` token
  (40% black), `routeChip` surface. No dynamic color (already decided).
- **Type:** add `displayMedium` (34sp) for ETA hero; keep 44sp displayLarge for
  departure minutes. Load one display font (system default today — no bundled
  font files; if adding, `res/font/` + `fontFamily` fallback).
- **Shape/elevation:** chips 12dp, cards 16dp, sheet 20dp top — already defined;
  apply uniformly. FAB corner 16dp (M3 default is 16).
- **Iconography:** adaptive icon (foreground/background vector), splash
  background matching `CanvasLight`/`CanvasDark`. No raster assets.
- **Motion tokens:** *adopt* `Motion.SelectionMillis=180`, `SheetMillis=260`
  in sheet/camera code or delete the object (it is currently dead).

### 2. Chrome (`MainActivity.kt`, `MapChrome.kt`, `RiderNavigation.kt`)

Borrowed from Google Maps / Apple Maps peek:

- **Persistent search pill** top-center (`DestinationSearchEntry` today moves to
  `statusBarsPadding` always-visible, not only on Explore). Tapping opens Plan
  sheet with both fields focused. Keep `Where to?` hint.
- **Filter chips** under the pill: `[Now▾] [Transit] [Radar]` (Filter Chips,
  `ToggleButton`-style). `Transit` toggles shapes+stops; `Radar` toggles radar
  layer; `Now` opens depart-time picker stub (display only this phase).
- **FAB column collapse:** delete 4-FAB column in `MapChrome.kt`. Replace with:
  bottom-end `Locate` (44dp, tinted primary when fix active — keep), above it
  extended `Plan` FAB (`ExtendedFAB` with `TravelExplore` icon + "Plan").
  Data/Layers/Departures FABs removed — their actions live in the peek sheet.
- **Peek sheet** (replaces `NearbyDeparturesSheet` modal): `ModalBottomSheet`
  with `skipPartiallyExpanded=false`, collapsed = 3 departure rows + handle,
  expanded = full board (V1 refines rows). Drag handle is the dismiss affordance
  (already in `Scrim` — hoist to peek).
- **Scope chip** moves into the peek header (right-aligned), not floating top-start.
  Label stays rider language ("Near you · street level").
- **Navigation:** keep bottom bar / rail at 600dp; add `Badge` on Explore for
  missing feed (already there). No change to `Destination` enum.
- **Empty/loading/error:** `EmptyPanel` everywhere (already shared) + one
  `Snackbar` for "Download failed" (today status text only). Snackbar is the
  only transient chrome.

### 3. Map visuals (`core/atlas-map`, `core/atlas-map-android`)

- **Vehicles:** oriented glyphs — `SymbolLayer` with `icon-rotate` from `bearing`
  (currently circles). Live = route-colored fill + white ring; stale = grey
  (`#9CA3AF` today), interpolated = same fill at 60% opacity. Size by
  `vehicleType` (bus 16dp, rail 20dp). GeoJSON gains `bearing`/`routeColor`
  properties; renderer binds them. Circles remain the CLUSTER bucket fallback.
- **Shapes:** per-route color from `route.color` (GTFS) with white casing underlay,
  4dp width. Today all `#0B5FA5`. Fallback blue when `color==null`.
- **Selected journey:** 6dp solid band (`#DC2626` today) → 8dp with white casing,
  `line-cap/join round` (already set), plus stop-number dots at board/alight.
  Walk legs dashed (requires `LineLayer` dash array — add).
- **Stops:** 8dp white circle, ink outline, route dot when zoom ≥14. No labels
  (density) — tap selects, detail sheet shows name (already works).
- **Basemap:** CARTO light/dark styles keyed to `LocalIsDark` (today always
  light). Offline package selection stays `OnlineOnly` this phase (V2 wires it).
- **Clusters:** keep grid cells, but draw count text in the glyph (`text-field`
  = count, stepped size already in `MapLibreMapRenderer:396-415` — expose it).
  Singleton = 10dp dot (catalogued behavior, keep).

## Out of scope

- Any animation beyond Material sheet/FAB defaults.
- Planner/departure row redesign (V1).
- Audio/haptics (V2).
- Offline basemap rendering.

## Components affected

- `app/.../ui/theme/Theme.kt`, `ui/MapChrome.kt`, `ui/ExploreScreen.kt`
  (peek), `ui/RiderNavigation.kt`, `res/`
- `core/atlas-map/.../GeoJsonWriter.kt`, `RenderModels.kt`
- `core/atlas-map-android/.../MapLibreMapRenderer.kt`

## Requirements

- No provider or scope changes. `state.scope` still drives camera.
- All touch targets ≥48dp; chips ≥32dp height with 48dp padding.
- Dark basemap must not recolor green/amber/red semantics (already frozen).
- Route color failure → fallback, never crash (already `parseRouteColor`).

## Tests/gates — zero tests until complete

No unit, screenshot, or UI tests during implementation. At phase-complete,
**one** gate (in this order):

1. `assembleDebug` + `lint`
2. `test` (whole project) — includes `PortabilityGuardTest`, scope round-trip
3. Manual visual drive-through (light/dark, bar+rail, 200% type, TalkBack):
   search pill visible, peek collapses/expands, locate tints, plan FAB opens
   planner, vehicle glyphs rotate, route colors differ, journey band visible,
   clusters show counts, dark map readable.

## Definition of Done

A reviewer opening the app cold sees a transit-app shell, not a debug map:
search pill, 3 chips, locate + Plan FAB, peeking departures, oriented colored
buses, colored route lines, and a dark mode that looks designed.

## Freeze gate

Visual sign-off on screenshots at 360dp and 800dp widths, light + dark.
Code freeze until gate passes; fixes are style-only.

## Artifacts

- Updated `Theme.kt`, `MapChrome.kt`, `ExploreScreen.kt`, `MapLibreMapRenderer.kt`
- Adaptive icon + splash colors
- This blueprint marked Implemented

## Status

Implemented. Gate: `assembleDebug` + `lint` + `test` (whole project) green.
Visual drive-through remains human (screenshots at 360/800dp, light/dark,
TalkBack). Oriented glyphs require on-device confirmation that
`icon-rotate` from GeoJSON `bearing` and dark basemap render over CARTO.
