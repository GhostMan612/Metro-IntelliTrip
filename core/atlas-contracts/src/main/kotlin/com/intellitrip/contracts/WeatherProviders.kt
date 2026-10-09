package com.intellitrip.contracts

import com.intellitrip.domain.Forecast
import com.intellitrip.domain.GeoPoint
import com.intellitrip.domain.LatLngBounds
import com.intellitrip.domain.RadarFrame
import com.intellitrip.domain.WeatherAlert
import java.time.Instant
import kotlinx.coroutines.flow.Flow

/**
 * Provider-neutral weather access.
 *
 * One-shot operations are `suspend` functions returning `ProviderResult<T>`;
 * continuously refreshed alert feeds use `Flow<ProviderResult<T>>`.
 */
interface RadarProvider {
    val providerId: String

    suspend fun latestFrame(): ProviderResult<RadarFrame?>

    suspend fun frames(since: Instant): ProviderResult<List<RadarFrame>>
}

interface WeatherAlertProvider {
    val providerId: String

    fun alerts(bounds: LatLngBounds? = null): Flow<ProviderResult<List<WeatherAlert>>>
}

interface WeatherForecastProvider {
    val providerId: String

    suspend fun forecast(location: GeoPoint): ProviderResult<Forecast>
}