# ADR-009: Realtime polling
Status: Provisional
Context: Metro Transit GTFS-RT refreshes about every 5 seconds; no push websocket is assumed.
Decision: Poll GTFS-RT with conservative cadence/backoff; revisit if provider adds push.
