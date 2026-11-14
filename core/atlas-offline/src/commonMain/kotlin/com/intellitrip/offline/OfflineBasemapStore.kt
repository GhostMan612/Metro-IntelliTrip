package com.intellitrip.offline

import kotlin.random.Random

/**
 * An installed offline basemap package.
 *
 * A package is a PMTiles v3 or MBTiles archive plus the MapLibre style it should be
 * rendered with. Both are required: tiles without a style render as nothing, and
 * a style without tiles has nothing to draw.
 *
 * [id] is untrusted input when it comes from an imported file, so it is never used
 * to build a path. See [OfflineBasemapStore.storageNameFor].
 *
 * [tilePath] is an opaque store path string (see [OfflineFileStore]). It is only
 * meaningful to the [OfflineFileStore] that produced it; hosts that need a
 * platform file must resolve it through their own store implementation.
 *
 * [styleJson] is the style exactly as imported, with its original
 * `pmtiles://...` or `mbtiles://...` URL. Use [OfflineBasemapStore.styleFor] to obtain the copy
 * bound to this package's installed archive.
 */
data class BasemapPackage(
    val id: String,
    val displayName: String,
    val tilePath: String,
    val styleJson: String,
    val sizeBytes: Long,
    val minZoom: Int,
    val maxZoom: Int,
    val bounds: BasemapBounds,
    val archiveFormat: BasemapArchiveFormat = BasemapArchiveFormat.PMTILES,
    val tileFormat: String = "pbf",
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
 * PMTiles v3 is read directly by MapLibre Native. MBTiles is served through a
 * loopback TileJSON endpoint supplied by the Android host; the common store only
 * accepts MBTiles after the host has validated its SQLite schema and metadata.
 *
 * Free and key-free by design: packages are produced from OpenStreetMap data,
 * whose license permits this. No tile vendor account is needed, which is the
 * whole point: the previous approach was blocked on commercial API keys.
 *
 * [rootDir] is the store root as an opaque [OfflineFileStore] path string. JVM
 * hosts previously passed a platform Path; pass its string form instead.
 */
class OfflineBasemapStore(
    private val rootDir: String,
    private val fileStore: OfflineFileStore = OfflineFileStore(rootDir),
    /** Smallest package accepted; anything under this is a stub or a failed download. */
    private val minimumBytes: Long = MINIMUM_PACKAGE_BYTES,
) {

    private fun manifestPath(): String = fileStore.resolvePath(rootDir, MANIFEST)

    /**
     * Packages whose archive and style are both present and whose manifest
     * record still passes validation. A manifest is editable state, not a
     * trusted index: an entry that escapes the store, names a missing file or
     * carries impossible metadata is silently dropped instead of being handed to
     * the renderer.
     */
    fun packages(): List<BasemapPackage> {
        if (!fileStore.exists(manifestPath())) return emptyList()
        return runCatching { fileStore.readLines(manifestPath()) }
            .getOrDefault(emptyList())
            .mapNotNull { parseManifestLine(it) }
            .filter { pkg ->
                fileStore.exists(pkg.tilePath) &&
                    stylePathFor(pkg.tilePath)?.let { fileStore.exists(it) } == true
            }
            .distinctBy { it.id }
            .sortedBy { it.displayName.lowercase() }
    }

    /**
     * Installs a PMTiles v3 package or host-validated MBTiles package.
     *
     * The file is copied into the store rather than referenced in place so the app
     * owns the only durable copy; a caller passing a temp file would otherwise see it
     * disappear on reboot.
     *
     * Installation is generation-based. The archive and style are written under a
     * fresh name, then the manifest is atomically replaced to point at that
     * generation. A crash before the manifest move leaves the previous generation
     * active; a crash after it leaves the new generation fully present. Orphaned
     * files from either half are harmless and are removed on the next successful
     * install of the same id.
     *
     * [stagedPath] is an opaque [OfflineFileStore] path string.
     */
    fun install(
        id: String,
        displayName: String,
        stagedPath: String,
        styleJson: String,
        mbtilesMetadata: MbtilesMetadata? = null,
    ): InstallResult {
        val packageId = id.trim()
        if (packageId.isEmpty()) {
            return InstallResult.Rejected("package id is empty")
        }
        if (packageId.length > MAX_ID_LENGTH) {
            return InstallResult.Rejected("package id is longer than $MAX_ID_LENGTH characters")
        }
        val name = displayName.trim()
        if (name.isEmpty()) {
            return InstallResult.Rejected("package has no display name")
        }
        if (name.length > MAX_DISPLAY_NAME) {
            return InstallResult.Rejected("display name is longer than $MAX_DISPLAY_NAME characters")
        }

        val actualSize = runCatching { fileStore.size(stagedPath) }.getOrElse { error ->
            return InstallResult.Rejected("could not read the staged archive: ${error.message}")
        }
        if (actualSize < minimumBytes) {
            return InstallResult.Rejected(
                "package is $actualSize bytes, below the $minimumBytes-byte minimum",
            )
        }

        val prefix = runCatching {
            fileStore.readRange(stagedPath, 0L, PmtilesFormat.HEADER_BYTES)
        }.getOrElse { error ->
            return InstallResult.Rejected("could not read the archive header: ${error.message}")
        }
        val archiveFormat = when (PmtilesFormat.kind(prefix)) {
            ArchiveKind.MBTILES -> {
                if (mbtilesMetadata == null) {
                    return InstallResult.Rejected(
                        "MBTiles schema and metadata must be validated before installation",
                    )
                }
                if (mbtilesMetadata.minZoom !in 0..24 ||
                    mbtilesMetadata.maxZoom !in mbtilesMetadata.minZoom..24 ||
                    mbtilesMetadata.tileFormat !in SUPPORTED_MBTILES_TILE_FORMATS ||
                    !mbtilesMetadata.bounds.isValid()
                ) {
                    return InstallResult.Rejected("MBTiles metadata has an unsupported format or zoom range")
                }
                BasemapArchiveFormat.MBTILES
            }
            ArchiveKind.UNKNOWN -> return InstallResult.Rejected(UNKNOWN_FORMAT_MESSAGE)
            ArchiveKind.PMTILES -> BasemapArchiveFormat.PMTILES
        }
        val header = if (archiveFormat == BasemapArchiveFormat.PMTILES) {
            when (val validation = PmtilesFormat.validate(prefix, actualSize)) {
                is PmtilesValidation.Invalid -> return InstallResult.Rejected(validation.reason)
                is PmtilesValidation.Valid -> validation.header
            }
        } else {
            null
        }

        when (val styleCheck = BasemapStyleValidator.validate(styleJson, archiveFormat)) {
            is BasemapStyleValidator.Result.Invalid -> return InstallResult.Rejected(styleCheck.reason)
            BasemapStyleValidator.Result.Valid -> Unit
        }

        val previous = packages().firstOrNull { it.id == packageId }
        runCatching { fileStore.ensureDir(rootDir) }.getOrElse { error ->
            return InstallResult.Rejected("could not create the basemap store: ${error.message}")
        }

        val storageName = storageNameFor(packageId, archiveFormat)
        val destination = fileStore.resolvePath(rootDir, storageName)
        val stylePath = fileStore.resolvePath(rootDir, storageName + STYLE_SUFFIX)
        val token = generationToken()
        val stagedTileCopy = "$destination.tmp-$token"
        val stagedStyleCopy = "$stylePath.tmp-$token"
        var committed = false

        return try {
            fileStore.copy(stagedPath, stagedTileCopy)
            val copiedSize = fileStore.size(stagedTileCopy)
            if (copiedSize != actualSize) {
                return InstallResult.Rejected(
                    "archive changed while importing ($actualSize bytes read, $copiedSize bytes copied)",
                )
            }
            fileStore.writeText(stagedStyleCopy, styleJson)
            fileStore.move(stagedTileCopy, destination)
            fileStore.move(stagedStyleCopy, stylePath)

            val pkg = BasemapPackage(
                id = packageId,
                displayName = name,
                tilePath = destination,
                styleJson = styleJson,
                sizeBytes = actualSize,
                minZoom = header?.minZoom ?: requireNotNull(mbtilesMetadata).minZoom,
                maxZoom = header?.maxZoom ?: requireNotNull(mbtilesMetadata).maxZoom,
                bounds = header?.let {
                    BasemapBounds(it.minLat, it.minLon, it.maxLat, it.maxLon)
                } ?: requireNotNull(mbtilesMetadata).bounds,
                archiveFormat = archiveFormat,
                tileFormat = mbtilesMetadata?.tileFormat ?: "pbf",
            )
            writeManifest(pkg)
            committed = true
            // The manifest is the commit point; only now is the previous
            // generation unreachable. Deleting it before the manifest move would
            // turn a failed install into data loss.
            if (previous != null && previous.tilePath != pkg.tilePath) {
                runCatching { fileStore.deleteIfExists(previous.tilePath) }
                stylePathFor(previous.tilePath)?.let { oldStyle ->
                    runCatching { fileStore.deleteIfExists(oldStyle) }
                }
            }
            InstallResult.Installed(pkg)
        } catch (e: Exception) {
            if (!committed) {
                runCatching { fileStore.deleteIfExists(destination) }
                runCatching { fileStore.deleteIfExists(stylePath) }
            }
            InstallResult.Rejected("could not store package: ${e.message ?: "I/O failure"}")
        } finally {
            runCatching { fileStore.deleteIfExists(stagedTileCopy) }
            runCatching { fileStore.deleteIfExists(stagedStyleCopy) }
        }
    }

    fun remove(id: String): Boolean {
        val installed = packages()
        val pkg = installed.firstOrNull { it.id == id } ?: return false
        val stylePath = stylePathFor(pkg.tilePath) ?: return false
        // Deleting the tile alone leaks the paired style on every uninstall.
        // Never report success when either delete or manifest persistence fails.
        return try {
            fileStore.deleteIfExists(pkg.tilePath)
            fileStore.deleteIfExists(stylePath)
            rewriteManifest(installed.filterNot { it.id == id })
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Total installed size, for the storage line in the UI. */
    fun installedBytes(): Long = packages().sumOf { it.sizeBytes }

    /** Maps directly onto [BasemapPolicy]'s installed flag. */
    fun hasPackage(): Boolean = packages().isNotEmpty()

    /**
     * The imported style with its offline source bound to this package.
     *
     * Returns null when the style file is missing/unreadable or the platform has
     * no addressable local files. Null means the package cannot be rendered;
     * MBTiles requires the host's local TileJSON URL; PMTiles is bound to the
     * installed archive URI.
     */
    fun styleFor(pkg: BasemapPackage, mbtilesTileJsonUrl: String? = null): String? {
        val stylePath = stylePathFor(pkg.tilePath) ?: return null
        val style = runCatching { fileStore.readText(stylePath) }.getOrNull() ?: return null
        if (BasemapStyleValidator.validate(style, pkg.archiveFormat) !is BasemapStyleValidator.Result.Valid) return null
        val archiveSize = runCatching { fileStore.size(pkg.tilePath) }.getOrNull() ?: return null
        val sourceUri = when (pkg.archiveFormat) {
            BasemapArchiveFormat.PMTILES -> {
                val header = runCatching {
                    fileStore.readRange(pkg.tilePath, 0L, PmtilesFormat.HEADER_BYTES)
                }.getOrNull() ?: return null
                if (PmtilesFormat.validate(header, archiveSize) !is PmtilesValidation.Valid) return null
                fileStore.fileUri(pkg.tilePath) ?: return null
            }
            BasemapArchiveFormat.MBTILES -> {
                if (archiveSize < minimumBytes) return null
                val prefix = runCatching {
                    fileStore.readRange(pkg.tilePath, 0L, PmtilesFormat.HEADER_BYTES)
                }.getOrNull() ?: return null
                if (PmtilesFormat.kind(prefix) != ArchiveKind.MBTILES) return null
                mbtilesTileJsonUrl?.takeIf { LOCAL_TILEJSON.matches(it) } ?: return null
            }
        }
        return BasemapStyleValidator.bindArchive(style, sourceUri, pkg.archiveFormat)
    }

    /** Exposes the store root to security tests without widening the public API. */
    internal fun rootForTest(): String = rootDir

    private fun stylePathFor(tilePath: String): String? {
        val name = fileStore.fileName(tilePath) ?: return null
        return fileStore.resolvePath(rootDir, name + STYLE_SUFFIX)
    }

    private fun writeManifest(pkg: BasemapPackage) {
        rewriteManifest(packages().filterNot { it.id == pkg.id } + pkg)
    }

    private fun rewriteManifest(entries: List<BasemapPackage>) {
        fileStore.ensureDir(rootDir)
        val text = entries
            .distinctBy { it.id }
            .map { pkg ->
                // Free-text fields are Base64 encoded. A delimiter-separated format
                // cannot carry arbitrary text safely: a display name containing the
                // separator would split into extra fields and the whole record would
                // be lost on reload.
                listOf(
                    encode(pkg.id),
                    encode(pkg.displayName),
                    fileStore.fileName(pkg.tilePath) ?: "",
                    pkg.sizeBytes.toString(),
                    pkg.minZoom.toString(),
                    pkg.maxZoom.toString(),
                    pkg.bounds.minLon.toString(),
                    pkg.bounds.minLat.toString(),
                    pkg.bounds.maxLon.toString(),
                    pkg.bounds.maxLat.toString(),
                    pkg.archiveFormat.name,
                    encode(pkg.tileFormat),
                ).joinToString(FIELD_SEPARATOR)
            }
            .joinToString("\n")
        val path = manifestPath()
        val temp = "$path.tmp-${generationToken()}"
        try {
            fileStore.writeText(temp, text)
            fileStore.move(temp, path)
        } finally {
            runCatching { fileStore.deleteIfExists(temp) }
        }
    }

    private fun encode(value: String): String =
        base64Encode(value.encodeToByteArray())

    private fun decode(value: String): String =
        base64Decode(value).decodeToString()

    private fun parseManifestLine(line: String): BasemapPackage? {
        if (line.isBlank()) return null
        val parts = line.split(FIELD_SEPARATOR)
        if (parts.size != LEGACY_FIELD_COUNT && parts.size != FIELD_COUNT) return null
        return runCatching {
            val fileName = parts[2]
            require(isPlainFileName(fileName)) { "manifest entry has an unsafe file name" }
            val archiveFormat = if (parts.size == LEGACY_FIELD_COUNT) {
                BasemapArchiveFormat.PMTILES
            } else {
                BasemapArchiveFormat.valueOf(parts[10])
            }
            val tileFormat = if (parts.size == LEGACY_FIELD_COUNT) "pbf" else decode(parts[11])
            require(
                (archiveFormat == BasemapArchiveFormat.PMTILES && fileName.endsWith(PMTILES_EXTENSION)) ||
                    (archiveFormat == BasemapArchiveFormat.MBTILES && fileName.endsWith(MBTILES_EXTENSION)),
            ) { "manifest archive extension does not match its format" }
            require(tileFormat in SUPPORTED_MBTILES_TILE_FORMATS) { "unsupported tile format" }
            // Resolve, then normalize and verify containment. A manifest is a file on
            // disk and is therefore editable; an entry pointing outside the store is
            // rejected rather than trusted.
            val resolved = fileStore.normalizePath(fileStore.resolvePath(rootDir, fileName))
            require(fileStore.isContained(rootDir, resolved)) {
                "manifest entry escapes the store root"
            }
            val sizeBytes = parts[3].toLong()
            require(sizeBytes >= 0L) { "negative package size" }
            val minZoom = parts[4].toInt()
            val maxZoom = parts[5].toInt()
            require(minZoom in 0..24 && maxZoom in minZoom..24) { "invalid zoom range" }
            val minLon = parts[6].toDouble()
            val minLat = parts[7].toDouble()
            val maxLon = parts[8].toDouble()
            val maxLat = parts[9].toDouble()
            require(minLon.isFinite() && minLat.isFinite() && maxLon.isFinite() && maxLat.isFinite())
            require(minLon <= maxLon && minLat <= maxLat) { "inverted bounds" }
            require(
                minLon >= -180.0 && maxLon <= 180.0 &&
                    minLat >= -90.0 && maxLat <= 90.0,
            ) { "bounds outside the valid lon/lat range" }
            val id = decode(parts[0])
            require(id.isNotBlank()) { "blank package id" }
            BasemapPackage(
                id = id,
                displayName = decode(parts[1]),
                tilePath = resolved,
                sizeBytes = sizeBytes,
                minZoom = minZoom,
                maxZoom = maxZoom,
                bounds = BasemapBounds(
                    minLat = minLat,
                    minLon = minLon,
                    maxLat = maxLat,
                    maxLon = maxLon,
                ),
                archiveFormat = archiveFormat,
                tileFormat = tileFormat,
                styleJson = STYLE_PLACEHOLDER,
            )
        }.getOrNull()
    }

    /**
     * Derives an opaque storage filename from a package id plus a fresh
     * generation token.
     *
     * The previous implementation was `id.replace("[^A-Za-z0-9._-]", "_")`, which
     * looks like a sanitizer but is a *literal* string replacement in Kotlin: it
     * replaced the regex source text itself and left `../` intact. Any path built
     * from that could escape the store root.
     *
     * Hashing sidesteps the whole class of problem. Nothing from the id reaches the
     * filesystem, so there is no separator to escape with, and two ids cannot
     * collide onto one file. The generation token gives every install its own
     * files so the manifest can switch from old to new atomically.
     */
    private fun storageNameFor(id: String, format: BasemapArchiveFormat): String {
        val hash = sha256Hex(id.encodeToByteArray()).take(24)
        val extension = when (format) {
            BasemapArchiveFormat.PMTILES -> PMTILES_EXTENSION
            BasemapArchiveFormat.MBTILES -> MBTILES_EXTENSION
        }
        return "pkg-$hash-${generationToken()}$extension"
    }

    private fun generationToken(): String =
        Random.nextLong().toULong().toString(16).padStart(16, '0')

    private fun isPlainFileName(name: String): Boolean =
        name.isNotBlank() &&
            name != "." &&
            name != ".." &&
            name.none { it == '/' || it == '\\' || it == '\u0000' } &&
            fileStore.fileName(name) == name

    private fun BasemapBounds.isValid(): Boolean =
        minLat.isFinite() && minLon.isFinite() && maxLat.isFinite() && maxLon.isFinite() &&
            minLon in -180.0..180.0 && maxLon in -180.0..180.0 &&
            minLat in -90.0..90.0 && maxLat in -90.0..90.0 &&
            minLon <= maxLon && minLat <= maxLat

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

        const val LEGACY_FIELD_COUNT = 10
        const val FIELD_COUNT = 12
        const val STYLE_SUFFIX = ".style.json"
        const val STYLE_PLACEHOLDER = ""
        const val PMTILES_EXTENSION = ".pmtiles"
        const val MBTILES_EXTENSION = ".mbtiles"
        val SUPPORTED_MBTILES_TILE_FORMATS = setOf("pbf", "png", "jpg", "jpeg", "webp")
        const val MINIMUM_PACKAGE_BYTES = 1024L
        const val MAX_ID_LENGTH = 256
        const val MAX_DISPLAY_NAME = 120
        val LOCAL_TILEJSON = Regex("^http://127\\.0\\.0\\.1:[0-9]{1,5}/[A-Za-z0-9]{32}/tilejson\\.json$")

        const val UNKNOWN_FORMAT_MESSAGE =
            "Not a PMTiles v3 or MBTiles archive. Import a .pmtiles or validated " +
                ".mbtiles file together with its MapLibre style JSON."
    }
}
