package com.intellitrip.gtfs

import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * Filesystem implementation of [PlatformFileStore] on `java.nio`.
 * Behavior matches the old direct `Files.*` calls: writes truncate, moves
 * replace, deletes remove a directory tree.
 */
actual class PlatformFileStore actual constructor(rootPath: String) {
    actual val rootPath: String = rootPath

    actual fun resolve(relative: String): String {
        val root = rootPath.trimEnd('/')
        if (relative.isEmpty()) return root
        val rel = relative.trimStart('/')
        return if (root.isEmpty()) rel else "$root/$rel"
    }

    actual fun exists(path: String): Boolean = Files.exists(Paths.get(path))

    actual fun isDirectory(path: String): Boolean = Files.isDirectory(Paths.get(path))

    actual fun createDirectories(path: String) {
        Files.createDirectories(Paths.get(path))
    }

    actual fun writeBytes(path: String, bytes: ByteArray) {
        val target = Paths.get(path)
        target.parent?.let { Files.createDirectories(it) }
        Files.write(target, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
    }

    actual fun readBytes(path: String): ByteArray = Files.readAllBytes(Paths.get(path))

    actual fun deleteIfExists(path: String) {
        val target = Paths.get(path)
        if (!Files.exists(target)) return
        if (Files.isDirectory(target)) {
            Files.walk(target).use { stream ->
                stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        } else {
            Files.deleteIfExists(target)
        }
    }

    actual fun move(sourcePath: String, targetPath: String, atomic: Boolean) {
        val target = Paths.get(targetPath)
        target.parent?.let { Files.createDirectories(it) }
        val options = ArrayList<StandardCopyOption>()
        options += StandardCopyOption.REPLACE_EXISTING
        if (atomic) options += StandardCopyOption.ATOMIC_MOVE
        Files.move(Paths.get(sourcePath), target, *options.toTypedArray())
    }

    actual fun listNames(path: String): List<String> {
        val dir = Paths.get(path)
        if (!Files.isDirectory(dir)) return emptyList()
        Files.list(dir).use { stream -> return stream.map { it.fileName.toString() }.toList().sorted() }
    }
}
