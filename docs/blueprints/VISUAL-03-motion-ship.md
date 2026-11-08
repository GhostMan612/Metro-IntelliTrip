# Phase V2 — Motion, Joy & Ship

## Objective

Add the feel: smoothed camera, living vehicles, arrival audio/haptics, and a
dark basemap. This is the smallest phase by code, the largest by perception.
If V0/V1 look right, V2 makes them feel right.

## Prerequisites

- V0 (chrome/map) and V1 (tasks) frozen.

## In scope

### 1. Motion system (adopt, don't invent)

One spring/easing vocabulary, applied everywhere (replaces today's instant
jumps + dead `Motion` object):

| Motion | Duration | Easing | Used for |
|---|---|---|---|
| Selection | 180ms | Emphasized decelerate | FAB press, row select, journey highlight |
| Sheet | 260ms | Emphasized decelerate | peek ↔ expanded, feature sheet |
| Camera | 600ms | Emphasized accelerate-decelerate | `moveCamera` on scope/journey/follow |
| Vehicle | 1s (poll cadence) | Linear | interpolation between realtime snapshots |

- **Camera tween:** replace `MapLibreMapRenderer.moveCamera` direct assignment
  with `animateCamera`. Programmatic-echo suppression (already in renderer,
  `programmaticTarget`) must key on the *final* tween destination, or the
  gesture filter swallows the rider's own pan mid-tween (today's bug class).
- **Vehicle interpolation at 60fps:** keep server interpolation in
  `TransitRenderPipeline` (30s cap, today) but drive GeoJSON updates on a
  `Choreographer` frame loop *between* polls, interpolating 0→1 toward the
  latest snapshot. No fake motion when `interpolated==false`. Bearing rotates
  glyph smoothly (V0 glyphs).
- **Sheets:** `ModalBottomSheet` with `SheetMillis` tween; peek sheet drag +
  snap (half → full), scrim fades 40% (already in `Scrim` — unify).
- **FABs:** press scale 0.97, Plan FAB ↔ "Planning…" morph (width tween),
  locate tint crossfade (already instant — tween it).
- **Rows:** staggered 24ms cascade on board/planner result entry (Material3
  `AnimatedVisibility` + `animateItem`). Cap at 8 items.
- **Respect `reduceMotion`:** when system animator duration scale is 0, all
  above become instant (accessibility win, one flag).

### 2. Audio + haptics (one of each, not a suite)

Borrowed from Citymapper "get off now" and Transit GO — not Waze gamification:

- **Arrival chime:** single soft `SoundPool` tone (2-note, <0.4s, no bundled
  audio file — synthesize with `ToneGenerator` or tiny WAV generated at build
  time; no asset bloat). Plays when the active journey's alighting stop is
  within 100m of `userLocation` *and* realtime is fresh. No other sounds.
- **Haptics:** `CLOCK_TICK` on plan result, `TEXT_HANDLE_MOVE` on sheet snap
  points, `KEYBOARD_TAP` on locate success. All behind a Settings toggle
  (default on, respects "silent" mode — no audio when ringer silent).
- No TTS, no vibration on radar/alerts (alerts are visual badges).

### 3. Visual joy (small, targeted)

- **Dark basemap:** CARTO dark style when `LocalIsDark` (V0 only keyed chrome;
  V2 keys tiles). Verify green/amber/red semantics unchanged over dark tiles.
- **Route timeline on map:** selected journey's walk legs dashed (V0 added dash
  capability — polish widths), transfer dots pulsing once on selection.
- **Empty/error art:** one-line vector illustrations (bus icon variants) for
  "no feed / no departures / no route" — `Icon` tinted `onSurfaceVariant`,
  no Lottie, no raster. Consistency beats illustration detail.
- **Splash:** system splash background `CanvasLight/Dark` + adaptive icon
  (V0) — no custom splash screen code.

### 4. Ship hardening (no new features)

- **Offline basemap wiring:** `BasemapPolicy` already reports
  `OfflineAvailable`; `MapCanvas` chooses offline style when installed and
  network is down. Without a package, offline shows transit over blank canvas
  with the honest "basemap missing" note (already in `OfflineRegistry` notes).
  Installer UI/driver is **not** in this phase (needs file picker + `.mbtiles`
  validation — track as V3 if wanted).
- **Release:** signing template (`docs/RELEASE.md`) verified end-to-end with a
  throwaway keystore; `versionCode` bumped; store listing copy drafted (what /
  screenshots / data-safety — NWS + RainViewer attributions required).
  `assembleRelease` AA B (`bundleRelease`) instead of APK.
- **Lint/a11y sweep:** 48dp audit, contrast check for route chips on both
  themes, `accessibilityLabel` pass on every new control.

## Out of scope

- OTP remote-route polish, fare display, accounts, sharing, Wear.
- Basemap package installer UI, tile serving.
- `maxTransfers > 2`, `largeHeap` re-tune.

## Components affected

- `ui/theme/Theme.kt` (Motion adoption), `ui/MapChrome.kt`, `ui/MapCanvas.kt`
- `core/atlas-map-android/.../MapLibreMapRenderer.kt` (tween, glyphs, dark style)
- `core/atlas-map/.../GeoJsonWriter.kt` (bearing/routeColor properties)
- `IntelliTripViewModel` (60fps holder, arrival check, toggles)
- `res/` (adaptive icon, splash, illustration vectors), `docs/RELEASE.md`

## Requirements

- Motion never blocks state: camera tween is view-only, scope remains source
  of truth (inverse-formula identity from prior session must hold).
- Audio only when realtime fresh + user in journey; never when stale.
- No new runtime permissions. No network in this phase.

## Tests/gates — zero tests until complete

No tests during implementation. At phase-complete, **one** gate:

1. `test` + `lint` + `assembleDebug` + `assembleRelease` (unsigned, then signed
   smoke if keys provided)
2. Device drive (emulator at minimum): pinch stays put, plan tween lands,
   vehicles glide between polls, arrival chime fires once within 100m, silent
   mode suppresses, dark map readable, offline shows blank canvas with note,
   200% type + TalkBack re-passed.
3. `publishAtlasToLocalRepo` still green (SDK untouched by V2).

## Definition of Done

The app feels like it is aware of motion but never moves on its own. A rider
gets one unmistakable "get off now" and can ship to Play with a signed AAB.

## Freeze gate

Device video of the three motions (camera, vehicle glide, sheet) + signed
AAB proof. Style-only fixes after.

## Artifacts

- Tweened renderer, 60fps interpolation driver, arrival audio/haptic utils
- Dark basemap, icon/splash/illustration vectors
- Signed AAB + listing copy draft

## Status

Implemented. Gate: `assembleDebug` + `lint` green.
Device drive-through (tween feel, glide between 15s polls, arrival chime
within 100m, silent-mode suppression, dark map) remains human.
Signed AAB still needs your keystore + account.
