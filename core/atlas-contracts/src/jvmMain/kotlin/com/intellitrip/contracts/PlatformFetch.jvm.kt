package com.intellitrip.contracts

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

actual suspend fun platformFetchResponse(
    url: String,
    headers: Map<String, String>,
): PlatformHttpResponse = withContext(Dispatchers.IO) {
    val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
    try {
        conn.requestMethod = "GET"
        conn.connectTimeout = 15_000
        conn.readTimeout = 60_000
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        val status = conn.responseCode
        val stream = if (status in 200..299) conn.inputStream else conn.errorStream
        val body = stream?.use { it.readBytes() } ?: ByteArray(0)
        val responseHeaders = conn.headerFields
            .filterKeys { it != null }
            .mapKeys { it.key!! }
            .mapValues { (_, values) -> values.joinToString(", ") }
        PlatformHttpResponse(status = status, body = body, headers = responseHeaders)
    } catch (e: Exception) {
        throw FetchTransportException("GET $url failed", e)
    } finally {
        conn.disconnect()
    }
}

actual suspend fun platformFetch(url: String, headers: Map<String, String>): ByteArray {
    val response = platformFetchResponse(url, headers)
    if (response.status !in 200..299) {
        throw FetchTransportException("GET $url failed: HTTP ${response.status}")
    }
    return response.body
}
