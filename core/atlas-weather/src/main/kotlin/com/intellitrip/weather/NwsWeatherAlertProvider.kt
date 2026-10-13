package com.intellitrip.weather

import com.intellitrip.contracts.ProviderError
import com.intellitrip.contracts.ProviderResult
import com.intellitrip.contracts.WeatherAlertProvider
import com.intellitrip.domain.AlertSeverity
import com.intellitrip.domain.AlertUrgency
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
 * NWS requires clients to identify themselves with a User-Agent containing an
 * application contact. The identity is supplied by the caller; no identity is
 * fabricated here and no personal contact data is committed to the repository.
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
            val result = withContext(ioDispatcher) { fetchAlerts(bounds) }
            emit(result)
            delay(pollInterval.toMillis())
        }
    }

    private fun fetchAlerts(bounds: LatLngBounds?): ProviderResult<List<WeatherAlert>> = try {
        val payload = http.get(alertUrl(bounds), context.headers()).toString(Charsets.UTF_8)
        val updated = parseUpdated(payload)
        // The request can only ask about one point, so the response is filtered against
        // the viewport before it reaches the UI. Alerts whose area box does not overlap
        // are dropped; alerts with an unknown box are kept, because hiding a warning
        // that turned out to be real is the worse failure.
        val relevant = parseAlerts(payload).filter { it.mayAffect(bounds) }
        ProviderResult.Success(relevant, context.freshness(updated))
    } catch (e: IOException) {
        ProviderResult.NetworkFailure(ProviderError("NWS alerts unavailable", e))
    } catch (e: Exception) {
        ProviderResult.MalformedResponse(ProviderError("Unreadable NWS alerts payload", e))
    }

    internal fun alertUrl(bounds: LatLngBounds?): String = when (bounds) {
        // No viewport: the national active set, which the client then filters.
        null -> "$apiBase/alerts/active"

        // A viewport query must use its centre.
        //
        // The previous code sent the southwest corner. A user looking at any area
        // other than the extreme corner of the map got alerts for the wrong place:
        // NWS resolves `point` to the alerts covering that single coordinate, so a
        // storm over north Minneapolis was invisible while the screen showed exactly
        // that storm. Centring the query is what "alerts for what I am looking at"
        // means.
        else -> {
            val centreLat = (bounds.southWest.lat + bounds.northEast.lat) / 2.0
            val centreLon = (bounds.southWest.lon + bounds.northEast.lon) / 2.0
            "$apiBase/alerts/active?point=$centreLat,$centreLon"
        }
    }

    internal companion object {
        private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME

        fun parseUpdated(payload: String): Instant? =
            Json.parse(payload).asObject()["updated"].asStringOrNull()?.let { raw ->
                runCatching { ZonedDateTime.parse(raw, DATE_FORMAT).toInstant() }.getOrNull()
            }

        fun parseAlerts(payload: String): List<WeatherAlert> {
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
         * NWS answers a `point` query with alerts covering that coordinate, so a wide
         * viewport misses warnings happening at its edges. Carrying each alert's own box
         * lets the caller include them without a second request.
         *
         * Walks the raw coordinate arrays rather than a geometry library, because the
         * provider's `Json` type is the only JSON reader this module depends on.
         * Returns null when the geometry is absent or malformed, which callers must
         * read as "unknown", never as "does not apply".
         */
        private fun boundsOf(geometry: Any?): com.intellitrip.domain.LatLngBounds? {
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
                com.intellitrip.domain.LatLngBounds(
                    southWest = com.intellitrip.domain.GeoPoint(minLat, minLon),
                    northEast = com.intellitrip.domain.GeoPoint(maxLat, maxLon),
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
    }
}