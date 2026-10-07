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

## Update/version strategy
- Static GTFS has feed metadata/versioning where available; refresh weekly or when feed changes.
- Provider capability checks may detect schema/feed changes.
- Offline snapshots include fetchedAt, feedVersion if known, and expiry policy.

## Degradation
- No realtime: show static network and last-known timestamps.
- No weather provider: hide radar layer, keep transit functional.
- No routing/network: allow saved trips/favorites only; explain routing unavailable.
