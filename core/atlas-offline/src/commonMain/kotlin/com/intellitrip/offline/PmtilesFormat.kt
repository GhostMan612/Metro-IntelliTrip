package com.intellitrip.offline

/**
 * Geographic coverage of an installed basemap archive, in degrees.
 *
 * PMTiles stores bounds as integer degrees × 10^7. The store converts them once
 * and keeps degrees everywhere else so the UI does not have to know the wire
 * representation.
 */
data class BasemapBounds(
    val minLat: Double,
    val minLon: Double,
    val maxLat: Double,
    val maxLon: Double,
) {
    val isWorld: Boolean
        get() = minLat <= -85.0 && maxLat >= 85.0 && minLon <= -179.0 && maxLon >= 179.0
}

/** What the first bytes of an imported archive are. */
internal enum class ArchiveKind { PMTILES, MBTILES, UNKNOWN }

/**
 * Validated PMTiles v3 header fields the store and UI use.
 *
 * Only the fields that affect installation, zoom range and coverage are
 * modelled. The directory offsets are validated but not retained: MapLibre reads
 * the archive itself.
 */
internal data class PmtilesHeader(
    val minZoom: Int,
    val maxZoom: Int,
    val tileType: Int,
    val minLat: Double,
    val minLon: Double,
    val maxLat: Double,
    val maxLon: Double,
    val tileDataOffset: Long,
    val tileDataLength: Long,
    val addressedTiles: Long,
)

internal sealed interface PmtilesValidation {
    data class Valid(val header: PmtilesHeader) : PmtilesValidation

    data class Invalid(val reason: String) : PmtilesValidation
}

/**
 * PMTiles v3 header validation.
 *
 * This is deliberately stricter than "the magic bytes are present". A truncated
 * archive, a directory range that points past EOF, an impossible zoom range or
 * a corrupt bounds pair would otherwise be installed and fail only when the map
 * tries to render it, long after the import UI reported success.
 *
 * Spec: https://github.com/protomaps/PMTiles/blob/main/spec/v3/spec.md
 */
internal object PmtilesFormat {

    /** Fixed v3 header size. */
    const val HEADER_BYTES: Int = 127

    private val MAGIC = byteArrayOf(0x50, 0x4D, 0x54, 0x69, 0x6C, 0x65, 0x73) // "PMTiles"
    private val SQLITE_MAGIC = "SQLite format 3\u0000".encodeToByteArray()

    fun kind(prefix: ByteArray): ArchiveKind {
        if (prefix.size >= SQLITE_MAGIC.size &&
            SQLITE_MAGIC.indices.all { prefix[it] == SQLITE_MAGIC[it] }
        ) {
            return ArchiveKind.MBTILES
        }
        val looksLikePmtiles = prefix.size >= MAGIC.size + 1 &&
            MAGIC.indices.all { prefix[it] == MAGIC[it] }
        return if (looksLikePmtiles) ArchiveKind.PMTILES else ArchiveKind.UNKNOWN
    }

