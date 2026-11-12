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

    /**
     * Reads at most [length] bytes starting at [offset]. Returns fewer bytes when
     * the range reaches EOF; returns an empty array when [offset] is at or past
     * EOF. Used for PMTiles header validation without loading a multi-hundred-
     * megabyte archive into memory.
     */
    fun readRange(path: String, offset: Long, length: Int): ByteArray

    fun writeText(path: String, text: String)

    fun writeLines(path: String, lines: List<String>)

    fun writeBytes(path: String, bytes: ByteArray)

    /** Copies [source] to [dest], replacing [dest] if it exists. */
    fun copy(source: String, dest: String)

    /**
     * Moves [source] to [dest], replacing [dest] when possible.
     *
     * The JVM actual requests an atomic move and falls back to a normal
     * replacement only when the filesystem cannot provide one. The store uses
     * this to publish a package generation and the manifest: a crash either
     * leaves the previous generation active or the new one fully present, never
     * a half-written manifest.
     */
    fun move(source: String, dest: String)

    /**
     * A `file://` URI for [path], or null when the platform has no addressable
     * local files (the wasmJs in-memory store). MapLibre Native needs the full
     * URI inside a `pmtiles://` source.
     */
    fun fileUri(path: String): String?

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
