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

## Routing
- OpenTripPlanner: https://www.opentripplanner.org/ · https://github.com/opentripplanner/OpenTripPlanner

## Weather / Radar
- NWS Web API: https://www.weather.gov/documentation/services-web-api
- NWS radar FAQ/GIS: https://www.weather.gov/radarfaq/ · https://www.weather.gov/gis/cloudgiswebservices
- NOAA MRMS data: https://www.nssl.noaa.gov/projects/mrms/MRMS_data.php
- MRMS QPE ImageServer: https://mapservices.weather.noaa.gov/raster/rest/services/obs/mrms_qpe/ImageServer
- NOAA radar base reflectivity services: https://mapservices.weather.noaa.gov/eventdriven/rest/services/radar/radar_base_reflectivity/MapServer
- IEM NEXRAD composites: https://mesonet.agron.iastate.edu/docs/nexrad_composites/
- IEM RADAR mapserver: https://mesonet.agron.iastate.edu/docs/radmapserver/?
- RainViewer weather maps API: https://www.rainviewer.com/api/weather-maps-api.html

## Basemap / tiles
- CARTO: https://www.carto.com/
- MapLibre: https://github.com/maplibre/maplibre-native

## Traffic (future)
- MnDOT freeway sensors/traffic data: research official developer API before implementation.

## Auth finding
Metro Transit GTFS/GTFS-RT/NexTrip endpoints are currently usable without provisioning a key; provider contracts must still support authentication for future providers.
