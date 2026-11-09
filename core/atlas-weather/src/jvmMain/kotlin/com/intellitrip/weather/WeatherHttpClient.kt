package com.intellitrip.weather

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * JVM actual for [WeatherHttpClient]: `HttpURLConnection` transport.
 * This is the sole `java.net` site in the module.
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
                try {
                    connection.inputStream.use { it.readBytes() }
                } catch (e: IOException) {
                    throw WeatherNetworkException(
                        "Failed to read response from $url: ${e.message}",
                        e,
                    )
                }
            } catch (e: IOException) {
                // Transport-level failures (connect, DNS, non-200, read) are
                // all network failures from the provider's point of view.
                throw WeatherNetworkException(e.message, e)
            } finally {
                connection.disconnect()
            }
        }
    }
}
