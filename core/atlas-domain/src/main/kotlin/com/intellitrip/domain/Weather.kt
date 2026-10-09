package com.intellitrip.domain

import java.time.Instant
import java.time.LocalDate

data class WeatherAlert(
    val id: String,
    val providerId: String,
    val event: String,
    val headline: String,
    val severity: AlertSeverity,
    val urgency: AlertUrgency,
    val areas: List<String>,
    val effective: Instant?,
    val expires: Instant?,
    val description: String?,
)

enum class AlertUrgency { IMMEDIATE, EXPECTED, FUTURE, PAST, UNKNOWN }

/**
 * A single radar imagery frame. `tileUrlTemplate` is provider-specific and only
 * reaches the renderer through a layer composer; the domain model stays
 * provider-neutral apart from the recorded provider identity.
 */
data class RadarFrame(
    val providerId: String,
    val timestamp: Instant,
    val tileUrlTemplate: String,
    val attribution: String,
    val kind: RadarFrameKind,
)

enum class RadarFrameKind { PAST, NOWCAST, SATELLITE }

data class Forecast(
    val providerId: String,
    val location: GeoPoint,
    val updatedAt: Instant,
    val periods: List<ForecastPeriod>,
)

data class ForecastPeriod(
    val name: String,
    val start: Instant,
    val end: Instant,
    val temperatureCelsius: Double?,
    val precipitationChance: Double?,
    val shortForecast: String?,
)