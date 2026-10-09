package com.intellitrip.map.android

import android.content.Context
import com.intellitrip.map.BasemapStyle
import com.intellitrip.map.GeoJsonWriter
import com.intellitrip.map.MapRenderer
import com.intellitrip.map.RenderSnapshot
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.sources.RasterSource

/**
 * MapLibre-backed renderer.
 *
 * All transit features are pushed as bulk GeoJSON source updates. The renderer
 * never creates an Android view or Compose object per vehicle, which keeps
 * system-wide rendering viable.
 */
class MapLibreMapRenderer(
    private val mapView: MapView,
    private val onReady: (MapLibreMapRenderer) -> Unit = {},
) : MapRenderer {

    private var map: MapLibreMap? = null
    private var styleApplied = false

    init {
        MapLibre.getInstance(mapView.context.applicationContext)
        mapView.getMapAsync { mapLibreMap ->
            map = mapLibreMap
            if (!styleApplied) onReady(this)
        }
    }

    val mapViewRef: MapView get() = mapView

    fun camera(): CameraViewCompat? {
        val position = map?.cameraPosition ?: return null
        val bounds = map?.projection?.getVisibleRegion()?.latLngBounds ?: return null
        return CameraViewCompat(
            southWestLat = bounds.getLatSouth(),
            southWestLon = bounds.getLonWest(),
            northEastLat = bounds.getLatNorth(),
            northEastLon = bounds.getLonEast(),
            zoom = position.zoom,
        )
    }

    fun moveCamera(lat: Double, lon: Double, zoom: Double) {
        map?.cameraPosition = CameraPosition.Builder()
            .target(LatLng(lat, lon))
            .zoom(zoom)
            .build()
    }

    override fun applyBasemap(style: BasemapStyle) {
        styleApplied = true
        val target = map ?: return
        val styleUri = style.styleUrl
        if (styleUri != null) {
            target.setStyle(styleUri) { attachLayers() }
        } else {
            attachLayers()
        }
    }

    override fun render(snapshot: RenderSnapshot) {
        val target = map ?: return
        target.getStyle { style ->
            updateSource(style.getSourceAs<GeoJsonSource>(VEHICLES_SOURCE), GeoJsonWriter.vehicles(snapshot))
            updateSource(style.getSourceAs<GeoJsonSource>(CLUSTERS_SOURCE), GeoJsonWriter.clusters(snapshot))
            updateSource(style.getSourceAs<GeoJsonSource>(STOPS_SOURCE), GeoJsonWriter.stops(snapshot))
            updateSource(style.getSourceAs<GeoJsonSource>(SHAPES_SOURCE), GeoJsonWriter.shapes(snapshot))
        }
    }

    /**
     * Radar imagery is a raster tile layer rather than a feature layer, so it is
     * applied separately and sits below the transit layers.
     */
    fun applyRadar(tileUrlTemplate: String, opacity: Float) {
        val target = map ?: return
        target.getStyle { style ->
            // The raster tile URL is fixed when the source is created; switching radar
// frames recreates the source so the latest frame is used.
            style.removeLayer(RADAR_LAYER)
            style.removeSource(RADAR_SOURCE)
            style.addSource(RasterSource(RADAR_SOURCE, tileUrlTemplate, 256))
            val layer = RasterLayer(RADAR_LAYER, RADAR_SOURCE)
            layer.setProperties(
                PropertyFactory.rasterOpacity(opacity),
                PropertyFactory.rasterFadeDuration(200f),
            )
            style.addLayerAbove(layer, SHAPES_LAYER)
        }
    }

    fun clearWeatherLayers() {
        val target = map ?: return
        target.getStyle { style ->
            style.removeLayer(RADAR_LAYER)
            style.removeLayer(ALERTS_LAYER)
            style.removeSource(RADAR_SOURCE)
            style.removeSource(ALERTS_SOURCE)
        }
    }

    /** Active weather alerts rendered as a bulk point source. */
    fun applyAlerts(geoJson: String) {
        val target = map ?: return
        target.getStyle { style ->
            val source = style.getSourceAs<GeoJsonSource>(ALERTS_SOURCE)
                ?: GeoJsonSource(ALERTS_SOURCE).also { style.addSource(it) }
            source.setGeoJson(geoJson)
            if (style.getLayer(ALERTS_LAYER) == null) {
                style.addLayer(
                    CircleLayer(ALERTS_LAYER, ALERTS_SOURCE).withProperties(
                        PropertyFactory.circleRadius(7f),
                        PropertyFactory.circleColor(Expression.get("#DC2626")),
                        PropertyFactory.circleOpacity(0.9f),
                        PropertyFactory.circleStrokeWidth(2f),
                        PropertyFactory.circleStrokeColor(Expression.get("#FFFFFF")),
                    )
                )
            }
        }
    }

    override fun destroy() {
        mapView.onDestroy()
    }

    private fun attachLayers() {
        val style = map?.style ?: return
        if (style.getSource(VEHICLES_SOURCE) != null) return
        style.addSource(GeoJsonSource(VEHICLES_SOURCE))
        style.addSource(GeoJsonSource(CLUSTERS_SOURCE))
        style.addSource(GeoJsonSource(STOPS_SOURCE))
        style.addSource(GeoJsonSource(SHAPES_SOURCE))

        style.addLayer(
            LineLayer(SHAPES_LAYER, SHAPES_SOURCE).withProperties(
                PropertyFactory.lineColor(Expression.get("#0B5FA5")),
                PropertyFactory.lineWidth(2f),
                PropertyFactory.lineOpacity(0.7f),
            )
        )
        style.addLayer(
            CircleLayer(STOPS_LAYER, STOPS_SOURCE).withProperties(
                PropertyFactory.circleRadius(3f),
                PropertyFactory.circleColor(Expression.get("#444444")),
                PropertyFactory.circleOpacity(0.6f),
            )
        )
        style.addLayer(
            CircleLayer(CLUSTERS_LAYER, CLUSTERS_SOURCE).withProperties(
                PropertyFactory.circleRadius(
                    Expression.step(
                        Expression.get("count"),
                        Expression.literal(10.0),
                        Expression.stop(10.0, 14.0),
                        Expression.stop(50.0, 20.0),
                        Expression.stop(200.0, 28.0),
                    )
                ),
                PropertyFactory.circleColor(Expression.get("#1D4ED8")),
                PropertyFactory.circleOpacity(0.8f),
            )
        )
        style.addLayer(
            CircleLayer(VEHICLES_LAYER, VEHICLES_SOURCE).withProperties(
                PropertyFactory.circleRadius(5f),
                PropertyFactory.circleColor(
                    Expression.step(
                        Expression.get("stale"),
                        Expression.get("#0078D4"),
                        Expression.stop(Expression.literal(true), Expression.get("#9CA3AF"))
                    )
                ),
                PropertyFactory.circleStrokeWidth(1.5f),
                PropertyFactory.circleStrokeColor(Expression.get("#FFFFFF")),
            )
        )
    }

    private fun updateSource(source: GeoJsonSource?, geoJson: String) {
        source?.setGeoJson(geoJson)
    }

    companion object {
        const val VEHICLES_SOURCE = "intellitrip-vehicles"
        const val CLUSTERS_SOURCE = "intellitrip-clusters"
        const val STOPS_SOURCE = "intellitrip-stops"
        const val SHAPES_SOURCE = "intellitrip-shapes"
        const val VEHICLES_LAYER = "intellitrip-vehicle-layer"
        const val CLUSTERS_LAYER = "intellitrip-cluster-layer"
        const val STOPS_LAYER = "intellitrip-stop-layer"
        const val SHAPES_LAYER = "intellitrip-shape-layer"
        const val RADAR_SOURCE = "intellitrip-radar"
        const val RADAR_LAYER = "intellitrip-radar-layer"
        const val ALERTS_SOURCE = "intellitrip-weather-alerts"
        const val ALERTS_LAYER = "intellitrip-weather-alert-layer"
    }
}

data class CameraViewCompat(
    val southWestLat: Double,
    val southWestLon: Double,
    val northEastLat: Double,
    val northEastLon: Double,
    val zoom: Double,
)

/** Convenience factory used by the app shell. */
fun createMapView(context: Context): MapView = MapView(context)
