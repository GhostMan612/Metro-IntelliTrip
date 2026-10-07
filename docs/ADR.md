# Architecture Decision Records

## ADR-001: Native Android (Kotlin/Compose) over Flutter
Status: Accepted (v1)
Context: MapLibre support and background location are first-class on Android.
Decision: Native Kotlin + Compose.
Revisit if: iOS becomes a hard requirement.

## ADR-002: MapLibre over Mapbox/Google
Status: Accepted
Context: Sovereignty, CARTO compatibility, no vendor token fees.
Decision: MapLibre GL Native; wrap behind `AtlasMapEngine` so swapping is local.

## ADR-003: RainViewer for radar MVP
Status: Accepted (Phase 2)
Context: Free, tile-based, fast. MRMS/IEM as upgrade path via `WeatherSource` interface.

## ADR-004: Engine modularity
Status: Accepted
Decision: transit/weather/map clients are separate Gradle modules; domain layer has zero Android deps, so it ports to the Recovery-for-All SDK.

## ADR-006: Metro Transit data auth
Status: Accepted
Context: GTFS, GTFS-RT and NexTrip REST (routes, directions, stops, shape, vehicles) are publicly accessible without a developer API key (verified 2026-10-06).
Decision: v1 ships keyless; provider interfaces must still support auth for future agencies.

## ADR-005: GTFS-RT polling, no push
Status: Provisional
Decision: Poll every 15s; backoff on failure. Websocket/SSE reconsider only if Metro Transit adds it.
