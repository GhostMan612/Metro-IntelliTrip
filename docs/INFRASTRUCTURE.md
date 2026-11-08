# Infrastructure deep-dive — how the big apps do it

> Research date: 2026-11-08. Sources: Moovit, Transit, Citymapper, Google Maps,
> Apple Maps (2026 reviews/comparisons), Protomaps PMTiles, OTP docs, GTFS
> Best Practices. Goal: borrow what makes IntelliTrip shippable on a 2.8 GB phone.

## 1. What the leaders actually do

### Moovit (3,500 cities, 1.5 B users)
- **Offline = downloaded artifacts, not live parse.** Full PDF transit maps +
  saved line schedules, cached in local memory. No on-device GTFS unzip/parse
  on first run — the server pre-renders.
- Community + official GTFS, Transit Data Manager builds feeds server-side.
- *Borrow:* pre-computed downloadable bundle; arrival predictions computed
  server-side from GPS, not phone-side ETA math.

### Transit (cleanest departure UX)
- In-house ETA engine + agency feed + GO crowdsourcing. Offline: saved
  schedules, not full network parse.
- *Borrow:* ETA hero is a value, not a formatted string; favorites persist offline.

### Citymapper (6 US cities)
- Time + cost comparison across Uber/Lyft/bike — needs server aggregation.
- Offline: NYC subway PDF, not a routing engine.
- *Borrow:* mode chips, disruption banners in-line. *Skip:* cost, ads.

### Google Maps
- Best-in-class offline navigation, server tile pyramid, progressive download.
- Transit is one mode among many — depth over speed for transit specifically.
- *Borrow:* tile pyramid pre-rendered, range-request fetching.

### Apple Maps
- Server-rendered tiles, transit line chips, immersion. No on-device GTFS parse.
- *Borrow:* translucent peek sheet, line colors.

### Protomaps / PMTiles
- Single-file Z/X/Y archive, HTTP Range Requests, **immutable** (rewrite whole
  file on update). Zero-maintenance maps on S3.
- *Borrow:* our offline basemap should be PMTiles, not MBTiles. Update =
  replace file, not patch.

### OpenTripPlanner
- Graph build is **hours** on a server (`graph.obj` from OSM + GTFS), then REST
  queries are milliseconds. The phone never builds the graph.
- *Borrow:* `graph.obj` is a build artifact, versioned, deployed — exactly our
  binary cache pattern. OTP `build-config.json` pulls from GCS; CI builds nightly.
- *Skip for v1:* self-hosting OTP (cost/ops) — keep local planner primary.

### GTFS Best Practices (RMI + 17 orgs)
- Stable URL, versioned datasets, persistent IDs across iterations.
- Parse into a warehouse (CARTO/BigQuery), serve tiles from the warehouse —
  never unzip 29 MB on the phone.

## 2. IntelliTrip today vs. the pattern

| Concern | Us | Leaders |
|---|---|---|
| First run | Download 29 MB ZIP → parse on phone (minutes on BLU) | Download pre-computed bundle (MBs) |
| Cold start | Re-parse ZIP (minutes) | Read binary cache (ms) |
| Offline basemap | Code ready, no package | PMTiles file, range-fetched |
| Routing | Phone planner + optional OTP server | Server graph, phone queries |
| Updates | Full re-download + re-parse | Delta / replace bundle |

We have already moved the first two rows: binary cache + streaming parse
cut heap 3× and desktop parse to 31 s. The BLU still pays minutes because it
parses the *full* feed *once*. The monster fix is to **never parse the full
feed on the phone at all**.

## 3. The monster (this design)

### A. Build-time bundle (`tools/` + Gradle)

```
gtfs.zip (29 MB, CI fixture)
  └─ JVM build (30 s on desktop, hourly CI)
       ├─ static-feed.cache (binary, ~6–8 MB after downsampling shapes to 200)
       ├─ stop-times.dat/.idx (trimmed to Twin Cities bbox, ~20 MB)
       └─ metro.mbtiles / metro.pmtiles (tilemaker + keep only z12–16, ~10 MB)
  ⇒ offline-bundle.zip (~35 MB)  → published alongside the APK
```

- Shapes downsampled 1,500 → **200 pts/shape** at build time (renderer only
  draws viewport-clipped geometry; 200 is invisible at 200% zoom).
- Stop-times clipped to bbox 44.6–45.4 / -93.7–-92.7 (Twin Cities metro) —
  cuts ~30% of rows with zero rider impact.
- Bundle is versioned (`hash` in `FeedMetadata`), immutable (PMTiles discipline),
  replaced wholesale on update via the existing conditional-refresh path.

### B. First-run (no download, no parse)

- App ships with `assets/offline/` containing `static-feed.cache` + stop-time
  index for the *bundled* snapshot. On first launch (or when `activeSnapshot`
  is absent), it copies the asset to `files/` and points the snapshot store at
  it — milliseconds, offline, no network.
- Conditional refresh still works: `If-None-Match` against the bundled hash;
  a 304 keeps the bundle, a 200 validates + replaces it.
- `OfflineRegistry.loadStaticFeed` reads the binary cache (already implemented);
  `stopTimeReader` reads the bundled index. Neither touches CSV.

### C. Updates (server precompute)

- CI nightly: download feed → build bundle → upload to S3/R2. App polls the
  feed URL; 200 → validate (streaming, on-device) → atomic swap.
- OTP graph stays a separate server artifact (`graph.obj`, nightly) — never
  on the phone. App queries it only when local planning fails (hybrid, existing).

### D. Why not ship the bundle in the APK?

35 MB would blow Play's 150 MB limit with dex + stays stale within days.
The bundle downloads once (like Moovit's PDF) from S3; the *first-run asset*
is a tiny 200 KB debug subset (minimal feed) so devs/BLU verify flows instantly.
Production first-run downloads the full bundle over Wi-Fi with progress.

## 4. Effort/impact

| Step | Effort | BLU impact |
|---|---|---|
| Bundle builder (JVM) | 1 day | Build 30 s, size −70% |
| First-run asset seed (debug subset) | 2 hrs | **Instant departures/planner on BLU** |
| PMTiles basemap + render choice | 2 days | Offline map tiles |
| OTP nightly graph + docs | 1 day | Cross-agency routing proof |

## 5. What to build next

Ship **B + debug subset now** (unblocks V1 device verification), then **A**
as a CI job. The debug subset is the thin end of the monster: same code path
as production, tiny data.

See `VISUAL-03-motion-ship.md` for the motion/audio work that rides on top.
