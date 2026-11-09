package com.intellitrip.offline

/**
 * An installed offline basemap package.
 *
 * A package is a vector tile archive (`.mbtiles`) plus the style it should be
 * rendered with. Both are required: tiles without a style render as nothing, and a
 * style without tiles has nothing to draw.
 *
 * [id] is untrusted input when it comes from an imported file, so it is never used
 * to build a path. See [OfflineBasemapStore.storageNameFor].
 *
 * [tilePath] is an opaque store path string (see [OfflineFileStore]). It is only
 * meaningful to the [OfflineFileStore] that produced it; hosts that need a
 * platform file must resolve it through their own store implementation.
 */
data class BasemapPackage(
    val id: String,
    val displayName: String,
    val tilePath: String,
    val styleJson: String,
    val sizeBytes: Long,
    val minZoom: Int,
    val maxZoom: Int,
)

/** Outcome of an install attempt; failures are explicit, never silently ignored. */
sealed class InstallResult {
    data class Installed(val pkg: BasemapPackage) : InstallResult()

    data class Rejected(val reason: String) : InstallResult()
}

/**
 * Installs and tracks offline basemap packages.
 *
 * Design note: this owns *package lifecycle*, not tile serving. MapLibre reads the
 * installed file directly; this class decides whether a package exists, is valid,
 * and should be preferred over the online style. Keeping them separate means the
 * provider-neutral module stays free of any renderer dependency, which is what
 * [BasemapPolicy] needs.
 *
 * Free and key-free by design: packages are produced from OpenStreetMap data, whose
 * license permits this. No tile vendor account is needed, which is the whole point —
 * the previous approach was blocked on commercial API keys.
 *
 * [rootDir] is the store root as an opaque [OfflineFileStore] path string. JVM
 * hosts previously passed a platform Path; pass its string form instead.
 */
