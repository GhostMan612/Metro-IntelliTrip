package com.intellitrip.gtfs

/**
 * wasmJs actual: an in-memory map keyed by store path (IndexedDB is a later
 * follow-up). Directories are implicit in path prefixes; `createDirectories`
 * only records them for `exists`/`isDirectory`.
 */
actual class PlatformFileStore actual constructor(rootPath: String) {
    actual val rootPath: String = rootPath

    private val files = HashMap<String, ByteArray>()
    private val dirs = HashSet<String>()

    actual fun resolve(relative: String): String {
        val root = rootPath.trimEnd('/')
        if (relative.isEmpty()) return root
        val rel = relative.trimStart('/')
        return if (root.isEmpty()) rel else "$root/$rel"
    }

    actual fun exists(path: String): Boolean = files.containsKey(path) || isDirectory(path)

    actual fun isDirectory(path: String): Boolean {
        if (dirs.contains(path)) return true
        val prefix = "$path/"
        return files.keys.any { it.startsWith(prefix) } || dirs.any { it.startsWith(prefix) }
    }

    actual fun createDirectories(path: String) {
        if (path.isEmpty()) return
        // Record the path and every ancestor so isDirectory/listNames see a
        // real (if virtual) directory tree.
        var current = path
        while (current.isNotEmpty()) {
            dirs.add(current)
            val slash = current.lastIndexOf('/')
            if (slash <= 0) break
            current = current.substring(0, slash)
        }
    }

    actual fun writeBytes(path: String, bytes: ByteArray) {
        val parent = path.substringBeforeLast('/', "")
        if (parent.isNotEmpty()) createDirectories(parent)
        files[path] = bytes.copyOf()
    }

    actual fun readBytes(path: String): ByteArray =
        files[path] ?: throw IllegalStateException("missing file: $path")

    actual fun deleteIfExists(path: String) {
        val prefix = "$path/"
        files.keys.removeAll { it == path || it.startsWith(prefix) }
        dirs.removeAll { it == path || it.startsWith(prefix) }
    }

    actual fun move(sourcePath: String, targetPath: String, atomic: Boolean) {
        val data = files.remove(sourcePath)
            ?: throw IllegalStateException("missing move source: $sourcePath")
        val parent = targetPath.substringBeforeLast('/', "")
        if (parent.isNotEmpty()) createDirectories(parent)
        files[targetPath] = data
    }

    actual fun listNames(path: String): List<String> {
        val prefix = "$path/"
        val children = LinkedHashSet<String>()
        dirs.forEach {
            if (it.startsWith(prefix)) children += it.removePrefix(prefix).substringBefore('/')
        }
        files.keys.forEach {
            if (it.startsWith(prefix)) children += it.removePrefix(prefix).substringBefore('/')
        }
        return children.toList()
    }
}
