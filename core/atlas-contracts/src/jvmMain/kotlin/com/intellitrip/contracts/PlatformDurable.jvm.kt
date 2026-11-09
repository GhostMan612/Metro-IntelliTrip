package com.intellitrip.contracts

private val durableMemory = mutableMapOf<String, ByteArray>()

actual suspend fun platformDurablePut(key: String, bytes: ByteArray) {
    synchronized(durableMemory) { durableMemory[key] = bytes.copyOf() }
}

actual suspend fun platformDurableGet(key: String): ByteArray? {
    synchronized(durableMemory) { return durableMemory[key]?.copyOf() }
}
