package com.intellitrip.gtfs

import com.intellitrip.contracts.platformFetchResponse

/**
 * wasmJs default transport: browser `fetch`, async only.
 *
 * `HttpTransport.open` remains synchronous by contract and cannot be
 * implemented honestly in a browser (synchronous XHR blocks the event loop),
 * so it throws. The refresher calls [GtfsFeedAcquirer.HttpTransport.openAsync],
 * which is real here and preserves status plus ETag/Last-Modified for
 * conditional refresh.
 */
actual fun defaultHttpTransport(): GtfsFeedAcquirer.HttpTransport =
    object : GtfsFeedAcquirer.HttpTransport {
        override fun open(request: GtfsFeedAcquirer.HttpRequest): GtfsFeedAcquirer.HttpResponse =
            throw UnsupportedOperationException(
                "Synchronous GTFS HTTP is not available on wasmJs; use openAsync",
            )

        override suspend fun openAsync(
            request: GtfsFeedAcquirer.HttpRequest,
        ): GtfsFeedAcquirer.HttpResponse {
            val headers = buildMap {
                request.ifNoneMatch?.let { put("If-None-Match", it) }
                request.ifModifiedSince?.let { put("If-Modified-Since", it) }
                request.authHeader?.let { put("Authorization", it) }
                // User-Agent is a forbidden browser request header; the
                // platformFetch bridge also filters it defensively.
            }
            val response = platformFetchResponse(request.url, headers)
            return GtfsFeedAcquirer.HttpResponse(
                status = response.status,
                body = response.body,
                headers = response.headers,
            )
        }
    }
