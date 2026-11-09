package com.intellitrip.gtfs

import com.intellitrip.domain.FeedId

/**
 * Default transport for [GtfsFeedAcquirer]: the platform HTTP client.
 *
 * JVM actual: `HttpURLConnection`. wasmJs actual: none — web hosts inject a
 * transport (browser fetch later). A stub throwing here keeps the common
 * signature portable; injected transports behave identically everywhere.
 */
expect fun defaultHttpTransport(): GtfsFeedAcquirer.HttpTransport

/**
 * Explicit, testable static GTFS acquisition. No acquisition runs implicitly
 * at application startup; callers must request a refresh explicitly.
 *
 * Conditional refresh uses `If-Modified-Since` and `If-None-Match` when a
 * prior snapshot exists, honors `304 Not Modified`, validates `200 OK`
 * payloads, and keeps authentication support configurable for providers
 * that need it.
 */
class GtfsFeedAcquirer(
    private val connectTimeoutMillis: Int = 15_000,
    private val readTimeoutMillis: Int = 60_000,
    private val userAgent: String = "IntelliTrip/0.1 (+https://github.com/GhostMan612/Metro-IntelliTrip)",
    private val authHeaderProvider: () -> String? = { null },
    private val transport: HttpTransport = defaultHttpTransport(),
) {

    fun interface HttpTransport {
        fun open(request: HttpRequest): HttpResponse
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
        } catch (e: Exception) {
            // The old JVM code caught only IOException; in common code the
            // transport failure surface is `Exception`, so any transport
            // failure becomes a Failure (a transport already maps its own
            // outcomes onto HttpResponse).
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
        fun sha256(bytes: ByteArray): String = Sha256.hex(bytes)
    }
}
