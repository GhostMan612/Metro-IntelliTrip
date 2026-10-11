# ADR-003: Radar imagery source
Status: Accepted (resolved for the free, key-free constraint)

Context: The app needs a radar overlay for precipitation. The choice is constrained
by "must be free and must not require a signup or API key", because a key-gated
dependency cannot be exercised by a contributor or a fresh clone.

## Decision

**RainViewer public API is the primary radar source**, with attribution displayed
in the app, and IEM/NWS as a key-free fallback behind the same `RadarProvider`
contract.

## Rationale

- RainViewer's public weather-maps endpoint requires no API key and no account. It
  returns a frame index plus nowcast, which MapLibre consumes directly as a raster
  source with no tiling pipeline of our own.
- The cost is attribution: "Radar data by RainViewer" must remain visible wherever
  radar is shown. `RadarFrame.attribution` carries it and the UI renders it.
- Usage limits exist and are not contractually guaranteed. This is a real
  dependency risk, mitigated by keeping the provider swappable rather than by
  pretending the risk is absent.

### Rejected

- **Commercial tile/weather vendors** (MapTiler, Meteomatics, Tomorrow.io): require
  signups, API keys, and per-request billing. Fails the constraint outright.
- **Self-hosted radar mosaic**: would need MRMS ingest and a rendering pipeline. Not
  proportionate for this app; the same constraint is already addressed by
  `OfflineBasemapStore`, where self-hosting is a documented option.

## Consequences

- No signup is required to run the app's weather features.
- RainViewer is provisional in the sense that its limits are not guaranteed, not in
  the sense that its terms are unknown. Attribution is mandatory.
- Swapping providers requires no domain change: `RadarProvider` is unchanged.

## Supersedes

The PROVISIONAL / RESEARCH-GATED status carried in earlier revisions. That status
blocked progress on an open question that has now been answered by constraining the
requirement to "free and key-free", which eliminates the licensed-vendor options
entirely and leaves one viable primary source.