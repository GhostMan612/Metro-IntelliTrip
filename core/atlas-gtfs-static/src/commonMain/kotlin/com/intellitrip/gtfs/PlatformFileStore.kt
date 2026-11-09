package com.intellitrip.gtfs

/**
 * Platform file store: the single I/O seam behind snapshot storage, the
 * stop-time index and the binary cache.
 *
 * Paths are strings so this can be written once in common code: the JVM
 * actual maps them to the filesystem, the wasmJs actual keeps an in-memory
 * map (IndexedDB is a later follow-up). Callers build child paths with
 * [resolve]; every other method takes paths verbatim (a path returned by
 * [resolve] is accepted by all of them). Separators are always `'/'`.
 *
 * Methods throw on I/O failure as platform exceptions; common callers
 * catch broadly (see [StaticFeedSnapshotStore]).
 */
expect class PlatformFileStore(rootPath: String) {
    val rootPath: String
    /** Joins [relative] under [rootPath]; [relative] may itself contain `'/'`. */
    fun resolve(relative: String): String

    /** True when [path] exists (file or directory). */
    fun exists(path: String): Boolean

    /** True when [path] exists and is a directory. */
    fun isDirectory(path: String): Boolean

    /** Creates [path] and any missing ancestors. */
    fun createDirectories(path: String)

    /** Writes [bytes] to [path], creating parent directories first. */
    fun writeBytes(path: String, bytes: ByteArray)

    /** Reads the whole file at [path]. */
    fun readBytes(path: String): ByteArray

    /**
     * Deletes [path]: a file, or a directory together with its contents.
     * Missing paths are silently ignored (mirroring
     * `Files.deleteIfExists` + tree delete in the old store).
     */
    fun deleteIfExists(path: String)

    /** Moves [sourcePath] to [targetPath], replacing an existing target. */
    fun move(sourcePath: String, targetPath: String, atomic: Boolean)

    /** Names of the direct children of directory [path]; empty when missing. */
    fun listNames(path: String): List<String>
}
