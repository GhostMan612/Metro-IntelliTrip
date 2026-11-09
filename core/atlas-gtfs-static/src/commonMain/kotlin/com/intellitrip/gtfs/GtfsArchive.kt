package com.intellitrip.gtfs

class GtfsArchiveException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Reads a GTFS ZIP archive into table-name to raw-byte mappings.
 *
 * Bytes are handed out instead of decoded strings so the parser can stream
 * each table and release it once consumed. Keeping the whole archive as
 * decoded strings previously exhausted a phone heap on `stop_times.txt`.
 */
object GtfsArchive {

    /**
     * Inflates every entry and returns a base-name to bytes map (directory
     * entries skipped; later entries with the same base name win, matching
     * the map semantics of the old platform ZIP read).
     */
    fun read(bytes: ByteArray): Map<String, ByteArray> {
        val files = LinkedHashMap<String, ByteArray>()
        try {
            val zip = ZipReader(bytes)
            for (name in zip.names) {
                files[name.substringAfterLast('/')] = zip.read(name)!!
            }
        } catch (e: GtfsArchiveException) {
            throw e
        } catch (e: Exception) {
            throw GtfsArchiveException("Invalid GTFS ZIP payload", e)
        }
        if (files.isEmpty()) {
            throw GtfsArchiveException("GTFS ZIP contained no readable tables")
        }
        return files
    }

    fun isZip(bytes: ByteArray): Boolean =
        bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()
}
