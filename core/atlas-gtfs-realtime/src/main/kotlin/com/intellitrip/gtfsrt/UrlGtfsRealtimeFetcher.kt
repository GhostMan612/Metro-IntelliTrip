package com.intellitrip.gtfsrt

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Explicit URL fetcher for GTFS-Realtime payloads. Acquisition is always
 * caller-initiated; nothing here runs at application startup.
 */
class UrlGtfsRealtimeFetcher(
    private val connectTimeoutMillis: Int = 15_000,
    private val readTimeoutMillis: Int = 30_000,
    private val userAgent: String = "IntelliTrip/0.1 (+https://github.com/GhostMan612/Metro-IntelliTrip)",
    private val authHeaderProvider: () -> String? = { null },
) : GtfsRealtimeFetcher {

    override fun fetch(url: String): ByteArray {
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
