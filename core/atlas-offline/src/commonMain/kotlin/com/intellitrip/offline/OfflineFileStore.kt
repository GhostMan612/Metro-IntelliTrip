package com.intellitrip.offline

/**
 * Portable file-store seam behind [OfflineBasemapStore].
 *
 * The JVM actual is backed by the platform Files API; the wasmJs actual is an
 * in-memory stub (a browser host that wants durable offline packages supplies
 * its own persisted implementation). Paths are opaque strings: callers must
 * build them only with [resolvePath] and query them only through this store,
 * never by string concatenation, so platform separators cannot leak through.
 *
 * Text is always UTF-8.
 *
 * Why two file seams (this vs `PlatformFileStore` in atlas-gtfs-static):
 * the basemap seam needs path-containment/security (`isContained`,
 * `normalizePath`, `copy`) for untrusted package ids, while the gtfs seam
 * needs atomic snapshot moves + byte-range indexing. Merging them would
 * widen both APIs for no win; keep both, each with its own tests.
 */
expect class OfflineFileStore(rootDir: String) {

    /** Creates [dir] (and parents) if needed. */
    fun ensureDir(dir: String)

    fun exists(path: String): Boolean

    fun readLines(path: String): List<String>

    fun readText(path: String): String

    fun readBytes(path: String): ByteArray

    fun writeText(path: String, text: String)

    fun writeLines(path: String, lines: List<String>)

    fun writeBytes(path: String, bytes: ByteArray)

    /** Copies [source] to [dest], replacing [dest] if it exists. */
    fun copy(source: String, dest: String)

    fun size(path: String): Long

    fun deleteIfExists(path: String)

    /** `parent.resolve(child)`: an absolute [child] resolves to itself. */
    fun resolvePath(parent: String, child: String): String

    /** The final path segment, or null for a root/empty path. */
    fun fileName(path: String): String?

    /** Lexical `.`/`..` normalization (no filesystem access). */
    fun normalizePath(path: String): String

    /** True when [path] (after normalization) is [root] itself or under it. */
    fun isContained(root: String, path: String): Boolean
}
