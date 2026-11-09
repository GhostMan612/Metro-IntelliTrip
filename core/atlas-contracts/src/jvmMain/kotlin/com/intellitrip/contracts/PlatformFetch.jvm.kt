package com.intellitrip.contracts

actual suspend fun platformFetch(url: String, headers: Map<String, String>): ByteArray {
    val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
    try {
        conn.requestMethod = "GET"
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        conn.inputStream.use { input ->
            val out = java.io.ByteArrayOutputStream()
            input.copyTo(out)
            return out.toByteArray()
        }
    } catch (e: Exception) {
        throw FetchTransportException("GET $url failed", e)
    } finally {
        conn.disconnect()
    }
}
