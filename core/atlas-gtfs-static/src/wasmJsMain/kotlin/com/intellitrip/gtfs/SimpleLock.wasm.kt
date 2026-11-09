package com.intellitrip.gtfs

/** wasmJs actual: single-threaded targets execute under no locks. */
actual class SimpleLock actual constructor() {
    actual fun <T> locked(block: () -> T): T = block()
}
