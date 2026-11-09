package com.intellitrip.weather

/**
 * wasmJs actual for [WeatherHttpClient].
 *
 * The synchronous [WeatherHttpClient.get] contract cannot be satisfied in a
 * browser (sync `XMLHttpRequest` is forbidden on the main thread and `fetch`
 * is async), so the default transport fails with [WeatherNetworkException].
 * [WeatherHttpClient.getAsync] is the real path: it bridges the shared
 * `platformFetch` seam (browser `fetch` actual).
 */
actual class WeatherHttpClient actual constructor(
    handler: (String, Map<String, String>) -> ByteArray,
) {
    private val storedHandler: (String, Map<String, String>) -> ByteArray = handler

    actual fun get(url: String, headers: Map<String, String>): ByteArray =
        storedHandler(url, headers)

    actual suspend fun getAsync(url: String, headers: Map<String, String>): ByteArray =
        com.intellitrip.contracts.platformFetch(url, headers)

    actual companion object {
        actual fun urlConnection(
            connectTimeoutMillis: Int,
            readTimeoutMillis: Int,
        ): WeatherHttpClient = WeatherHttpClient { _, _ ->
            throw WeatherNetworkException(
                "Synchronous fetch is unavailable on wasmJs; use getAsync (browser fetch bridge)."
            )
        }
    }
}
