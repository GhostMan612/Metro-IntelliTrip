package com.intellitrip.contracts

/**
 * Network failure surfaced by [platformFetch] implementations, so callers can
 * distinguish transport outages from bad payloads without referencing
 * platform I/O types (which do not exist on wasmJs).
 */
class FetchTransportException(message: String?, cause: Throwable? = null) :
    Exception(message, cause)

/**
 * The single HTTP seam for portable clients that cannot issue blocking calls
 * on wasmJs (browsers have no synchronous XHR; `fetch` is async).
 *
 * JVM actual: `HttpURLConnection` on the IO pool (same behavior callers had
 * with the legacy synchronous fetchers). wasmJs actual: browser `fetch`
 * (`Response.arrayBuffer()`). Providers call this from coroutine context.
 *
 * The legacy synchronous fetchers (`WeatherHttpClient.get`, etc.) stay for
 * tests and JVM hosts that already run off the main thread.
 */
expect suspend fun platformFetch(url: String, headers: Map<String, String>): ByteArray
