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
- Run a lightweight conditional check daily using `If-Modified-Since` / `Last-Modified` validators.
- `304 Not Modified`: keep the current snapshot.
- `200 OK`: download, then validate (parse + required tables + agency/route/stop consistency) before activation.
- Replacement is atomic: only after successful validation, the new snapshot is swapped in and the old one discarded.
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
- OPEN: no offline vector tile package is bundled yet; the policy hook exists so a package can be added without architectural change.
