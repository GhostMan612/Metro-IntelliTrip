# Offline Model

## Authoritative local data
- Last accepted static GTFS snapshot once downloaded.
- Cached routes/stops/shapes.
- Cached route plan results where explicitly saved by user.
- App settings and non-sensitive provider metadata.

## Cacheable
- Static GTFS zip and parsed indexes.
- Latest successful vehicle/trip-update snapshots with timestamps.
- Recent radar frames metadata/tiles where permitted.
- Basemap tiles/style assets when offline map strategy is implemented.

## Requires connectivity
- Fresh realtime vehicle positions.
- Trip updates and service alerts freshness.
- Live weather alerts/radar updates.
- Routing requests unless provider supports offline/local routing later.

## Stale-data behavior
- Every realtime payload carries timestamp/age.
- UI marks stale data visibly.
- Do not silently reuse expired realtime positions as current.

## Static GTFS refresh
- Run a lightweight conditional check daily using `If-Modified-Since` / `If-None-Match` validators.
- Validators are persisted per feed in `validators.properties` beside the snapshot, written
  through a temporary file and moved into place. A publisher that sends neither validator
  simply gets a full download every time.
- `304 Not Modified`: keep the current snapshot. Validators returned with the 304 replace
  the stored ones, so a rotated ETag still matches later requests.
- `200 OK`: download, then validate (parse + required tables + agency/route/stop consistency) before activation.
- Validators are recorded only after the new snapshot has been activated, so a rejected
  payload can never leave behind an ETag that would suppress the retry meant to replace it.
- Replacement is atomic: only after successful validation, the new snapshot is swapped in and the old one discarded.
- After activation, superseded snapshots are pruned: the active one plus at most one
  recent fallback are kept. A reader that resolved the pointer just before it moved still
  has something valid to open. Failed or interrupted refreshes otherwise leave a full copy
  of the feed on disk that nothing ever reclaims.
- Offline snapshots record `fetchedAt`, feed validity window (`validFrom`/`validTo`), version/hash, and activation/swap metadata so stale or superseded feeds are never silently activated.

## Provider capability checks
- Provider capability checks may detect schema/feed changes.
- Offline snapshots include fetchedAt, feedVersion if known, and expiry policy.

## Degradation
- No realtime: show static network and last-known timestamps.
- No weather provider: hide radar layer, keep transit functional.
- No routing/network: allow saved trips/favorites only; explain routing unavailable.

## Implementation status
- `core/atlas-gtfs-static`: `StaticFeedSnapshotStore` keeps immutable versioned snapshots and swaps the active pointer atomically.
- `core/atlas-offline`: `StalenessModel` (FRESH/STALE/EXPIRED/ABSENT), `OfflineRegistry` (capability report + offline feed loading + live-vehicle gating), `BasemapPolicy` (online / offline package / missing).
- Cached realtime data is retained with its fetch time and is only rendered as live while fresh; otherwise the engine reports realtime unavailable instead of drawing stale positions.
- A cold start discards stop times while reloading a snapshot (`OfflineRegistry.loadStaticFeed` passes a no-op sink). They are already on disk in the snapshot's stop-time index and are read back on demand, so offline reload no longer rebuilds the whole table in memory.
- `OfflineBasemapStore` (new): installs and tracks offline basemap packages, validating that a package is large enough to be real and carries a style, since tiles without a style render as nothing and a style without tiles has nothing to draw. A package whose tile file has vanished is reported uninstalled regardless of what the manifest claims.
- No package is **bundled**: shipping a tile extract would mean committing hundreds of megabytes. Packages are produced by the user from OpenStreetMap data via tilemaker, or taken from a free Protomaps build. `HUMAN_ACTIONS.md` documents the routes.
- Offline is therefore data-only by default: transit network and planning work with no network, but the basemap does not. `BasemapPolicy.OfflineOnlyMissing` reports this rather than showing a blank canvas.
