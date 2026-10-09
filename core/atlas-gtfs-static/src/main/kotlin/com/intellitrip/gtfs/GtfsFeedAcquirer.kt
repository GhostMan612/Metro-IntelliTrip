package com.intellitrip.gtfs

import com.intellitrip.domain.FeedId
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Explicit, testable static GTFS acquisition. No acquisition runs implicitly at
 * application startup; callers must request a refresh explicitly.
 *
 * Conditional refresh uses `If-Modified-Since` and `If-None-Match` when a prior
 * snapshot exists, honors `304 Not Modified`, validates `200 OK` payloads, and
 * keeps authentication support configurable for providers that need it.
 */
class GtfsFeedAcquirer(
    private val connectTimeoutMillis: Int = 15_000,
    private val readTimeoutMillis: Int = 60_000,
    private val userAgent: String = "IntelliTrip/0.1 (+https://github.com/GhostMan612/Metro-IntelliTrip)",
    private val authHeaderProvider: () -> String? = { null },
    private val transport: HttpTransport = HttpTransport.UrlConnection,
) {

    fun interface HttpTransport {
        fun open(request: HttpRequest): HttpResponse

        companion object {
            val UrlConnection = HttpTransport { request ->
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
                HttpResponse(status, body, headers)
            }
        }
    }

    data class HttpRequest(
        val url: String,
        val userAgent: String,
        val ifModifiedSince: String?,
        val ifNoneMatch: String?,
        val authHeader: String?,
        val connectTimeoutMillis: Int,
        val readTimeoutMillis: Int,
    )

    data class HttpResponse(val status: Int, val body: ByteArray, val headers: Map<String, String>)

    sealed interface AcquisitionResult {
        data class NotModified(
            val etag: String?,
            val lastModified: String?,
        ) : AcquisitionResult

        data class Updated(
            val feedId: FeedId,
            val payload: ByteArray,
            val etag: String?,
            val lastModified: String?,
        ) : AcquisitionResult

        data class Failure(val message: String) : AcquisitionResult
    }

    fun acquire(
        feedId: FeedId,
        url: String,
        currentEtag: String? = null,
        currentLastModified: String? = null,
    ): AcquisitionResult {
        val request = HttpRequest(
            url = url,
            userAgent = userAgent,
            ifModifiedSince = currentLastModified,
            ifNoneMatch = currentEtag,
            authHeader = authHeaderProvider(),
            connectTimeoutMillis = connectTimeoutMillis,
            readTimeoutMillis = readTimeoutMillis,
        )
        val response = try {
            transport.open(request)
        } catch (e: IOException) {
            return AcquisitionResult.Failure("Network failure acquiring feed: ${e.message}")
        }

        return when (response.status) {
            304 -> AcquisitionResult.NotModified(response.headers["ETag"], response.headers["Last-Modified"])
            401, 403 -> AcquisitionResult.Failure("Authentication required for feed $feedId")
            429 -> AcquisitionResult.Failure("Rate limited while acquiring feed $feedId")
            in 200..299 -> {
                if (response.body.isEmpty()) {
                    AcquisitionResult.Failure("Empty GTFS payload for feed $feedId")
                } else if (!GtfsArchive.isZip(response.body)) {
                    AcquisitionResult.Failure("GTFS payload for feed $feedId is not a ZIP archive")
                } else {
                    AcquisitionResult.Updated(
                        feedId = feedId,
                        payload = response.body,
                        etag = response.headers["ETag"],
                        lastModified = response.headers["Last-Modified"],
                    )
                }
            }
            else -> AcquisitionResult.Failure("Unexpected HTTP ${response.status} for feed $feedId")
        }
    }

    companion object {
        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }
    }
}