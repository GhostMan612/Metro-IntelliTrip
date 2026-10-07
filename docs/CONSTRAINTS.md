# Constraints & Non-Negotiables

## Technical
- Android only for v1: Kotlin, Jetpack Compose, minSdk 26.
- Map SDK: MapLibre GL.
- Provider integration must be contract-first; provider-specific types do not leak into core domain.
- Metro Transit GTFS/GTFS-RT/NexTrip are public for v1; authenticated provider support is still required at contract boundaries.
- Realtime feeds may refresh as often as 5s; clients poll conservatively and honor provider limits.
- No secrets, API keys, or tokens in Git.
- No artificial maximum system render radius; scalability comes from scope/render pipeline.

## Product
- One app, no forks.
- Default experience simple; advanced/dev controls behind settings.
- Routing is core, not optional.
- Phase implementation requires blueprint freeze gates.

## Process
- Docs authoritative before code.
- Contradictions are resolved by ADRs before implementation.
- Unresolved research questions stay explicitly open.
