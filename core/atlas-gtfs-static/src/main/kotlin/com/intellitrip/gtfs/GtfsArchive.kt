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
                        files[name] = readEntryBytes(zip)
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

    /** Reads a staged ZIP file directly, without a payload ByteArray. */
    fun readFile(file: java.nio.file.Path): Map<String, ByteArray> {
        val files = LinkedHashMap<String, ByteArray>()
        try {
            java.util.zip.ZipFile(file.toFile()).use { zipFile ->
                val entries = zipFile.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val name = entry.name.substringAfterLast('/')
                    if (!entry.isDirectory && name.isNotEmpty()) {
                        zipFile.getInputStream(entry).use { input ->
                            val out = java.io.ByteArrayOutputStream()
                            val buf = ByteArray(8 * 1024)
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                            }
                            files[name] = out.toByteArray()
                        }
                    }
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

    /**
     * Streams each entry with an 8 KiB buffer instead of [InputStream.readBytes].
     * Saves one full-size transient allocation per entry (the 100 MB+ OOM seen
     * on low-end devices for `stop_times.txt`).
     */
    fun readEntryBytes(zip: ZipInputStream): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8 * 1024)
        while (true) {
            val n = zip.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    fun isZip(bytes: ByteArray): Boolean =
        bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()
}