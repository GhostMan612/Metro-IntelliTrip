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
        ProviderResult.Success(parseAlerts(payload), context.freshness(updated))
    } catch (e: IOException) {
        ProviderResult.NetworkFailure(ProviderError("NWS alerts unavailable", e))
    } catch (e: Exception) {
        ProviderResult.MalformedResponse(ProviderError("Unreadable NWS alerts payload", e))
    }

    internal fun alertUrl(bounds: LatLngBounds?): String = when (bounds) {
        null -> "$apiBase/alerts/active"
        else -> "$apiBase/alerts/active?point=${bounds.southWest.lat},${bounds.southWest.lon}"
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
                )
            }
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