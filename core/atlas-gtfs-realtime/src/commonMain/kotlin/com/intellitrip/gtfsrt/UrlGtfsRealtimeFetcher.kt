package com.intellitrip.gtfsrt

/**
 * Explicit URL fetcher for GTFS-Realtime payloads. Acquisition is always
 * caller-initiated; nothing here runs at application startup.
 *
 * This is the HTTP seam: the [GtfsRealtimeFetcher] funnel the polling client
 * depends on. The platform actuals implement the wire call (JVM:
 * `HttpURLConnection`; wasmJs: browser fetch bridge).
 */
expect class UrlGtfsRealtimeFetcher(
    connectTimeoutMillis: Int = 15_000,
    readTimeoutMillis: Int = 30_000,
    userAgent: String = "IntelliTrip/0.1 (+https://github.com/GhostMan612/Metro-IntelliTrip)",
    authHeaderProvider: () -> String? = { null },
) : GtfsRealtimeFetcher {
    override fun fetch(url: String): ByteArray
}
