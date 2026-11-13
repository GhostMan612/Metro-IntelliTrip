# Offline basemap licensing and format

The app does not bundle, host, or redistribute any basemap archive or style. A
rider or distributor imports both files from local storage. That keeps the
repository free of large binaries and avoids shipping a tile licence the project
has not cleared.

## Format

- **Accepted:** PMTiles v3 (`.pmtiles`) plus a MapLibre style JSON.
- **Rejected:** MBTiles/SQLite and unknown formats. Convert first with the
  official tool:

  ```
  pmtiles convert metro.mbtiles metro.pmtiles
  ```

  The app shows this command in the import error instead of pretending an MBTiles
  file is installable.

The store validates the PMTiles header before installation: magic and version,
u64 offsets/counts, non-empty root/tile sections, compression and tile type, zoom
range, and ordered bounds. An all-zero bounds value is treated as world coverage,
never as a guessed location.

## Style requirements

- JSON object with `"version": 8`
- a `"sources"` object and a `"layers"` array
- at least one source URL beginning with `pmtiles://`

The style controls labels, colours, fonts, and attribution. On installation the
store rewrites every `pmtiles://` URL to the installed archive's `file://` URI, so
the style cannot fetch a different remote archive behind the rider's back. Fonts
and sprites referenced by URL are not bundled; use an offline style whose glyph
and sprite references are available, or accept that those resources may be
missing offline.

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
