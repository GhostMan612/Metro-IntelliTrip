package com.intellitrip.weather

import com.intellitrip.contracts.ProviderError
import com.intellitrip.contracts.ProviderResult
import com.intellitrip.contracts.WeatherAlertProvider
import com.intellitrip.domain.AlertSeverity
import com.intellitrip.domain.AlertUrgency
import com.intellitrip.domain.GeoPoint
import com.intellitrip.domain.LatLngBounds
import com.intellitrip.domain.WeatherAlert
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * National Weather Service active-alerts adapter.
 *
 * NWS exposes active alerts either nationally or for a single coordinate. There is no
 * bounding-box search, which is the central problem this adapter has to solve: a rider
 * looking at a viewport needs the warnings intersecting it, and a single point returns
 * only what covers that point.
 *
 * The answer is a bounded sweep of sample points inside the viewport, merged by alert
 * id and then filtered spatially. That is genuinely regional, uses only documented
 * capabilities, and costs a fixed handful of requests rather than a guess about which
 * NWS area codes might apply.
 *
 * NWS also requires clients to identify themselves with a User-Agent containing an
 * application contact. The identity is supplied by the caller; nothing is fabricated
 * here and no personal contact data is committed to the repository.
 */
class NwsWeatherAlertProvider(
    private val http: WeatherHttpClient,
    private val context: WeatherRequestContext,
    private val apiBase: String = "https://api.weather.gov",
    private val pollInterval: Duration = Duration.ofMinutes(5),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    override val providerId: String = "nws",
) : WeatherAlertProvider {

    override fun alerts(bounds: LatLngBounds?): Flow<ProviderResult<List<WeatherAlert>>> = flow {
        val userAgent = runCatching { context.requireUserAgent() }.getOrNull()
        if (userAgent == null) {
            emit(
                ProviderResult.AuthFailure(
                    ProviderError("NWS requires an identifying User-Agent; provider not configured")
                )
            )
            return@flow
        }
        while (currentCoroutineContext().isActive) {
            val result = withContext(ioDispatcher) { fetchAlerts(bounds, userAgent) }
            emit(result)
            delay(pollInterval.toMillis())
        }
    }

    /**
     * Fetches, merges and filters the alerts relevant to a viewport.
     *
     * Each sample point is fetched independently so one failure cannot discard the rest;
     * the national path keeps a single request and a single failure mode.
     */
    private fun fetchAlerts(
        bounds: LatLngBounds?,
        userAgent: String,
    ): ProviderResult<List<WeatherAlert>> {
        if (bounds == null) {
            return try {
                val payload = http.get(alertUrl(null), context.headers()).toString(Charsets.UTF_8)
                ProviderResult.Success(parseAlerts(payload), context.freshness(parseUpdated(payload)))
            } catch (e: IOException) {
                ProviderResult.NetworkFailure(ProviderError("NWS alerts unavailable", e))
            } catch (e: Exception) {
                ProviderResult.MalformedResponse(ProviderError("Unreadable NWS alerts payload", e))
            }
        }

        // Regional sweep. The same alert is returned by every sample it covers, so the
        // merge is keyed on the alert id.
        val samples = samplePoints(bounds)
        val byId = LinkedHashMap<String, WeatherAlert>()
        var updated: Instant? = null
        var failures = 0
        samples.forEach { (lat, lon) ->
            try {
                val payload = http.get(pointUrl(lat, lon), context.headers()).toString(Charsets.UTF_8)
                if (updated == null) updated = parseUpdated(payload)
                parseAlerts(payload).forEach { alert -> byId.putIfAbsent(alert.id, alert) }
            } catch (e: Exception) {
                failures++
            }
        }

        return when {
            // Every sample failed: report the failure rather than an empty alert list,
            // which would read to a rider as "no severe weather anywhere".
            failures >= samples.size ->
                ProviderResult.NetworkFailure(ProviderError("NWS alerts unavailable for this area"))

            else -> {
                // Spatial filter on top of the sweep. Box overlap rather than polygon
                // intersection, and alerts with an unparsable box are kept: for a severe
                // weather warning, showing one slightly out of area beats hiding one that
                // was real.
                ProviderResult.Success(
                    byId.values.filter { it.mayAffect(bounds) },
                    context.freshness(updated),
                )
            }
        }
    }

    internal companion object {
        const val ATTRIBUTION = "National Weather Service"

        /** National active set, used when no viewport is known. */
        internal fun alertUrl(bounds: LatLngBounds?): String = when (bounds) {
            null -> "$apiBase/alerts/active"

            // A viewport query uses its centre as a single representative point. The
            // previous code sent the southwest corner, so a user anywhere but the
            // extreme corner of the map got alerts for the wrong place.
            else -> {
                val centreLat = (bounds.southWest.lat + bounds.northEast.lat) / 2.0
                val centreLon = (bounds.southWest.lon + bounds.northEast.lon) / 2.0
                "$apiBase/alerts/active?point=$centreLat,$centreLon"
            }
        }

        /** URL for one sample point, used by the regional sweep. */
        internal fun pointUrl(lat: Double, lon: Double): String = "$apiBase/alerts/active?point=$lat,$lon"

        /**
         * Sample points covering a viewport, for the regional sweep.
         *
         * The interior is sampled rather than the extremes, because an alert touching
         * only the very edge is mostly off-screen while its request is as expensive as
         * any other.
         *
         * A degenerate viewport collapses to a single point. Returning an empty sample
         * set would mean zero requests and therefore zero alerts, which would read as
         * clear weather when nothing was ever asked.
         */
        internal fun samplePoints(
            bounds: LatLngBounds,
            columns: Int = 3,
            rows: Int = 3,
        ): List<Pair<Double, Double>> {
            val latSpan = bounds.northEast.lat - bounds.southWest.lat
            val lonSpan = bounds.northEast.lon - bounds.southWest.lon
            if (latSpan <= 0.0 || lonSpan <= 0.0) {
                return listOf(
                    (bounds.southWest.lat + bounds.northEast.lat) / 2.0 to
                        (bounds.southWest.lon + bounds.northEast.lon) / 2.0,
                )
            }
            // Inset by an eighth so the outer samples sit inside the viewport rather than
            // on its border, where a sample could resolve to the neighbouring area.
            val latInset = latSpan / 8.0
            val lonInset = lonSpan / 8.0
            val points = mutableListOf<Pair<Double, Double>>()
            for (row in 0 until rows) {
                for (column in 0 until columns) {
                    val lat = bounds.southWest.lat + latInset +
                        (latSpan - 2 * latInset) * row / (rows - 1).coerceAtLeast(1)
                    val lon = bounds.southWest.lon + lonInset +
                        (lonSpan - 2 * lonInset) * column / (columns - 1).coerceAtLeast(1)
                    points.add(lat to lon)
                }
            }
            return points
        }

        internal fun parseUpdated(payload: String): Instant? =
            Json.parse(payload).asObject()["updated"].asStringOrNull()?.let { raw ->
                runCatching { ZonedDateTime.parse(raw, DATE_FORMAT).toInstant() }.getOrNull()
            }

        internal fun parseAlerts(payload: String): List<WeatherAlert> {
            val features = Json.parse(payload).asObject()["features"].asList()
            return features.mapNotNull { feature ->
                val properties = feature.asObject()["properties"].asObject()
                val id = properties["id"].asStringOrNull() ?: return@mapNotNull null
                val event = properties["event"].asStringOrNull() ?: return@mapNotNull null
                WeatherAlert(
                    id = id,
                    providerId = "nws",
                    event = event,
                    headline = properties["headline"].asStringOrNull() ?: event,
                    severity = severity(properties["severity"].asStringOrNull()),
                    urgency = urgency(properties["urgency"].asStringOrNull()),
                    areas = properties["areaDesc"].asStringOrNull()
                        ?.split(",")
                        ?.map { it.trim() }
                        ?.filter { it.isNotEmpty() }
                        ?: emptyList(),
                    effective = parseInstant(properties["effective"].asStringOrNull()),
                    expires = parseInstant(properties["expires"].asStringOrNull()),
                    description = properties["description"].asStringOrNull(),
                    areaBounds = boundsOf(feature.asObject()["geometry"]),
                )
            }
        }

        /**
         * Bounding box of a GeoJSON geometry.
         *
         * Carried on each alert so the viewport filter has something to test against:
         * NWS resolves a `point` query to alerts covering that coordinate, so without
         * this the client cannot tell whether a returned alert is relevant at all.
         *
         * Walks the raw coordinate arrays rather than a geometry library, because this
         * module's `Json` reader is its only JSON dependency. Nested Polygon and
         * MultiPolygon rings are handled by recursing until a coordinate pair is found.
         *
         * Returns null when the geometry is absent or malformed, which callers must read
         * as "unknown", never as "does not apply".
         */
        private fun boundsOf(geometry: Any?): LatLngBounds? {
            val coordinates = geometry.asObject()["coordinates"].asList()
            if (coordinates.isEmpty()) return null
            var minLat = Double.MAX_VALUE
            var minLon = Double.MAX_VALUE
            var maxLat = -Double.MAX_VALUE
            var maxLon = -Double.MAX_VALUE
            var found = false

            fun visit(value: Any?) {
                val list = value.asList()
                if (list.size >= 2) {
                    val lon = (list[0] as? Number)?.toDouble()
                    val lat = (list[1] as? Number)?.toDouble()
                    if (lon != null && lat != null) {
                        found = true
                        if (lat < minLat) minLat = lat
                        if (lat > maxLat) maxLat = lat
                        if (lon < minLon) minLon = lon
                        if (lon > maxLon) maxLon = lon
                        return
                    }
                }
                list.forEach(::visit)
            }

            visit(coordinates)
            if (!found) return null
            return runCatching {
                LatLngBounds(
                    southWest = GeoPoint(minLat, minLon),
                    northEast = GeoPoint(maxLat, maxLon),
                )
            }.getOrNull()
        }

        private fun parseInstant(value: String?): Instant? = value?.let {
            runCatching { ZonedDateTime.parse(it, DATE_FORMAT).toInstant() }.getOrNull()
        }

        private fun severity(value: String?): AlertSeverity = when (value?.uppercase()) {
            "EXTREME", "SEVERE" -> AlertSeverity.SEVERE
            "MODERATE" -> AlertSeverity.WARNING
            else -> AlertSeverity.INFO
        }

        private fun urgency(value: String?): AlertUrgency = when (value?.uppercase()) {
            "IMMEDIATE" -> AlertUrgency.IMMEDIATE
            "EXPECTED" -> AlertUrgency.EXPECTED
            "FUTURE" -> AlertUrgency.FUTURE
            "PAST" -> AlertUrgency.PAST
            else -> AlertUrgency.UNKNOWN
        }

        private const val apiBase = "https://api.weather.gov"
        private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME
    }
}