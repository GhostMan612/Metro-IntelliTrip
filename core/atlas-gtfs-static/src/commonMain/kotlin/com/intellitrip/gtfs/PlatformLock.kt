package com.intellitrip.gtfs

/**
 * Mutual-exclusion lock for the stop-time reader's cache.
 *
 * Single-threaded targets execute the block directly; the JVM actual
 * synchronizes, preserving the old `synchronized` behavior of
 * [StopTimeIndexReader].
 */
expect class SimpleLock() {
    fun <T> locked(block: () -> T): T
}
