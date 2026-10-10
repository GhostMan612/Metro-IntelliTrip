package com.intellitrip.gtfs

import java.io.ByteArrayInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

class GtfsArchiveException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Reads a GTFS ZIP archive into table-name to raw-byte mappings.
 *
 * Bytes are handed out instead of decoded strings so the parser can stream each
 * table and release it once consumed. Keeping the whole archive as decoded
 * strings previously exhausted an Android heap on `stop_times.txt`.
 */
object GtfsArchive {

    fun read(bytes: ByteArray): Map<String, ByteArray> {
        val files = LinkedHashMap<String, ByteArray>()
        try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                var entry: ZipEntry? = zip.nextEntry
                while (entry != null) {
                    val name = entry.name.substringAfterLast('/')
                    if (!entry.isDirectory && name.isNotEmpty()) {
                        files[name] = zip.readBytes()
                    }
                    entry = zip.nextEntry
                }
            }
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