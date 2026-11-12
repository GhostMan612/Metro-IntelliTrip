package com.intellitrip.offline

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path

/**
 * Synthetic PMTiles v3 headers for store tests.
 *
 * These fixtures are intentionally not renderable archives: they exercise the
 * installer's header validation, generation switching and manifest round trip
 * without shipping a multi-megabyte tile package in the repository. A real
 * archive is still required for the Chromium/Android render checks.
 */
internal object PmtilesFixture {

    fun bytes(
        size: Int = 4096,
        minZoom: Int = 0,
        maxZoom: Int = 14,
        tileType: Int = 1,
    ): ByteArray {
        require(size >= 1024) { "fixture must clear the store's minimum size" }
        val bytes = ByteArray(size)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("PMTiles".toByteArray(Charsets.US_ASCII))
        buffer.put(3)
        buffer.putLong(127L) // root directory offset
        buffer.putLong(16L) // root directory length
        buffer.putLong(0L) // metadata offset
        buffer.putLong(0L) // metadata length
        buffer.putLong(0L) // leaf directories offset
        buffer.putLong(0L) // leaf directories length
        buffer.putLong(143L) // tile data offset
        buffer.putLong((size - 143).toLong()) // tile data length
        buffer.putLong(1L) // addressed tiles
        buffer.putLong(1L) // tile entries
        buffer.putLong(1L) // tile contents
        buffer.put(0) // clustered
        buffer.put(2) // internal compression: gzip
        buffer.put(2) // tile compression: gzip
        buffer.put(tileType.toByte())
        buffer.put(minZoom.toByte())
        buffer.put(maxZoom.toByte())
        buffer.putInt(-934_000_000) // min lon E7
        buffer.putInt(448_000_000) // min lat E7
        buffer.putInt(-929_000_000) // max lon E7
        buffer.putInt(451_000_000) // max lat E7
        buffer.put(12) // center zoom
        buffer.putInt(-931_500_000) // center lon E7
        buffer.putInt(449_500_000) // center lat E7
        return bytes
    }

    fun mbtiles(size: Int = 4096): ByteArray {
        val bytes = ByteArray(size)
        val magic = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
        magic.copyInto(bytes)
        return bytes
    }

    fun write(path: Path, bytes: ByteArray = bytes()): Path {
        Files.write(path, bytes)
        return path
    }

    fun style(): String =
        """{"version":8,"sources":{"offline":{"type":"vector","url":"pmtiles://basemap.pmtiles"}},"layers":[]}"""
}
