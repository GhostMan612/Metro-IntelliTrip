package com.intellitrip.gtfs

/** JVM actual: the reader's cache is shared across planner threads. */
actual class SimpleLock actual constructor() {
    private val lock = Any()

    actual fun <T> locked(block: () -> T): T = synchronized(lock, block)
}
