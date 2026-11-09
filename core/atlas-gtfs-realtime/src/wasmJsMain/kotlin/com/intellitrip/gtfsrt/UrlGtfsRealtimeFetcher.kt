package com.intellitrip.gtfsrt

/**
 * wasmJs HTTP actual.
 *
 * Browser `fetch` is asynchronous while [GtfsRealtimeFetcher.fetch] is a
 * synchronous call, so there is no honest synchronous implementation today
 * (a synchronous XHR would block the JS event loop). This stub fails loudly
 * as a network failure instead of returning fabricated bytes; making the
 * fetcher suspend is a later seam change.
 */
actual class UrlGtfsRealtimeFetcher actual constructor(
    connectTimeoutMillis: Int,
    readTimeoutMillis: Int,
    userAgent: String,
    authHeaderProvider: () -> String?,
) : GtfsRealtimeFetcher {

    // Stored as plain fields: the expect constructor params are not
    // properties, so the actual must not redeclare them as vals.
    private val connectTimeoutMillis: Int = connectTimeoutMillis
    private val readTimeoutMillis: Int = readTimeoutMillis
    private val userAgent: String = userAgent
    private val authHeaderProvider: () -> String? = authHeaderProvider

    actual override fun fetch(url: String): ByteArray {
        throw GtfsRealtimeException(
            "Synchronous HTTP fetching is not available on wasmJs; " +
                "the polling client now calls fetchAsync (browser fetch bridge).",
        )
    }

    override suspend fun fetchAsync(url: String): ByteArray {
        val headers = buildList {
            add("User-Agent" to userAgent)
            authHeaderProvider()?.let { add("Authorization" to it) }
        }.toMap()
        return try {
            com.intellitrip.contracts.platformFetch(url, headers)
        } catch (e: Exception) {
            throw GtfsRealtimeException("fetch failed: ${e.message}", e)
        }
    }
}
