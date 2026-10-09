# ADR-011: GTFS feed identity and service time
Status: Accepted
Context: Static GTFS entities (`routes.txt`, `trips.txt`, `stops.txt`, `calendar.txt`, `shapes.txt`, `stop_times.txt`) are unique per feed, not per agency. A feed may describe one or more agencies, multiple agencies may serve the same stop, and GTFS-RT payloads are associated with a static feed. The prior identity model used `AgencyId` as the namespace for `RouteKey`/`TripKey`/`StopKey`/`VehicleKey`, which cannot represent shared stops, one-agency feeds without an `agency_id`, or realtime entities whose feed association is not agency-derived. GTFS service times are offsets from the start of the service day and routinely exceed `24:00:00`.

Decision:
- Static GTFS entity keys are feed-scoped: `RouteKey(feedId, routeId)`, `TripKey(feedId, tripId)`, `StopKey(feedId, stopId)`, `VehicleKey(feedId, vehicleId)`, `ServiceKey(feedId, serviceId)`, `ShapeKey(feedId, shapeId)`.
- `AgencyId` remains a separate Atlas-normalized agency identity. It is not a namespace for static entity keys and is not assumed to equal raw GTFS `agency_id`. Adapters synthesize it from the feed and raw agency identifiers; agencies reference feed-scoped stops, so a stop served by multiple agencies has exactly one identity.
- `FeedId` is the stable logical feed namespace. It does not change across feed versions, hashes, or validity windows, so keys issued for one snapshot remain valid for the next.
- `GtfsServiceTime(secondsSinceServiceDayStart: Int)` represents GTFS `arrival_time`/`departure_time` and frequency start/end times. It is nonnegative, parsed strictly as `HH:MM:SS` or `H:MM:SS`, rejects malformed values, preserves times beyond 24 hours without wrapping (`25:10:00` stays `25:10:00`), and formats deterministically back to canonical `HH:MM:SS`.
- Static/realtime feed association is explicit: realtime entities (vehicle positions, trip updates, alerts) resolve to `VehicleKey`/`TripKey`/`RouteKey` within the `FeedId` namespace of the static feed they are associated with. Association is declared in feed/provider metadata and is not inferred from agency.
- DST limitation: a `GtfsServiceTime` is service-day wall-clock, not a UTC instant. Converting it to an instant requires the service date and the agency timezone; on DST transition days such local times may be ambiguous or nonexistent, and consumers must resolve them using GTFS service-day semantics rather than re-deriving a time from a wrapped clock.

Consequences:
- All authoritative docs and code use feed-scoped keys for static entities; `AgencyId` is used only as agency identity/filtering.
- Phase 02 realtime adapters must resolve the associated `FeedId` first, then map entities into feed-scoped keys.
- Phase 01 static parsing/normalization lives in a pure Kotlin/JVM module (`core/atlas-gtfs-static`), keeping the quarantined `core/atlas-transit` prototype untouched.
