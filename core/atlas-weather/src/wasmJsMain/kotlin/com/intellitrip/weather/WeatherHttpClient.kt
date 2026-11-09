package com.intellitrip.weather

/**
 * wasmJs actual for [WeatherHttpClient]: stub transport.
 *
 * `get` is synchronous in the shared contract, but browsers offer no
 * synchronous HTTP (synchronous `XMLHttpRequest` is forbidden on the main
 * thread and `fetch` is async), so the platform default fails with
 * [WeatherNetworkException] and providers surface a `NetworkFailure` rather
 * than crashing. Custom handlers injected via the constructor still work.
 *
 * TODO(wasm): make fetching async behind a suspending façade (e.g. `fetch`
 * via Kotlin/JS interop) with an IndexedDB-backed response cache and a
 * staleness policy, then retire this stub.
 */
actual class WeatherHttpClient actual constructor(
    handler: (String, Map<String, String>) -> ByteArray,
) {
    private val storedHandler: (String, Map<String, String>) -> ByteArray = handler

    actual fun get(url: String, headers: Map<String, String>): ByteArray =
        storedHandler(url, headers)

    actual companion object {
        actual fun urlConnection(
            connectTimeoutMillis: Int,
            readTimeoutMillis: Int,
        ): WeatherHttpClient = WeatherHttpClient { _, _ ->
            throw WeatherNetworkException(
                "Network fetching is not available on wasmJs (stub transport). " +
                    "TODO: async fetch + IndexedDB cache."
            )
        }
    }
}
