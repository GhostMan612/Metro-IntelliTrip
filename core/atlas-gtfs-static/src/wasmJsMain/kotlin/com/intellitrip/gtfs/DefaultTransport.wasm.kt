package com.intellitrip.gtfs

/**
 * wasmJs actual for the default transport: unavailable without synchronous
 * JS HTTP. Web hosts inject a [GtfsFeedAcquirer.HttpTransport].
 */
actual fun defaultHttpTransport(): GtfsFeedAcquirer.HttpTransport =
    GtfsFeedAcquirer.HttpTransport {
        throw UnsupportedOperationException(
            "No default HTTP transport on wasmJs: inject a GtfsFeedAcquirer.HttpTransport",
        )
    }