    fun validate(prefix: ByteArray, fileSize: Long): PmtilesValidation {
        if (prefix.size < HEADER_BYTES) {
            return PmtilesValidation.Invalid(
                "archive is too short to contain a PMTiles header " +
                    "(${prefix.size} bytes; need at least $HEADER_BYTES)",
            )
        }
        if (fileSize < HEADER_BYTES) {
            return PmtilesValidation.Invalid("archive is smaller than a PMTiles header")
        }
        for (i in MAGIC.indices) {
            if (prefix[i] != MAGIC[i]) {
                return PmtilesValidation.Invalid("archive does not start with the PMTiles magic bytes")
            }
        }
        val version = prefix[7].toInt() and 0xFF
        if (version != 3) {
            return PmtilesValidation.Invalid(
                "PMTiles version $version is not supported; re-export the archive as v3",
            )
        }

        val rootOffset = readU64(prefix, 8)
        val rootLength = readU64(prefix, 16)
        val metadataOffset = readU64(prefix, 24)
        val metadataLength = readU64(prefix, 32)
        val leafOffset = readU64(prefix, 40)
        val leafLength = readU64(prefix, 48)
        val tileDataOffset = readU64(prefix, 56)
        val tileDataLength = readU64(prefix, 64)
        val addressedTiles = readU64(prefix, 72)
        val tileEntries = readU64(prefix, 80)
        val tileContents = readU64(prefix, 88)

        if (listOf(
                rootOffset,
                rootLength,
                metadataOffset,
                metadataLength,
                leafOffset,
                leafLength,
                tileDataOffset,
                tileDataLength,
                addressedTiles,
                tileEntries,
                tileContents,
            ).any { it < 0L }
        ) {
            return PmtilesValidation.Invalid("archive header contains an out-of-range offset or count")
        }
        if (rootOffset < HEADER_BYTES.toLong()) {
            return PmtilesValidation.Invalid("root directory overlaps the header")
        }
        if (rootLength <= 0L) {
            return PmtilesValidation.Invalid("root directory is empty")
        }
        if (!rangeWithin(fileSize, rootOffset, rootLength)) {
            return PmtilesValidation.Invalid("root directory points past the end of the archive")
        }
        if (metadataLength > 0L && !rangeWithin(fileSize, metadataOffset, metadataLength)) {
            return PmtilesValidation.Invalid("metadata points past the end of the archive")
        }
        if (leafLength > 0L && !rangeWithin(fileSize, leafOffset, leafLength)) {
            return PmtilesValidation.Invalid("leaf directories point past the end of the archive")
        }
        if (tileDataOffset < HEADER_BYTES.toLong()) {
            return PmtilesValidation.Invalid("tile data overlaps the header")
        }
        if (tileDataLength <= 0L) {
            return PmtilesValidation.Invalid("archive contains no tile data")
        }
        if (!rangeWithin(fileSize, tileDataOffset, tileDataLength)) {
            return PmtilesValidation.Invalid("tile data points past the end of the archive")
        }
        if (addressedTiles <= 0L || tileEntries <= 0L || tileContents <= 0L) {
            return PmtilesValidation.Invalid("archive reports no tiles")
        }

        val clustered = prefix[96].toInt() and 0xFF
        if (clustered !in 0..1) {
            return PmtilesValidation.Invalid("invalid clustered flag $clustered")
        }
        val internalCompression = prefix[97].toInt() and 0xFF
        val tileCompression = prefix[98].toInt() and 0xFF
        if (internalCompression !in 0..4 || tileCompression !in 0..4) {
            return PmtilesValidation.Invalid(
                "unsupported compression (internal=$internalCompression, tile=$tileCompression)",
            )
        }
        val tileType = prefix[99].toInt() and 0xFF
        if (tileType !in 1..5) {
            return PmtilesValidation.Invalid("unsupported tile type $tileType")
        }
        val minZoom = prefix[100].toInt() and 0xFF
        val maxZoom = prefix[101].toInt() and 0xFF
        if (minZoom > maxZoom || maxZoom > 24) {
            return PmtilesValidation.Invalid("invalid zoom range $minZoom..$maxZoom")
        }

        val minLonE7 = readI32(prefix, 102)
        val minLatE7 = readI32(prefix, 106)
        val maxLonE7 = readI32(prefix, 110)
        val maxLatE7 = readI32(prefix, 114)
        val hasBounds = minLonE7 != 0 || minLatE7 != 0 || maxLonE7 != 0 || maxLatE7 != 0
        val bounds = if (hasBounds) {
            if (minLonE7 > maxLonE7 || minLatE7 > maxLatE7) {
                return PmtilesValidation.Invalid("bounds are inverted")
            }
            if (minLonE7 < -180_0000000 || maxLonE7 > 180_0000000 ||
                minLatE7 < -90_0000000 || maxLatE7 > 90_0000000
            ) {
                return PmtilesValidation.Invalid("bounds fall outside the valid lon/lat range")
            }
            BasemapBounds(
                minLat = minLatE7 / 10_000_000.0,
                minLon = minLonE7 / 10_000_000.0,
                maxLat = maxLatE7 / 10_000_000.0,
                maxLon = maxLonE7 / 10_000_000.0,
            )
        } else {
            // A zeroed bounds field is legal for an archive whose writer did not
            // compute coverage. Treat it as world coverage rather than inventing a
            // location, and keep the package otherwise usable.
            BasemapBounds(minLat = -90.0, minLon = -180.0, maxLat = 90.0, maxLon = 180.0)
        }

        return PmtilesValidation.Valid(
            PmtilesHeader(
                minZoom = minZoom,
                maxZoom = maxZoom,
                tileType = tileType,
                minLat = bounds.minLat,
                minLon = bounds.minLon,
                maxLat = bounds.maxLat,
                maxLon = bounds.maxLon,
                tileDataOffset = tileDataOffset,
                tileDataLength = tileDataLength,
                addressedTiles = addressedTiles,
            ),
        )
    }

    /**
     * Reads a little-endian u64. PMTiles uses unsigned offsets, but Kotlin/JVM
     * file sizes are signed Long; a value with the high bit set cannot describe a
     * real file and is rejected by callers as a negative offset. Return -1 so no
     * arithmetic can wrap into a plausible range.
     */
    private fun readU64(bytes: ByteArray, offset: Int): Long {
        var value = 0L
        for (i in 0 until 8) {
            val b = bytes[offset + i].toLong() and 0xFF
            if (i == 7 && b >= 0x80) return -1L
            value = value or (b shl (8 * i))
        }
        return value
    }

    private fun readI32(bytes: ByteArray, offset: Int): Int {
        val b0 = bytes[offset].toInt() and 0xFF
        val b1 = bytes[offset + 1].toInt() and 0xFF
        val b2 = bytes[offset + 2].toInt() and 0xFF
        val b3 = bytes[offset + 3].toInt() and 0xFF
        return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
    }

    private fun rangeWithin(fileSize: Long, offset: Long, length: Long): Boolean {
        if (offset < 0L || length < 0L) return false
        // Subtraction avoids overflow from offset + length.
        return offset <= fileSize && length <= fileSize - offset
    }
}
