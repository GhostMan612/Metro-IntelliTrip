package com.intellitrip.gtfs

import java.net.HttpURLConnection
import java.net.URL

/**
 * JVM actual for the default transport: `HttpURLConnection` with the same
 * headers and timeouts the old `UrlConnection` transport used.
 */
actual fun defaultHttpTransport(): GtfsFeedAcquirer.HttpTransport =
    GtfsFeedAcquirer.HttpTransport { request ->
        val connection = URL(request.url).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = request.connectTimeoutMillis
        connection.readTimeout = request.readTimeoutMillis
        connection.setRequestProperty("User-Agent", request.userAgent)
        if (request.ifModifiedSince != null) {
            connection.setRequestProperty("If-Modified-Since", request.ifModifiedSince)
        }
        if (request.ifNoneMatch != null) {
            connection.setRequestProperty("If-None-Match", request.ifNoneMatch)
        }
        request.authHeader?.let { connection.setRequestProperty("Authorization", it) }
        val status = connection.responseCode
        val body = if (status == HttpURLConnection.HTTP_OK) {
            connection.inputStream.use { it.readBytes() }
        } else {
            ByteArray(0)
        }
        val headers = mapOf(
            "ETag" to connection.getHeaderField("ETag"),
            "Last-Modified" to connection.getHeaderField("Last-Modified"),
        )
        connection.disconnect()
        GtfsFeedAcquirer.HttpResponse(status, body, headers)
    }
