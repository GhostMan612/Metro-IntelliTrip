# Blueprint Set — Visual Polish (V0–V2)

> Three phases. No tests run until a phase is declared complete. This is deliberate:
> iterate visually first, prove once per phase gate.

## Why three

Phases 00–09 built a correct engine. The app's debt is felt polish, not
correctness: no camera tween, no vehicle glyphs, no sheet springs, no audio.
Those can be shipped in three large, reviewable phases instead of a trickle of
micro-PRs. Each phase ends with one full verification gate, not per-change tests.

| Phase | Goal | Outcome |
|---|---|---|
| **V0 — Foundation** | Design system + chrome + map | A recognizably different app shell: search pill, peek sheet, FAB/echo layout, themed tokens, oriented glyphs |
| **V1 — Rider tasks** | Departures + Planner + Detail | The three flows a rider uses daily feel consumer-grade |
| **V2 — Motion + Ship** | Juice + hardening + release | Camera tweens, live interpolation, arrival audio/haptics, dark basemap, ship-ready |

## Comparative analysis (borrowed, not copied)

**Google Maps** — persistent search pill anchored top; layer chips above the locate
FAB; collapsed peek sheet showing next departure; place cards with ETA as the
hero number; chevron affordance to expand. *Borrow:* search pill + chips + peek
sheet. *Skip:* multi-mode tabs, ads, Knowledge Graph.

**Transit (Transit App)** — one screen answers "when is the next bus": huge countdown,
green dot = live, favorites pinned, GO turn-by-turn. Cleanest departure UX in the
category per 2026 reviews. *Borrow:* ETA hero, live discipline, favorites-first board.
*Skip:* gamification, leaderboard, crowdsourcing points.

**Citymapper** — big GO button, step-by-step itinerary with walking distances,
disruption banners in-line, saved Home/Work, mode-filter chips. *Borrow:* GO-equivalent
("Plan" primary), step timeline with minutes+meters, alert banner pattern.
*Skip:* rideshare/cab comparison, dark humor, AI mode.

**Apple Maps** — immersive map, translucent chrome, collapsed sheet with handle only,
transit line chips colored by line, "Details" for accessibility. *Borrow:* translucent
peeking sheet, line chips, immersion. *Skip:* iOS-only carplay/auto.

**Moovit** — real-time arrivals with GPS-sourced ETAs, "nearby" list, favorite lines,
lift-off notifications. *Borrow:* nearby-by-default board, favorite-line concept.
*Skip:* advertising, paywall, crowded line cards.

**Material 3** — NavigationBar/Rail, extended FAB, Filter Chips, ModalBottomSheet
(half/full states), badged icons. *Borrow:* all primitives; this app already uses
them, but places them per the transit-app choreography above instead of generic
Android chrome.

**Resulting IntelliTrip chrome (constant across phases):**

```
+--------------------------------------------------+
| [ Search "Where to?"                    (⌕) ]    | <- persistent pill, top inset
| [Now ▾][Transit ▾][Radar 🌧]   [scope chip]        | <- filter chips (transit-only)
|                                                  |
|                    MAP                           |
|                                  [Locate]        |
|                                  [Plan ▸ FAB]    | <- extended FAB, bottom-end
+-- [Peek: Next departures ▸ 3 lines] ------------+
| [Explore][Plan][Saved][More]   (bottom bar/rail) |
+--------------------------------------------------+
```

- Peek sheet (collapsed) shows the next 3 departures; expand → full board.
- Right-side 4-FAB column (`MapChrome.kt` today) is replaced by 2 controls:
  locate (bottom-end) + extended Plan (bottom-end, above locate).
- Layers/reread/auto-refresh move *into* the peek/dat sheet, not floating FABs.

## Ground rules for all phases

- Architecture unchanged: pure-Kotlin `core/atlas-*`, bulk GeoJSON, feed-scoped keys.
- `Theme.Motion` (180/260ms) is **used** or deleted — no dead tokens.
- Zero new permissions, zero new network calls without an explicit tap.
- Zero tests until phase-complete (see each gate). No CI additions mid-phase.
- Route identity never color-alone (name + number + chip), per `Theme.kt`.
- Every visual change ships with a TalkBack sentence.

## Exit criteria (whole set)

- Cold start → map framed, peek sheet shows departures if feed present.
- Pan/pinch → camera stays put (no snap; inverse-formula fix already in).
- Plan → 3-leg journey draws, selects, and reads as a timeline.
- Light + dark + 200% type + TalkBack drive-through passes visually.
- `assembleDebug` + `assembleRelease` (unsigned) + `lint` green at each gate.
