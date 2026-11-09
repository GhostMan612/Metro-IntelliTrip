package com.intellitrip.weather

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

/** Explicit HTTP fetcher for weather providers. Never invoked at app startup. */
fun interface WeatherHttpClient {
    fun get(url: String, headers: Map<String, String>): ByteArray

    companion object {
        fun urlConnection(
            connectTimeoutMillis: Int = 15_000,
            readTimeoutMillis: Int = 30_000,
        ): WeatherHttpClient = WeatherHttpClient { url, headers ->
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "GET"
                connection.connectTimeout = connectTimeoutMillis
                connection.readTimeout = readTimeoutMillis
                headers.forEach { (key, value) -> connection.setRequestProperty(key, value) }
                val status = connection.responseCode
                if (status != HttpURLConnection.HTTP_OK) {
                    throw IOException("Unexpected HTTP $status for $url")
                }
                connection.inputStream.use { it.readBytes() }
            } finally {
                connection.disconnect()
            }
        }
    }
}

/**
 * Shared weather-provider plumbing: User-Agent identification and freshness
 * metadata. NWS requires clients to identify themselves; the identity is
 * configured by the caller and never hardcoded to a false value.
 */
class WeatherRequestContext(
    private val providerId: String,
    private val userAgent: String?,
    private val clock: Clock = Clock.System,
) {
    fun headers(): Map<String, String> =
        userAgent?.let { mapOf("User-Agent" to it) } ?: emptyMap()

    fun freshness(sourceTimestamp: Instant?): com.intellitrip.contracts.FreshnessMetadata {
        val now = clock.now()
        return com.intellitrip.contracts.FreshnessMetadata(
            sourceTimestamp = sourceTimestamp,
            fetchedAt = now,
            age = now - (sourceTimestamp ?: now),
            providerId = providerId,
        )
    }

    fun requireUserAgent(): String = userAgent?.takeIf { it.isNotBlank() }
        ?: throw IllegalStateException(
            "$providerId requires an identifying User-Agent; configure one before use"
        )
}