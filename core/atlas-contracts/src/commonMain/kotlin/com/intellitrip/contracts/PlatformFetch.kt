package com.intellitrip.contracts

/**
 * Network failure surfaced by [platformFetch] / [platformFetchResponse]
 * implementations, so callers can distinguish transport outages from bad
 * payloads without referencing platform I/O types (which do not exist on
 * wasmJs).
 */
class FetchTransportException(message: String?, cause: Throwable? = null) :
    Exception(message, cause)

/**
 * Status-aware HTTP response for portable clients that must see conditional
 * request outcomes (GTFS publishers answer `304 Not Modified`) instead of a
 * bytes-or-throw API.
 *
 * Header names are preserved as received; callers that need case-insensitive
 * lookup normalize them (see `GtfsFeedAcquirer`).
 */
data class PlatformHttpResponse(
    val status: Int,
    val body: ByteArray,
    val headers: Map<String, String>,
)

/**
 * The single HTTP seam for portable clients that cannot issue blocking calls
 * on wasmJs (browsers have no synchronous XHR; `fetch` is async).
 *
 * JVM actual: `HttpURLConnection` (same behavior callers had with the legacy
 * synchronous fetchers). wasmJs actual: browser `fetch`
 * (`Response.arrayBuffer()`), with status and the response headers a
 * conditional GTFS refresh needs.
 */
expect suspend fun platformFetchResponse(
    url: String,
    headers: Map<String, String>,
): PlatformHttpResponse

/**
 * Bytes-or-throw convenience over [platformFetchResponse].
 *
 * Kept for providers whose success condition is simply `2xx` (weather,
 * alerts). A non-2xx response is a [FetchTransportException].
 */
expect suspend fun platformFetch(url: String, headers: Map<String, String>): ByteArray
