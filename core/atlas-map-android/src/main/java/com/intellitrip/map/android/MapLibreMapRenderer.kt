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
    private val onCameraMoved: (lat: Double, lon: Double, zoom: Double) -> Unit = { _, _, _ -> },
) : MapRenderer {

    private var map: MapLibreMap? = null
    private var layersAttached = false

    // getMapAsync is asynchronous, but callers legitimately want to configure the
    // renderer the moment they construct it. Anything requested before the map
    // exists is queued here and drained once it arrives; previously these calls
    // hit `map ?: return` and were silently dropped, leaving a blank map.
    private var pendingStyle: BasemapStyle? = null
    private var pendingCamera: Triple<Double, Double, Double>? = null

    private var lastReportedLat = Double.NaN
    private var lastReportedLon = Double.NaN
    private var lastReportedZoom = Double.NaN

    init {
        MapLibre.getInstance(mapView.context.applicationContext)
        mapView.getMapAsync { mapLibreMap ->
            map = mapLibreMap
            mapLibreMap.addOnCameraMoveListener { reportCameraIfChanged() }
            // Drain in the order the caller originally made the calls, so a basemap
            // is attached before layers are added on top of it.
            pendingStyle?.let { style ->
                pendingStyle = null
                applyBasemap(style)
            }
            pendingCamera?.let { (lat, lon, zoom) ->
                pendingCamera = null
                moveCamera(lat, lon, zoom)
            }
        }
    }

    /**
     * Reports camera changes so the host can derive scope from user gestures.
     * Panning counts, not just zooming: a user dragging the map expects the scope to
     * follow. Sub-degree jitter is filtered so settling gestures do not thrash state.
     */
    private fun reportCameraIfChanged() {
        val position = map?.cameraPosition ?: return
        val target = position.target ?: return
        val lat = target.latitude
        val lon = target.longitude
        val zoom = position.zoom
        val moved = kotlin.math.abs(lat - lastReportedLat) > CAMERA_EPSILON_DEGREES ||
            kotlin.math.abs(lon - lastReportedLon) > CAMERA_EPSILON_DEGREES ||
            kotlin.math.abs(zoom - lastReportedZoom) > CAMERA_EPSILON_ZOOM
        if (!moved) return
        lastReportedLat = lat
        lastReportedLon = lon
        lastReportedZoom = zoom
        onCameraMoved(lat, lon, zoom)
    }

    val mapViewRef: MapView get() = mapView

    /**
     * True once sources and layers exist. Callers must wait for [onReady] before
     * pushing data, otherwise updates are discarded because no source exists yet.
     */
    val isReady: Boolean get() = map != null && layersAttached

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
        val target = map
        if (target == null) {
            pendingCamera = Triple(lat, lon, zoom)
            return
        }
        target.cameraPosition = CameraPosition.Builder()
            .target(LatLng(lat, lon))
            .zoom(zoom)
            .build()
    }

    override fun applyBasemap(style: BasemapStyle) {
        val target = map
        if (target == null) {
            // Queued, not dropped: the map is still being created.
            pendingStyle = style
            return
        }
        val styleUri = style.styleUrl
        if (styleUri != null) {
            target.setStyle(styleUri) { onStyleLoaded() }
        } else {
            onStyleLoaded()
        }
    }

    /**
     * Runs once the style is usable. `setStyle` replaces the style object, so any
     * previously attached sources and layers are gone; they are re-added here and
     * [onReady] fires afterwards so callers can push data only once layers exist.
     */
    private fun onStyleLoaded() {
        layersAttached = attachLayers()
        if (!layersAttached) return
        onReady(this)
    }

    override fun render(snapshot: RenderSnapshot) {
        val target = map ?: return
        if (!layersAttached) return
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

    /**
     * Planned journeys, drawn above the network in their own bulk source so the
     * layer can be styled without touching the feed layers.
     */
    fun applyRoutes(geoJson: String) {
        val target = map ?: return
        target.getStyle { style ->
            val source = style.getSourceAs<GeoJsonSource>(ROUTES_SOURCE)
                ?: GeoJsonSource(ROUTES_SOURCE).also { style.addSource(it) }
            source.setGeoJson(geoJson)
            if (style.getLayer(ROUTES_LAYER) == null) {
                val layer = LineLayer(ROUTES_LAYER, ROUTES_SOURCE).withProperties(
PropertyFactory.lineColor("#DC2626"),
                    PropertyFactory.lineWidth(5f),
                    PropertyFactory.lineOpacity(0.85f),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                    PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                )
                // Above everything so a plan reads clearly over the network.
                style.addLayer(layer)
            }
        }
    }

    fun clearRoutes() {
        val target = map ?: return
        target.getStyle { style ->
            style.removeLayer(ROUTES_LAYER)
            style.removeSource(ROUTES_SOURCE)
        }
    }

    /**
     * The user's own position: an accuracy halo showing how much the fix can be
     * trusted, with a solid dot inside it. Rendered as its own source so it is not
     * culled with the transit network — the user is not a vehicle.
     */
    fun applyUserLocation(geoJson: String?) {
        val target = map ?: return
        target.getStyle { style ->
            val source = style.getSourceAs<GeoJsonSource>(USER_LOCATION_SOURCE)
                ?: GeoJsonSource(USER_LOCATION_SOURCE).also { style.addSource(it) }
            if (geoJson == null) {
                source.setGeoJson(EMPTY_COLLECTION)
            } else {
                source.setGeoJson(geoJson)
                if (style.getLayer(USER_LOCATION_HALO_LAYER) == null) {
                    // Halo first so the dot draws on top of it.
                    style.addLayer(
                        CircleLayer(USER_LOCATION_HALO_LAYER, USER_LOCATION_SOURCE).withProperties(
                            PropertyFactory.circleRadius(
                                // Accuracy is a data-driven radius: expression, not a
                                // fixed value, so a 30 m fix is visibly smaller than a
                                // 500 m one.
                                Expression.interpolate(
                                    Expression.linear(),
                                    Expression.toNumber(Expression.get("accuracy")),
                                    Expression.stop(0.0, 4.0),
                                    Expression.stop(500.0, 64.0),
                                )
                            ),
                            PropertyFactory.circleColor(Expression.literal("#1D4ED8")),
                            PropertyFactory.circleOpacity(0.15f),
                        )
                    )
                    style.addLayer(
                        CircleLayer(USER_LOCATION_DOT_LAYER, USER_LOCATION_SOURCE).withProperties(
                            PropertyFactory.circleRadius(7f),
                            // White core with a blue ring reads clearly over both the
                            // pale basemap and the darker vehicle clusters.
                            PropertyFactory.circleColor(Expression.literal("#FFFFFF")),
                            PropertyFactory.circleStrokeWidth(3f),
                            PropertyFactory.circleStrokeColor(Expression.literal("#1D4ED8")),
                        )
                    )
                }
            }
        }
    }

    fun clearUserLocation() = applyUserLocation(null)

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
                        PropertyFactory.circleColor(Expression.literal("#DC2626")),
                        PropertyFactory.circleOpacity(0.9f),
                        PropertyFactory.circleStrokeWidth(2f),
                        PropertyFactory.circleStrokeColor(Expression.literal("#FFFFFF")),
                    )
                )
            }
        }
    }

    override fun destroy() {
        mapView.onDestroy()
    }

    /**
     * Adds every source and layer, in stacking order. Safe to call again after a style
     * change: sources are only added when missing, and layers are re-added against the
     * new style object. Returns false when the style is not ready yet.
     */
    private fun attachLayers(): Boolean {
        val style = map?.style ?: return false
        listOf(VEHICLES_SOURCE, CLUSTERS_SOURCE, STOPS_SOURCE, SHAPES_SOURCE, ROUTES_SOURCE).forEach { name ->
            if (style.getSource(name) == null) style.addSource(GeoJsonSource(name))
        }
        // Remove-then-add so a restyled map does not retain layers bound to the old style.
        listOf(SHAPES_LAYER, STOPS_LAYER, CLUSTERS_LAYER, VEHICLES_LAYER, ROUTES_LAYER).forEach {
            if (style.getLayer(it) != null) style.removeLayer(it)
        }

        style.addLayer(
            LineLayer(SHAPES_LAYER, SHAPES_SOURCE).withProperties(
                PropertyFactory.lineColor("#0B5FA5"),
                PropertyFactory.lineWidth(2f),
                PropertyFactory.lineOpacity(0.7f),
            )
        )
        style.addLayer(
            CircleLayer(STOPS_LAYER, STOPS_SOURCE).withProperties(
                PropertyFactory.circleRadius(3f),
                PropertyFactory.circleColor(Expression.literal("#444444")),
                PropertyFactory.circleOpacity(0.6f),
            )
        )
        style.addLayer(
            CircleLayer(CLUSTERS_LAYER, CLUSTERS_SOURCE).withProperties(
                PropertyFactory.circleRadius(
                    // Singleton cells render at the 10px base size.
                    Expression.step(
                        Expression.toNumber(Expression.get("count")),
                        Expression.literal(10.0),
                        Expression.stop(2.0, 14.0),
                        Expression.stop(10.0, 18.0),
                        Expression.stop(50.0, 24.0),
                        Expression.stop(200.0, 30.0),
                    )
                ),
                // Plain string literals, not Expression.get: a colour is not a
                // feature property, and wrapping one in `get` silently yields the
                // default black paint.
                PropertyFactory.circleColor("#1D4ED8"),
                PropertyFactory.circleOpacity(0.85f),
                PropertyFactory.circleStrokeWidth(1.5f),
                PropertyFactory.circleStrokeColor("#FFFFFF"),
            )
        )
        style.addLayer(
            CircleLayer(VEHICLES_LAYER, VEHICLES_SOURCE).withProperties(
                PropertyFactory.circleRadius(5f),
                PropertyFactory.circleColor(
                    // `match`, not `step`: `stale` is a boolean and step compares
                    // numbers. Colours must be `literal` — `get` looks up a feature
                    // property, so `get("#0078D4")` finds nothing and paints black.
                    Expression.match(
                        Expression.get("stale"),
                        Expression.literal("#0078D4"),
                        Expression.stop(Expression.literal(true), Expression.literal("#9CA3AF")),
                    )
                ),
                PropertyFactory.circleStrokeWidth(1.5f),
                PropertyFactory.circleStrokeColor(Expression.literal("#FFFFFF")),
            )
        )
        // Planned journeys sit above everything so a route reads clearly over the network.
        style.addLayer(
            LineLayer(ROUTES_LAYER, ROUTES_SOURCE).withProperties(
                PropertyFactory.lineColor(Expression.literal("#DC2626")),
                PropertyFactory.lineWidth(5f),
                PropertyFactory.lineOpacity(0.85f),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            )
        )
        return true
    }

    private fun updateSource(source: GeoJsonSource?, geoJson: String) {
        source?.setGeoJson(geoJson)
    }

    companion object {
        /** Roughly a metre; below this a pan is treated as camera jitter. */
        private const val CAMERA_EPSILON_DEGREES = 0.00001

        /** Sub-pixel zoom noise; prevents scope churn while the map settles. */
        private const val CAMERA_EPSILON_ZOOM = 0.01
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
        const val ROUTES_SOURCE = "intellitrip-routes"
        const val ROUTES_LAYER = "intellitrip-route-layer"
        const val USER_LOCATION_SOURCE = "intellitrip-user-location"
        const val USER_LOCATION_HALO_LAYER = "intellitrip-user-location-halo"
        const val USER_LOCATION_DOT_LAYER = "intellitrip-user-location-dot"

        /** Valid empty FeatureCollection; `null` GeoJSON would throw in setGeoJson. */
        private const val EMPTY_COLLECTION = """{"type":"FeatureCollection","features":[]}"""
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
