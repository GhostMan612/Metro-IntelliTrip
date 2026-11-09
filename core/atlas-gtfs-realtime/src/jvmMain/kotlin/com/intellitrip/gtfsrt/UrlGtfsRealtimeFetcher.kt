package com.intellitrip.gtfsrt

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * JVM HTTP actual: synchronous `HttpURLConnection` GET. Non-200 surfaces as
 * [IOException] (as before); the polling client maps fetcher failures to
 * [com.intellitrip.contracts.ProviderResult.NetworkFailure].
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
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = connectTimeoutMillis
            connection.readTimeout = readTimeoutMillis
            connection.setRequestProperty("User-Agent", userAgent)
            authHeaderProvider()?.let { connection.setRequestProperty("Authorization", it) }
            val status = connection.responseCode
            if (status != HttpURLConnection.HTTP_OK) {
                throw IOException("Unexpected HTTP $status for $url")
            }
            return connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }
}
