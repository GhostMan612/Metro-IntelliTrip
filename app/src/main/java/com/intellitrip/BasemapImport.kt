package com.intellitrip

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Stages files chosen through the Storage Access Framework into app-private
 * cache storage.
 *
 * SAF URIs are transient and may point at a provider that disappears when the
 * process does, so the package store cannot reference them. Copying also gives
 * the installer random access for PMTiles header validation and a stable size
 * and digest for the manifest id.
 *
 * A basemap is potentially large, so the copy is streamed with a hard ceiling
 * rather than read into a ByteArray. The service also reports the source size
 * when the provider supplies it, which lets the caller reject an impossible
 * import before spending the copy time.
 */
internal object BasemapImport {

    /** Large enough for a regional PMTiles archive, small enough to bound abuse. */
    private const val MAX_ARCHIVE_BYTES = 2L * 1024 * 1024 * 1024

    /** The store enforces the same limit; this avoids staging an absurd style. */
    const val MAX_STYLE_BYTES = 2L * 1024 * 1024

    private const val CACHE_SUBDIR = "basemap-import"
    private const val CACHE_MAX_AGE_MS = 24L * 60L * 60L * 1000L

    enum class Kind { PMTILES, MBTILES, STYLE, UNKNOWN }

    data class StagedFile(
        val displayName: String,
        val file: File,
        val sizeBytes: Long,
        val sha256: String,
        val kind: Kind,
    )

    fun stage(context: Context, uri: Uri): StagedFile {
        val displayName = displayName(context, uri)
        val directory = File(context.cacheDir, CACHE_SUBDIR).apply { mkdirs() }
        pruneOldFiles(directory)
        val target = File.createTempFile("import-", ".bin", directory)
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        try {
            val source = context.contentResolver.openInputStream(uri)
                ?: throw IOException("the provider did not return content for $displayName")
            source.use { input ->
                target.outputStream().buffered().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_ARCHIVE_BYTES) {
                            throw IOException(
                                "$displayName is larger than the " +
                                    "${MAX_ARCHIVE_BYTES / (1024 * 1024)} MiB import limit",
                            )
                        }
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                    }
                }
            }
            return StagedFile(
                displayName = displayName,
                file = target,
                sizeBytes = total,
                sha256 = digest.digest().toHex(),
                kind = kindOf(target),
            )
        } catch (error: Exception) {
            target.delete()
            throw error
        }
    }

    /** Best-effort cleanup of imports abandoned by a crash or process death. */
    private fun pruneOldFiles(directory: File) {
        val cutoff = System.currentTimeMillis() - CACHE_MAX_AGE_MS
        directory.listFiles()?.forEach { file ->
            if (file.isFile && file.lastModified() < cutoff) file.delete()
        }
    }

    private fun kindOf(file: File): Kind {
        val prefix = ByteArray(16)
        val read = file.inputStream().use { it.read(prefix) }
        if (read <= 0) return Kind.UNKNOWN
        if (read >= 7 && prefix.copyOfRange(0, 7).contentEquals(PMTILES_MAGIC)) {
            return Kind.PMTILES
        }
        if (read >= SQLITE_MAGIC.size &&
            prefix.copyOfRange(0, SQLITE_MAGIC.size).contentEquals(SQLITE_MAGIC)
        ) {
            return Kind.MBTILES
        }
        var index = 0
        if (read >= 3 &&
            prefix[0] == 0xEF.toByte() &&
            prefix[1] == 0xBB.toByte() &&
            prefix[2] == 0xBF.toByte()
        ) {
            index = 3
        }
        while (index < read && prefix[index].toInt().toChar().isWhitespace()) index++
        if (index < read && prefix[index] == '{'.code.toByte()) return Kind.STYLE
        return Kind.UNKNOWN
    }

    private fun displayName(context: Context, uri: Uri): String {
        val projection = arrayOf(OpenableColumns.DISPLAY_NAME)
        runCatching {
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) {
                        cursor.getString(index)?.takeIf { it.isNotBlank() }?.let { return it }
                    }
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "import"
    }

    private fun ByteArray.toHex(): String {
        val digits = "0123456789abcdef"
        val out = StringBuilder(size * 2)
        for (byte in this) {
            val value = byte.toInt() and 0xFF
            out.append(digits[value ushr 4]).append(digits[value and 0x0F])
        }
        return out.toString()
    }

    private val PMTILES_MAGIC = "PMTiles".toByteArray(Charsets.US_ASCII)
    private val SQLITE_MAGIC = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
}
