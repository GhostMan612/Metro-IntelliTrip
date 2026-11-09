package com.intellitrip.contracts

/**
 * Durable cross-restart byte cache, async by necessity: IndexedDB (the
 * browser durable store) has no synchronous API, so this cannot stay behind
 * the synchronous `PlatformFileStore` seam. JVM actual: in-memory (files are
 * already durable there — this seam exists for the wasm shell, whose file
 * store is otherwise process-memory only).
 *
 * Entries are large (full GTFS payloads, basemap tiles), keyed by caller
 * content (`payload/<feed>/<sha>`), so callers can skip downloads after a
 * reload without re-fetching bytes they already activated.
 */
expect suspend fun platformDurablePut(key: String, bytes: ByteArray)

expect suspend fun platformDurableGet(key: String): ByteArray?
