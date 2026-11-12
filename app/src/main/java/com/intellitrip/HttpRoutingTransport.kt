package com.intellitrip

import com.intellitrip.routing.RoutingTransport
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Blocking HTTP transport for the opt-in OpenTripPlanner adapter.
 *
 * OTP planning is a single GET with no credentials and no request body. The
 * transport is deliberately small: it adds a descriptive User-Agent and bounded
 * timeouts, and it throws on non-2xx so the adapter reports a network failure
 * instead of trying to parse an error page as JSON.
 *
 * This transport is only constructed when `OTP_ENDPOINT` is non-blank. No request
 * is made in the default local-only build.
 */
internal class HttpRoutingTransport(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS)
        .build(),
    private val userAgent: String = USER_AGENT,
) : RoutingTransport {

    override fun get(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Accept", "application/json")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("OTP returned HTTP ${response.code}")
            }
            return response.body?.string() ?: throw IOException("OTP returned an empty body")
        }
    }
}
