package com.intellitrip.weather

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

/**
 * Network failure surfaced by [WeatherHttpClient] implementations.
 *
 * Platform transports map their native failures onto this type so providers
 * in common code can distinguish a network outage (reported as
 * [com.intellitrip.contracts.ProviderResult.NetworkFailure]) from an
 * unreadable payload (reported as
 * [com.intellitrip.contracts.ProviderResult.MalformedResponse]) without
 * referencing JVM-only I/O types, which do not exist on wasmJs.
 */
class WeatherNetworkException(message: String?, cause: Throwable? = null) :
    Exception(message, cause)

/**
 * Explicit HTTP fetcher for weather providers. Never invoked at app startup.
 *
 * The platform actual is the only networking seam in this module: the JVM
 * actual uses `HttpURLConnection`, the wasmJs actual is a stub (see
 * `wasmJsMain`). Construct directly with a handler lambda for tests or
 * custom transports; use [urlConnection] to obtain the platform default.
 */
expect class WeatherHttpClient {
    constructor(handler: (String, Map<String, String>) -> ByteArray)

    fun get(url: String, headers: Map<String, String>): ByteArray

    /**
     * Async variant backed by the platform fetch (M4c): browser `fetch` on
     * wasmJs, IO-pool blocking read on JVM. Providers call this so web
     * hosts get a real transport instead of the sync stub.
     */
    suspend fun getAsync(url: String, headers: Map<String, String>): ByteArray

    companion object {
        /**
         * Platform-default transport. JVM: `HttpURLConnection` with the
         * given timeouts. wasmJs: browser `fetch` bridge (see wasmJs actual).
         */
        fun urlConnection(
            connectTimeoutMillis: Int = 15_000,
            readTimeoutMillis: Int = 30_000,
        ): WeatherHttpClient
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
