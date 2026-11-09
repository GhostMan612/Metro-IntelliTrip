package com.intellitrip.offline

/**
 * wasmJs actual for [OfflineFileStore]: an in-memory stub.
 *
 * The browser has no durable filesystem, so this stores files in a process-wide
 * map keyed by normalized path strings. A host that needs durable offline
 * packages on wasmJs must supply its own persisted implementation (e.g. an
 * IndexedDB-backed store) behind the same seam; this stub exists so the
 * portable facade compiles and can be exercised in tests.
 */
actual class OfflineFileStore actual constructor(rootDir: String) {

    actual fun ensureDir(dir: String) {
        dirs.add(normalizePath(dir))
    }

    actual fun exists(path: String): Boolean =
        files.containsKey(normalizePath(path)) || dirs.contains(normalizePath(path))

    actual fun readLines(path: String): List<String> = readText(path).lines()

    actual fun readText(path: String): String {
        val bytes = files[normalizePath(path)]
            ?: throw IllegalArgumentException("no such file: $path")
        return bytes.decodeToString()
    }

    actual fun readBytes(path: String): ByteArray =
        files[normalizePath(path)] ?: throw IllegalArgumentException("no such file: $path")

    actual fun writeText(path: String, text: String) {
        files[normalizePath(path)] = text.encodeToByteArray()
    }

    actual fun writeLines(path: String, lines: List<String>) {
        writeText(path, lines.joinToString("\n"))
    }

    actual fun writeBytes(path: String, bytes: ByteArray) {
        files[normalizePath(path)] = bytes
    }

    actual fun copy(source: String, dest: String) {
        val bytes = files[normalizePath(source)]
            ?: throw IllegalArgumentException("no such file: $source")
        files[normalizePath(dest)] = bytes
    }

    actual fun size(path: String): Long =
        (files[normalizePath(path)] ?: throw IllegalArgumentException("no such file: $path")).size.toLong()

    actual fun deleteIfExists(path: String) {
        files.remove(normalizePath(path))
    }

    actual fun resolvePath(parent: String, child: String): String {
        val c = child.replace('\\', '/')
        if (c.startsWith("/")) return normalizePath(c)
        return normalizePath(normalizePath(parent) + "/" + c)
    }

    actual fun fileName(path: String): String? {
        val n = normalizePath(path)
        if (n.isEmpty() || n == "/") return null
        val name = n.substringAfterLast('/')
        return if (name.isEmpty()) null else name
    }

    actual fun normalizePath(path: String): String {
        val p = path.replace('\\', '/')
        val absolute = p.startsWith("/")
        val parts = mutableListOf<String>()
        for (seg in p.split('/')) {
            when (seg) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty() && parts.last() != "..") parts.removeAt(parts.lastIndex)
                else -> parts.add(seg)
            }
        }
        val joined = parts.joinToString("/")
        return if (absolute) "/$joined" else joined
    }

    actual fun isContained(root: String, path: String): Boolean {
        val r = normalizePath(root)
        val p = normalizePath(path)
        return p == r || p.startsWith("$r/")
    }

    private companion object {
        private val files = mutableMapOf<String, ByteArray>()
        private val dirs = mutableSetOf<String>()
    }
}
