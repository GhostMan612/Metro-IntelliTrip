# Offline basemap licensing and format

The app does not bundle, host, or redistribute any basemap archive or style. A
rider or distributor imports both files from local storage. That keeps the
repository free of large binaries and avoids shipping a tile licence the project
has not cleared.

## Format

- **Accepted:** PMTiles v3 (`.pmtiles`) or Android-host-validated MBTiles
  (`.mbtiles`), each with a MapLibre style JSON. MBTiles is served by the Android
  host over loopback; the platform-neutral store requires the host's validated
  metadata.
- **Rejected:** unknown formats. To convert MBTiles to PMTiles, use the
  official tool:

  ```
  pmtiles convert metro.mbtiles metro.pmtiles
  ```

  PMTiles remains the portable store's default format; Android accepts
  MBTiles only after SQLite schema, metadata, zoom/bounds, and tile data checks.

The store validates the PMTiles header before installation: magic and version,
u64 offsets/counts, non-empty root/tile sections, compression and tile type, zoom
range, and ordered bounds. An all-zero bounds value is treated as world coverage,
never as a guessed location.

## Style requirements

- JSON object with `"version": 8`
- a `"sources"` object and a `"layers"` array
- at least one source URL beginning with the selected format's `pmtiles://` or
  `mbtiles://` marker

The style controls labels, colours, fonts, and attribution. On installation the
store rewrites `pmtiles://` URLs to the installed archive's `file://` URI and
Android binds `mbtiles://` URLs to the host's loopback TileJSON endpoint. MBTiles
styles with remote HTTP(S) `url`, `tiles`, `sprite`, or `glyphs` resources are
rejected. This validation is not a full MapLibre style-schema validator and
runtime no-network behavior has not been proven; do not treat source checks as
offline-rendering evidence.

## Licence and attribution

The archive and style remain the importer's responsibility:

- OpenStreetMap-derived data requires OSM attribution. Keep the attribution
  string in the style or in the app's map attribution control.
- Protomaps publishes free PMTiles builds; check its current terms before
  redistributing an archive.
- Commercial providers (MapTiler, Mapbox, Stadia) require an account and usually a
  billing relationship. Their terms generally prohibit redistributing raw tiles;
  importing a personal archive does not grant redistribution rights.
- Transit data attribution (Metro Transit) is separate from basemap attribution
  and must remain visible.

This project cannot audit an imported archive's provenance. If you distribute a
build with a preloaded or linked basemap, you are the distributor for that data
and must clear the licence yourself.

## Limits and safety

- Archive: up to 2 GiB.
- Style: up to 2 MiB.
- Installation is atomic at the manifest commit; a failed import leaves the
  previous package active.
- Removing the active package returns the map to the online CARTO style on the
  next style application.
- The archive is stored in app-private storage. Other apps cannot read it, and
  uninstalling the app removes it.
