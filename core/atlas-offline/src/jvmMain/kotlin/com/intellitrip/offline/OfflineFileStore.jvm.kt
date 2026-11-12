package com.intellitrip.offline

import java.io.RandomAccessFile
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

/**
 * JVM actual for [OfflineFileStore], backed by `java.nio.file.Files`.
 *
 * Text reads use only APIs available on Android: the previous implementation
 * notes that `Files.readString` is Java 11+ and missing on Android API 34, so
 * this uses the Java 7 `readAllBytes` for text too.
 */
actual class OfflineFileStore actual constructor(rootDir: String) {

    @Suppress("unused")
    private val root: Path = Paths.get(rootDir)

    actual fun ensureDir(dir: String) {
        Files.createDirectories(Paths.get(dir))
    }

    actual fun exists(path: String): Boolean = Files.exists(Paths.get(path))

    actual fun readLines(path: String): List<String> = Files.readAllLines(Paths.get(path))

    actual fun readText(path: String): String =
        String(Files.readAllBytes(Paths.get(path)), Charsets.UTF_8)

    actual fun readBytes(path: String): ByteArray = Files.readAllBytes(Paths.get(path))

    actual fun readRange(path: String, offset: Long, length: Int): ByteArray {
        require(offset >= 0L) { "offset must be non-negative" }
        require(length >= 0) { "length must be non-negative" }
        RandomAccessFile(Paths.get(path).toFile(), "r").use { file ->
            val size = file.length()
            if (offset >= size || length == 0) return ByteArray(0)
            val count = minOf(length.toLong(), size - offset).toInt()
            val bytes = ByteArray(count)
            file.seek(offset)
            file.readFully(bytes)
            return bytes
        }
    }

    actual fun writeText(path: String, text: String) {
        Files.write(Paths.get(path), text.toByteArray(Charsets.UTF_8))
    }

    actual fun writeLines(path: String, lines: List<String>) {
        Files.write(Paths.get(path), lines)
    }

    actual fun writeBytes(path: String, bytes: ByteArray) {
        Files.write(Paths.get(path), bytes)
    }

    actual fun copy(source: String, dest: String) {
        Files.copy(Paths.get(source), Paths.get(dest), StandardCopyOption.REPLACE_EXISTING)
    }

    actual fun move(source: String, dest: String) {
        val from = Paths.get(source)
        val to = Paths.get(dest)
        try {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: UnsupportedOperationException) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    actual fun fileUri(path: String): String? = Paths.get(path).toUri().toString()

    actual fun size(path: String): Long = Files.size(Paths.get(path))

    actual fun deleteIfExists(path: String) {
        Files.deleteIfExists(Paths.get(path))
    }

    actual fun resolvePath(parent: String, child: String): String =
        Paths.get(parent).resolve(child).toString()

    actual fun fileName(path: String): String? = Paths.get(path).fileName?.toString()

    actual fun normalizePath(path: String): String = Paths.get(path).normalize().toString()

    actual fun isContained(root: String, path: String): Boolean =
        Paths.get(path).normalize().startsWith(Paths.get(root).normalize())
}
