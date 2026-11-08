# Phase V1 — Rider Tasks: Departures, Planner, Detail

## Objective

Make the three daily rider flows — *when is the next bus, plan a trip, tap a
feature* — feel consumer-grade. Borrowed from Transit (departure hero),
Citymapper (step timeline), Moovit (nearby default), Google Maps (pitch-black
status discipline). No new map layers; this is information design.

## Prerequisites

- Phase V0 frozen (chrome, glyphs, route colors). Peek sheet exists.

## In scope

### 1. Departure board (`ExploreScreen.kt`, `DepartureBoard.kt`)

Borrowed from Transit's "next bus" screen + Google Maps ETA hero:

- **Row layout (locked):** left 72dp column = wait ("4 min" in `displayMedium`,
  "Due ≤0"), under it provenance chip (`● Live` green / `◎ Schedule` grey);
  center = route pill (short name, route color bg, white text) + headsign +
  stop name (small); right = route-color bar + chevron. One row per departure,
  sorted by time (already).
- **Live updates:** rows tick every 30s (timer in the sheet, not a poll) —
  minutes recompute, "Due" flips, no full list rebuild. Interrupted when sheet
  is dismissed.
- **Frequency lines:** show "every 10 min" suffix when `frequencies` expand
  the board (planner already expands; board expanded in the prior session —
  surface the headway in the row: `Route 9 · every 10 min`).
- **Empty states:** "No departures nearby — pan or zoom out" (already) + distance
  note ("Nearest stop 240 m") using `metersBetween`.
- **Tap:** selects stop in map (focus camera 300m), opens `MapFeatureSheet`
  stop detail. Today `onSelectDeparture` is a no-op (`MainActivity.kt:267`).

### 2. Planner (`MapChrome.kt:PlanSheet`, journey detail)

Borrowed from Citymapper GO + step timeline:

- **Fields:** From/To are real text fields with presets + "My location" row
  (today preset chips only). Swap button between them (already atomic).
- **Modes row:** chips `[Transit] [Walk only]` — Global toggle filtering
  journeys (Transit = planner; Walk only = haversine walk leg only, stub
  "outside coverage" when >2km — honest, not estimated).
- **Results:** up to 5 cards (already `MAX_RESULTS`), each = headline
  ("08:30 → 08:58 · 28 min") + 3 mode icons (walk/transit/walk) + transfer
  count badge ("1 transfer") + warning line. Selected card = `secondaryContainer`
  (already) + "Shown on map".
- **Timeline:** tapping a journey expands inline steps (today journey detail is
  a separate sheet `MapFeatureSheet:JourneyDetailBody` — inline it):
  `Walk 4 min (280 m) → Route 9, 08:30–08:42 → Transfer 3 min → Route 2…`
  Each step is a row with icon, time, and status word (on time/delayed/scheduled).
  Transfer rows get confidence dot (HIGH green, MEDIUM amber, LOW red) from
  `TransferConfidenceScorer` — surfaced in planner for the first time.
- **Status:** Planning spinner in the Plan button ("Planning…"), empty results
  = "No route within walk limit — try a closer destination" (PartialResult
  missing reasons mapped to rider strings, not `stops-within-walk-limit`).

### 3. Feature detail (`MapFeatureSheet.kt`)

- **Stop:** name, "240 m · 4 min walk" (when location known), departure rows
  (reuse board rows, compact), Save/Remove (already). No departures → explicit
  "Schedule not downloaded" + Download button (already `EmptyPanel`).
- **Vehicle:** route + headsign + `Live position` / `Updated 4 min ago` (already
  correct) + "Track this" follow toggle (sets `Focus.Vehicle`) — new.
- **Cluster:** "12 vehicles here" + "Zoom in" (already) + zoom-preview list of
  first 5 vehicle rows. No more dead-end panel.
- **Ambiguous:** keep list-not-guess (already correct); add vehicle/stop icons
  per row.

### 4. Cross-cutting

- **Typography:** ETA hero 34sp tabular numerals (`FontFeatureSettings="tnum"`),
  alignment columns so times line up down the board.
- **Spacing:** board rows 12dp vertical, cards 16dp radius, sheet padding 16dp
  (align to `Space` tokens; remove ad-hoc `12.dp`/`10.dp` literals).
- **Snackbars:** plan errors ("Planning unavailable: …" today status text)
  surface as `Snackbar` + status footer. One queue, no stacking.

## Out of scope

- Map glyph/camera animation (V2).
- Geocoding/search backend (presets remain, labeled honestly).
- Fare comparison (Citymapper has it; we don't have fare data — omit).
- Favorites sync, accounts, sharing.

## Components affected

- `app/.../ui/ExploreScreen.kt`, `ui/MapChrome.kt` (PlanSheet), `ui/MapFeatureSheet.kt`,
  `ui/DepartureBoard.kt` (view only), `Formatting.kt`
- `IntlTripViewModel` (board timer, follow toggle) — no engine changes.

## Requirements

- No planner logic change except surfacing `PartialResult.missing` + transfer
  confidence already computed.
- "My location" endpoint resolves via `host.location` fix (already) — no new
  permission flows.
- Every new string has a TalkBack sentence; ETA rows keep `accessibilityLabel`.

## Tests/gates — zero tests until complete

No tests during implementation. At phase-complete, **one** gate:

1. `test` + `lint` + `assembleDebug`
2. Visual drive: cold start with feed → peek shows 3 rows; board ticks; frequency
   line reads "every 10 min"; plan with 2 transfers shows confidence dots;
   no-route shows rider string; track vehicle follows; TalkBack reads board in
   order; 200% type does not clip rows.
3. `assembleRelease` (unsigned) smoke: same drive, no R8 crash (shrinker
   regression check per `proguard-rules.pro` note).

## Definition of Done

A rider can answer "when, where, which" in under 5 seconds from the board,
plan a 2-transfer trip and read its steps, and tap any map thing and get a
useful detail — without reading engine vocabulary anywhere.

## Freeze gate

Visual sign-off on board + planner screenshots (light + dark). Style-only fixes
afterward.

## Artifacts

- `ExploreScreen.kt` (board rows), `MapChrome.kt` (planner inline timeline),
  `MapFeatureSheet.kt` (follow + cluster list)
- Rider-mapped strings for `PartialResult.missing`

## Status

Implemented. Gate: `test` + `lint` + `assembleDebug` green.
Visual drive-through remains human (board tick, frequency suffix, planner
confidence dots, track-vehicle, TalkBack, 200% type).
