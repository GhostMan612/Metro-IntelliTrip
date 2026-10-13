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
    /**
     * Bounding box of the alert area, when the provider published one.
     *
     * Used to decide whether an alert is relevant to the map viewport. A provider that
     * answers a single-point query returns only alerts covering that point, so a wide
     * viewport would silently miss warnings happening at its edges. Carrying the area's
     * box lets the caller include those without a second request.
     *
     * Null when unknown. A null box must never cause an alert to be hidden: for a
     * safety warning, showing one that turns out to be slightly out of area is far less
     * harmful than suppressing one that was real.
     */
    val areaBounds: LatLngBounds? = null,
) {
    /**
     * Whether this alert may be relevant to [bounds].
     *
     * Deliberately permissive. The test is bounding-box overlap, not polygon
     * intersection, so an alert whose box overlaps the viewport is kept even if the
     * polygon misses by a corner. Over-inclusion is the safe direction for a severe
     * weather warning; under-inclusion is the dangerous one.
     */
    fun mayAffect(bounds: LatLngBounds?): Boolean {
        if (bounds == null) return true
        val area = areaBounds ?: return true
        return !(area.northEast.lat < bounds.southWest.lat ||
            area.southWest.lat > bounds.northEast.lat ||
            area.northEast.lon < bounds.southWest.lon ||
            area.southWest.lon > bounds.northEast.lon)
    }
}

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