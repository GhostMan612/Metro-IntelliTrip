# Constraints & Non-Negotiables

## Technical
- Platform: Android only, minSdk 26, Kotlin, Jetpack Compose.
- Map SDK: MapLibre GL (free, no token lock-in).
- Basemap: CARTO (API key owned); evaluate offline PMTiles later — do not assume connectivity.
- Weather radar MVP: RainViewer free tiles; MRMS/IEM later — RainViewer's free API is the load-bearing constraint, design an interface so it can be swapped.
- Transit: Metro Transit public GTFS-RT + REST; no API key required for read endpoints — still rate-limit ourselves (poll ≥ 10s).
- No paid tile/vendor dependencies in v1.

## Product
- Clean default UI; advanced mode behind a setting. Never overwhelm the first-run user.
- One app — no forks per user type.

## Process
- Blueprints and ADRs before new features.
- Keep the engine extractable: no Android framework types in domain modules.
