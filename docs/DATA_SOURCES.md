# Data Sources (audited starting points)

## Transit — Metro Transit
- Service/data feeds: https://svc.metrotransit.org/
- NexTrip Swagger: https://svc.metrotransit.org/swagger/index.html?
- Static GTFS: https://svc.metrotransit.org/mtgtfs/gtfs.zip
- GTFS-Flex: https://svc.metrotransit.org/mtgtfs/gtfs-flex.zip
- GTFS-RT vehicle positions: https://svc.metrotransit.org/mtgtfs/vehiclepositions.pb
- GTFS-RT trip updates: https://svc.metrotransit.org/mtgtfs/tripupdates.pb
- GTFS-RT alerts: https://svc.metrotransit.org/mtgtfs/alerts.pb
- GTFS extensions: https://www.metrotransit.org/resources/gtfs-extensions/
- Public developer info: https://www.metrotransit.org/resources/apps/
- `FeedId` for this feed is a stable logical namespace (e.g. `metro-transit-regional`); it must not be derived from ZIP hash, feed_version, or download date (ADR-011).
- GTFS-Realtime feeds must be explicitly associated with the corresponding static `FeedId`; `FeedEntity.id` is not interchangeable with static `FeedId`, and `VehicleDescriptor.id` may be absent (ADR-011, Phase 02 requirement).
- Static GTFS acquisition uses conditional refresh (`If-Modified-Since`/`If-None-Match`, 304 keep, 200 validate then atomic activation); acquisition is explicit and never runs at app startup.

## Routing
- OpenTripPlanner: https://www.opentripplanner.org/ · https://github.com/opentripplanner/OpenTripPlanner
- Topology unresolved: Android app → `RoutingProvider` → self-hosted OTP service / trusted public service / local offline engine / hybrid. Affects privacy, sovereignty, network dependency, hosting cost, offline, latency, reliability. Research gate before routing implementation (ADR-007). Routing remains core.

## Static GTFS refresh
- Daily lightweight conditional check with `If-Modified-Since` / `Last-Modified`.
- `304` keeps current snapshot; `200` triggers download, validation, then atomic swap (see OFFLINE_MODEL.md).

## Weather / Radar
- NWS Web API: https://www.weather.gov/documentation/services-web-api
- NWS requires clients to send an identifying `User-Agent` (app name + contact); adapter-configurable, not a fabricated identity.
- NWS radar FAQ/GIS: https://www.weather.gov/radarfaq/ · https://www.weather.gov/gis/cloudgiswebservices
- NOAA MRMS data: https://www.nssl.noaa.gov/projects/mrms/MRMS_data.php
- MRMS QPE ImageServer: https://mapservices.weather.noaa.gov/raster/rest/services/obs/mrms_qpe/ImageServer
- NOAA radar base reflectivity services: https://mapservices.weather.noaa.gov/eventdriven/rest/services/radar/radar_base_reflectivity/MapServer
- IEM NEXRAD composites: https://mesonet.agron.iastate.edu/docs/nexrad_composites/
- IEM RADAR mapserver: https://mesonet.agron.iastate.edu/docs/radmapserver/?
- RainViewer weather maps API: https://www.rainviewer.com/api/weather-maps-api.html
  - Status: PROVISIONAL / RESEARCH-GATED (terms, limits, suitability, licensing unconfirmed — see ADR-003). Viable for prototype/MVP adapter only, not an unquestioned production dependency.

## Basemap / tiles
- CARTO: https://www.carto.com/
- MapLibre: https://github.com/maplibre/maplibre-native

## Traffic (future)
- MnDOT freeway sensors/traffic data: research official developer API before implementation.

## Auth finding
Metro Transit GTFS/GTFS-RT/NexTrip endpoints are currently usable without provisioning a key; provider contracts must still support authentication for future providers.