class OfflineBasemapStore(
    private val rootDir: String,
    private val fileStore: OfflineFileStore = OfflineFileStore(rootDir),
    /** Smallest package accepted; anything under this is a stub or a failed download. */
    private val minimumBytes: Long = 1024L,
) {

    private fun manifestPath(): String = fileStore.resolvePath(rootDir, MANIFEST)

    fun packages(): List<BasemapPackage> {
        if (!fileStore.exists(manifestPath())) return emptyList()
        return runCatching { fileStore.readLines(manifestPath()) }
            .getOrDefault(emptyList())
            .mapNotNull { parseManifestLine(it) }
            // A package whose file vanished is not installed, whatever the manifest says.
            .filter { fileStore.exists(it.tilePath) }
            .sortedBy { it.displayName }
    }

    /**
     * Installs a package from a staged file.
     *
     * The file is copied into the store rather than referenced in place so the app
     * owns the only durable copy; a caller passing a temp file would otherwise see it
     * disappear on reboot.
     *
     * [stagedPath] is an opaque [OfflineFileStore] path string. [sizeBytes] defaults
     * to the staged file's size (use a negative value to have it computed); pass an
     * explicit non-negative size when the host already knows it.
     */
    fun install(
        id: String,
        displayName: String,
        stagedPath: String,
        styleJson: String,
        sizeBytes: Long = -1L,
        minZoom: Int = 0,
        maxZoom: Int = 16,
    ): InstallResult {
        val actualSize = if (sizeBytes >= 0) sizeBytes else fileStore.size(stagedPath)
        if (actualSize < minimumBytes) {
            return InstallResult.Rejected(
                "package is ${actualSize} bytes, below the ${minimumBytes}-byte minimum",
            )
        }
        if (styleJson.isBlank()) {
            return InstallResult.Rejected("package has no style; tiles alone cannot render")
        }
        // The id is attacker-controlled if it comes from an imported file, so it is
        // never used as a path. A content-addressed name is derived from it instead,
        // which removes traversal and separator tricks by construction rather than
        // by trying to filter them out.
        val storageName = storageNameFor(id)
        return try {
            fileStore.ensureDir(rootDir)
            val destination = fileStore.resolvePath(rootDir, storageName)
            fileStore.copy(stagedPath, destination)
            fileStore.writeText(fileStore.resolvePath(rootDir, storageName + STYLE_SUFFIX), styleJson)
            val pkg = BasemapPackage(
                id = id,
                displayName = displayName,
                tilePath = destination,
                styleJson = styleJson,
                sizeBytes = actualSize,
                minZoom = minZoom,
                maxZoom = maxZoom,
            )
            writeManifest(pkg)
            InstallResult.Installed(pkg)
        } catch (e: Exception) {
            InstallResult.Rejected("could not store package: ${e.message}")
        }
    }

    fun remove(id: String): Boolean {
        val pkg = packages().firstOrNull { it.id == id } ?: return false
        runCatching { fileStore.deleteIfExists(pkg.tilePath) }
        rewriteManifest(packages().filterNot { it.id == id })
        return true
    }

    /** Total installed size, for the storage line in the UI. */
    fun installedBytes(): Long = packages().sumOf { it.sizeBytes }

    /** Maps directly onto [BasemapPolicy]'s installed flag. */
    fun hasPackage(): Boolean = packages().isNotEmpty()

    private fun writeManifest(pkg: BasemapPackage) {
        rewriteManifest(packages().filterNot { it.id == pkg.id } + pkg)
    }

    private fun rewriteManifest(entries: List<BasemapPackage>) {
        fileStore.ensureDir(rootDir)
        fileStore.writeLines(
            manifestPath(),
            entries.map {
                // Free-text fields are Base64 encoded. A delimiter-separated format
                // cannot carry arbitrary text safely: a display name containing the
                // separator would split into extra fields and the whole record would
                // be lost on reload. Encoding removes the delimiter from the alphabet.
                listOf(
                    encode(it.id),
                    encode(it.displayName),
                    fileStore.fileName(it.tilePath) ?: "",
                    it.sizeBytes.toString(),
                    it.minZoom.toString(),
                    it.maxZoom.toString(),
                ).joinToString(FIELD_SEPARATOR)
            },
        )
    }

    private fun encode(value: String): String =
        base64Encode(value.encodeToByteArray())

    private fun decode(value: String): String =
        base64Decode(value).decodeToString()

    private fun parseManifestLine(line: String): BasemapPackage? {
        if (line.isBlank()) return null
        val parts = line.split(FIELD_SEPARATOR)
        if (parts.size < 6) return null
        return runCatching {
            // Resolve, then normalize and verify containment. A manifest is a file on
            // disk and is therefore editable; an entry pointing outside the store is
            // rejected rather than trusted.
            val resolved = fileStore.normalizePath(fileStore.resolvePath(rootDir, parts[2]))
            require(fileStore.isContained(rootDir, resolved)) { "manifest entry escapes the store root" }
            BasemapPackage(
                id = decode(parts[0]),
                displayName = decode(parts[1]),
                tilePath = resolved,
                sizeBytes = parts[3].toLong(),
                minZoom = parts[4].toInt(),
                maxZoom = parts[5].toInt(),
                styleJson = STYLE_PLACEHOLDER,
            )
        }.getOrNull()
    }

    /**
     * Styles are held in their own file rather than the manifest line so the manifest
     * stays one-line-per-package and trivially parseable.
     *
     * The style is addressed by the stored tile file's name, never by the package id,
     * so a hostile id in the manifest cannot point the read outside the store.
     */
    fun styleFor(pkg: BasemapPackage): String? {
        val name = fileStore.fileName(pkg.tilePath) ?: return null
        val stylePath = fileStore.resolvePath(rootDir, name + STYLE_SUFFIX)
        return runCatching { fileStore.readText(stylePath) }.getOrNull()
    }

    /** Exposes the store root to security tests without widening the public API. */
    internal fun rootForTest(): String = rootDir

    /**
     * Derives an opaque storage filename from a package id.
     *
     * The previous implementation was `id.replace("[^A-Za-z0-9._-]", "_")`, which
     * looks like a sanitizer but is a *literal* string replacement in Kotlin: it
     * replaced the regex source text itself and left `../` intact. Any path built
     * from that could escape the store root.
     *
     * Hashing sidesteps the whole class of problem. Nothing from the id reaches the
     * filesystem, so there is no separator to escape with, and two ids cannot collide
     * onto one file. The id itself is kept in the manifest as metadata only.
     */
    private fun storageNameFor(id: String): String =
        "pkg-" + sha256Hex(id.encodeToByteArray()).take(24) + PACKAGE_EXTENSION

    private companion object {
        const val MANIFEST = "basemap-manifest.txt"

        /**
         * ASCII unit separator (U+001F), built from its code point rather than
         * written as a literal control character.
         *
         * A literal `"\u001F"` was silently destroyed by a text-rewrite pass, which
         * left the separator empty: `joinToString("")` concatenated every field and
         * `split("")` could not recover them, so no installed package could be read
         * back. Deriving it from the code point cannot be emptied by an editor, and
         * states the intent plainly in review.
         */
        val FIELD_SEPARATOR: String = 0x1F.toChar().toString()

        const val STYLE_SUFFIX = ".style.json"
        const val STYLE_PLACEHOLDER = ""

        /** Tile archives this store accepts. */
        const val PACKAGE_EXTENSION = ".mbtiles"
    }
}
