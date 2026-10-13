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
    private val onMapTapped: (lat: Double, lon: Double) -> Unit = { _, _ -> },
) : MapRenderer {

    private var map: MapLibreMap? = null
    private var layersAttached = false

    // getMapAsync is asynchronous, but callers legitimately want to configure the
    // renderer the moment they construct it. Anything requested before the map
    // exists is queued here and drained once it arrives; previously these calls
    // hit `map ?: return` and were silently dropped, leaving a blank map.
    private var pendingStyle: BasemapStyle? = null
    private var pendingCamera: Triple<Double, Double, Double>? = null

    /**
     * Where this renderer last asked the camera to go, or null.
     *
     * While set, camera-move reports matching this destination are treated as the
     * echo of our own request rather than as a rider gesture.
     */
    private var programmaticTarget: Triple<Double, Double, Double>? = null

    /** Guards against attaching a second click listener on a reused map. */
    private var tapHandlerAttached = false

    private var lastReportedLat = Double.NaN
    private var lastReportedLon = Double.NaN
    private var lastReportedZoom = Double.NaN

    init {
        MapLibre.getInstance(mapView.context.applicationContext)
        mapView.getMapAsync { mapLibreMap ->
            map = mapLibreMap
            attachTapHandler(mapLibreMap)
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
     *
     * Movement this renderer asked for is suppressed. Assigning `cameraPosition`
     * fires the camera-move listener exactly like a gesture, so without this the app
     * treats its own framing as a user pan, publishes a viewport, and the scope
     * engine hands that viewport straight back as a new camera target. Skipping
     * only the first automatic update was not enough, because any later programmatic
     * move re-entered the same loop. Suppression is keyed on the destination
     * instead, so it holds until the map actually arrives where it was told to go.
     */
    private fun reportCameraIfChanged() {
        val position = map?.cameraPosition ?: return
        val target = position.target ?: return
        val lat = target.latitude
        val lon = target.longitude
        val zoom = position.zoom

        programmaticTarget?.let { (wantedLat, wantedLon, wantedZoom) ->
            val arrived = kotlin.math.abs(lat - wantedLat) <= CAMERA_EPSILON_DEGREES &&
                kotlin.math.abs(lon - wantedLon) <= CAMERA_EPSILON_DEGREES &&
                kotlin.math.abs(zoom - wantedZoom) <= CAMERA_EPSILON_ZOOM
            if (arrived) {
                programmaticTarget = null
                // Consume the update and adopt it as the baseline, so the delta
                // filter below cannot fire for this same movement.
                lastReportedLat = lat
                lastReportedLon = lon
                lastReportedZoom = zoom
                return
            }
        }

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
        // Recorded before the assignment: assigning cameraPosition can deliver the
        // camera-move callback synchronously, and the echo must already be identifiable
        // as ours.
        programmaticTarget = Triple(lat, lon, zoom)
        target.cameraPosition = CameraPosition.Builder()
            .target(LatLng(lat, lon))
            .zoom(zoom)
            .build()
    }

    /**
     * Drops any outstanding programmatic-move suppression.
     *
     * Call after a real gesture so a pending target cannot swallow the rider's own
     * pan if it lands within the arrival epsilon of the last programmatic move.
     */
    fun clearProgrammaticCameraTarget() {
        programmaticTarget = null
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
                            // pale basemap and the darker vehicle clusters. A stale fix
                            // goes hollow, so "last known" cannot be mistaken for live.
                            PropertyFactory.circleColor(
                                Expression.match(
                                    Expression.get("fresh"),
                                    Expression.literal("#FFFFFF"),
                                    Expression.stop(
                                        Expression.literal(false),
                                        Expression.literal("#FFFFFF00"),
                                    ),
                                )
                            ),
                            PropertyFactory.circleStrokeWidth(3f),
                            // A hollow dot needs an opaque ring to stay visible.
                            PropertyFactory.circleStrokeColor(
                                Expression.match(
                                    Expression.get("fresh"),
                                    Expression.literal("#1D4ED8"),
                                    Expression.stop(
                                        Expression.literal(false),
                                        Expression.literal("#9CA3AF"),
                                    ),
                                )
                            ),
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
                // Plain string literals, not Expression.get: a color is not a
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
                    // numbers. Colors must be `literal` — `get` looks up a feature
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

    /**
     * Queries the rendered features under a screen point.
     *
     * Hit testing runs against what is actually drawn, which is the only way for a tap
     * on a marker to select that marker rather than whatever happens to share its
     * coordinates. Layers are queried in a fixed order that puts the most specific
     * feature first, so the caller can collapse the result without repeating the
     * stacking rules.
     */
    fun queryFeatures(
        lat: Double,
        lon: Double,
        tolerancePx: Float = TAP_TOLERANCE_PX,
    ): List<FeatureProperties> {
        val target = map ?: return emptyList()
        // This MapLibre build exposes only single-point queries, so the tap tolerance is
        // built by sampling a small cross of points rather than one exact coordinate.
        // A finger is far larger than a marker: without the tolerance a rider aiming at
        // a bus on a moving map taps beside it and is told nothing is there.
        val center = target.projection.toScreenLocation(LatLng(lat, lon))
        val radius = tolerancePx.coerceIn(1f, MAX_TAP_RADIUS_PX)
        val samples = listOf(
            center,
            android.graphics.PointF(center.x - radius, center.y),
            android.graphics.PointF(center.x + radius, center.y),
            android.graphics.PointF(center.x, center.y - radius),
            android.graphics.PointF(center.x, center.y + radius),
        )

        val result = mutableListOf<FeatureProperties>()
        val seen = mutableSetOf<String>()
        for (layerId in SELECTABLE_LAYERS) {
            for (point in samples) {
                val features = runCatching {
                    target.queryRenderedFeatures(point, layerId)
                }.getOrNull() ?: continue
                for (feature in features) {
                    val values = toPropertyMap(feature.properties())
                    if (values.isEmpty()) continue
                    // The same feature is returned by several samples; keep one.
                    if (seen.add(values.toString())) {
                        result.add(FeatureProperties(layerId, values))
                    }
                }
            }
        }
        return result
    }

    /**
     * Flattens a feature's properties into a plain map.
     *
     * This MapLibre build hands back a gson `JsonObject`, not a `Map`, so nested values
     * are unwrapped here. The selection rules consume plain scalars, so anything that
     * is not one is dropped rather than stringified: a nested object turned into
     * "com.google.gson.JsonObject@1a2b" would be worse than absent.
     */
    private fun toPropertyMap(source: com.google.gson.JsonObject?): Map<String, Any?> {
        if (source == null) return emptyMap()
        val out = LinkedHashMap<String, Any?>(source.size())
        for ((key, value) in source.entrySet()) {
            when {
                value == null || value.isJsonNull -> out[key] = null
                value.isJsonPrimitive -> {
                    val primitive = value.asJsonPrimitive
                    out[key] = when {
                        primitive.isBoolean -> primitive.asBoolean
                        primitive.isNumber -> primitive.asNumber
                        else -> primitive.asString
                    }
                }

                else -> Unit
            }
        }
        return out
    }

    /**
     * Subscribes to map taps.
     *
     * A tap is only a click if it is not a drag: panning the map ends with the finger
     * lifting over the map, and treating that as a selection would fire a sheet every
     * time the rider adjusted the view.
     */
    private fun attachTapHandler(target: MapLibreMap) {
        if (tapHandlerAttached) return
        tapHandlerAttached = true
        target.addOnMapClickListener { point ->
            onMapTapped(point.latitude, point.longitude)
            true
        }
    }

    /** One queried feature: which layer produced it, and its properties. */
    data class FeatureProperties(val layerId: String, val values: Map<String, Any?>)

    companion object {
        /** Roughly a meter; below this a pan is treated as camera jitter. */
        private const val CAMERA_EPSILON_DEGREES = 0.00001

        /** Sub-pixel zoom noise; prevents scope churn while the map settles. */
        private const val CAMERA_EPSILON_ZOOM = 0.01

        /**
         * How far from a tap, in screen pixels, a feature still counts as touched.
         *
         * Roughly the radius of a fingertip. Sized here rather than at the call site so
         * every selectable feature gets the same forgiving target.
         */
        const val TAP_TOLERANCE_PX = 24f

        /** Ceiling on the sampled hit radius, so a zoomed-out tap cannot select everything. */
        private const val MAX_TAP_RADIUS_PX = 48f

        /**
         * Layers consulted by hit testing, most specific first.
         *
         * Order matters: a planned journey sits above the network, and a vehicle above
         * the stops. Querying in reverse would make every tap near a busy stop select
         * the stop instead of the bus the rider aimed at.
         */
        val SELECTABLE_LAYERS = listOf(ROUTES_LAYER, VEHICLES_LAYER, STOPS_LAYER, CLUSTERS_LAYER)
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
