# Privacy policy

Last updated: 2026-10-12

IntelliTrip is a transit map and trip planner. This policy describes what the app stores,
what it sends, and what it deliberately does not do.

## The short version

- No account. No sign-in, ever.
- No advertising or analytics SDKs.
- Location is used on device and is not uploaded.
- The only data sent anywhere is a request for the things you asked to see.

## What the app stores on your device

| Data | Where | Why | Removed when |
|---|---|---|---|
| Downloaded transit timetable | App private storage | Planning and departures work offline | You clear app data, or the app replaces it with a newer feed |
| Saved stops | App private storage | So your saved places work offline with no account | You remove them in the app |
| Last known location | Memory only, not written to disk | Centering the map | When the app process ends |

None of this is readable by other apps. It lives in Android's app-private storage and
is removed when you uninstall.

## Location

Location permission is requested only when you tap the locate control. It is never
requested at launch.

- Your position is used on your device to center the map and to find nearby departures.
- Your position is **not** uploaded, logged, or shared.
- Coarse location is fully supported. If you grant approximate location only, the map
  still centers on you; it simply tells you the fix is approximate instead of claiming
  meter-level accuracy.
- A stale fix is shown as "last known" with its age and never moves the map on its own.

## What the app sends over the network

Only when you ask for it, and only to the provider that supplies the feature:

- **Transit timetable** — a request to the agency's public GTFS feed when you download
  schedules or refresh them. Refresh uses `If-None-Match` / `If-Modified-Since`, so an
  unchanged feed transfers no data at all.
- **Vehicle positions and trip updates** — a request to the agency's public GTFS
  Realtime feed, when you turn on live data or auto refresh.
- **Radar imagery** — tile requests to RainViewer when you turn the precipitation layer
  on. Radar tiles are visible to RainViewer as ordinary image requests.
- **Severe weather alerts** — a request to the US National Weather Service covering the
  area you are looking at.
- **Basemap tiles** — tile requests to CARTO for the map itself.

**Trip planning runs on your device.** When a journey is planned, your origin and
destination stay on the phone. The app does not send them to a server to find you a
route.

## Sharing

None. IntelliTrip has no mechanism to share your location, saved places, or journeys.

## Third-party providers

| Provider | Used for | Key required | Notes |
|---|---|---|---|
| Metro Transit | Timetable, live vehicles | No | Public agency feeds |
| CARTO | Basemap tiles | No | |
| RainViewer | Precipitation radar | No | Radar data by RainViewer; still under review for production licensing |
| US National Weather Service | Severe weather alerts | No | Public API |

## Children's privacy

The app collects no personal information and creates no user profiles.

## Changes

Any change to what the app collects will be reflected here before it ships.

## Contact

Questions about privacy: open an issue in the project repository.