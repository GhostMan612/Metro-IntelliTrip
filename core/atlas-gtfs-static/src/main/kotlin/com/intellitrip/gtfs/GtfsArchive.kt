package com.intellitrip.gtfs

import java.io.ByteArrayInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

class GtfsArchiveException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Reads a GTFS ZIP archive into table-name to CSV-content mappings. */
object GtfsArchive {

    fun read(bytes: ByteArray): Map<String, String> {
        val files = LinkedHashMap<String, String>()
        try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                var entry: ZipEntry? = zip.nextEntry
                while (entry != null) {
                    val name = entry.name.substringAfterLast('/')
                    if (!entry.isDirectory && name.isNotEmpty()) {
                        files[name] = zip.readBytes().toString(Charsets.UTF_8)
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