# What you need to do, as a human

Short version: **nothing is required to run the app.** Everything below is optional
and additive. There are no API keys to obtain, no accounts to create, and no
billing details to enter for the app to work.

## Nothing needed to start

| Feature | Status | You must do |
| --- | --- | --- |
| Static transit network | Works, verified on-device | Nothing |
| Live vehicle positions | Works, verified on-device | Nothing |
| Trip planning | Works, verified on-device | Nothing |
| Radar | Works via key-free public API | Nothing |
| Weather alerts | Works via NWS, no key | Nothing |
| Map | Works via CARTO public tiles | Nothing |

Data sources are Metro Transit (public GTFS + GTFS-RT), CARTO (public basemap
tiles), RainViewer (public radar, no key) and NOAA/NWS (public alerts). None
require registration for the volumes this app uses.

---

## Optional 1: Self-hosted OTP routing

Only needed if you want **cross-agency** trips and **fare-aware** itineraries.
Single-metro planning already works without it.

### Cost: $0 (Oracle Cloud Always Free)

- Sign up: https://signup.cloud.oracle.com/ — **needs a credit card for identity
  verification, but the Always Free tier is not charged.**
- Pick the home region closest to Minneapolis.
- Create a VM.Standard.A1.Flex shape, 2 OCPU / 12 GB RAM (Always Free allows up to
  4 OCPU / 24 GB across instances).
- Ubuntu 22.04 or later. ~30 GB boot volume is plenty.

Watch out: Oracle **reclaims idle Always Free instances**. A VM with near-zero CPU
for several days can be stopped. Keep it warm with a cron job hitting a health
endpoint every few hours:

```
*/30 * * * * curl -sf http://localhost:8080/otp/routers/default/index > /dev/null
```

### Build the graph

```
java -Xmx8G -jar otp.jar --build /opt/otp/graphs \
  --router default \
  /opt/otp/graphs/metro-transit.osm.pbf \
  https://svc.metrotransit.org/mtgtfs/gtfs.zip
```

Metro Transit GTFS needs no auth. For the OSM extract, use a Geofabrik metro-area
extract (a few hundred MB) rather than the planet file.

### Point the app at it

The base URL is read from configuration; nothing is committed. With it unset, the
app runs local-only, which is fully supported:

```
OTP_BASE_URL=https://your-instance-host/otp
```

See `docs/OTP_DEPLOYMENT.md` for the full recipe.

### Ongoing work

OTP's graph is a **separate artifact** from the app's GTFS snapshot. They do not
sync. After every Metro Transit feed update, rebuild the OTP graph or remote
routing will serve stale schedules. Set a monthly reminder.

---

## Optional 2: Offline basemap package

The app is offline-capable for **data** today (transit network, planning) but the
**basemap** still needs the network, because no offline tile package is installed.
`OfflineBasemapStore` exists to manage packages; nothing is bundled because
shipping a tile extract would mean committing hundreds of megabytes.

### Free routes

1. **Do nothing.** Offline shows transit data over a blank/gray map. Legitimate.
2. **Generate your own.** Download a Geofabrik metro extract and build vector tiles
   with [tilemaker](https://github.com/systemed/tilemaker), then:
   ```
   tilemaker --input minnesota-latest.osm.pbf --output metro.mbtiles \
             --config metro.config.json
   ```
   Then install the package in-app (Data sheet → Offline basemap).
3. **Download a free pack.** [Protomaps](https://docs.protomaps.com) publishes
   free `.pmtiles` basemaps built from OSM. No key required.

There is deliberately no step 4: every commercial tile vendor (MapTiler, Mapbox,
Stadia) requires a signup and a billing card, which the free constraint rules out.

---

## Optional 3: Publish to Google Play

Not required to build or run. When you want it:

- Google Play Developer account: **$25 one-time**. There is no way around this.
- Create an upload key: `keytool -genkeypair` (Android Studio generates one).
- Sign the release build with it.
- First upload is manual review; updates are not.

Note the app currently has no release signing config, no ProGuard rules, and no
store listing copy. Those are the remaining pieces before a Play submission.

---

## Not required, but you'll want it

**A better feed than Metro Transit.** The app is written against the feed-agnostic
provider contracts. Swapping in Twin Cities + another agency means implementing
`TransitStaticProvider` / `TransitStaticRealtimeProvider` and passing a second
`FeedId`. That is the extension point Phase 00–09 were built around.

## Where the decisions are recorded

| Question | Answer | Document |
| --- | --- | --- |
| Routing topology | Hybrid, self-hosted OTP, free tier | `docs/ADR/007-routing-core.md` |
| Radar source | RainViewer, key-free, attribution required | `docs/ADR/003-weather-provider.md` |
| Legacy prototype | Retired, not deleted (protected files) | `docs/ADR/014-retire-atlas-transit.md` |
| Phase status | 00–09 closed, post-freeze work recorded | `docs/blueprints/PHASE-09-sdk-extraction.md` |