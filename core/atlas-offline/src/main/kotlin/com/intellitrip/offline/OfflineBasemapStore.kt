package com.intellitrip.offline

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Base64

/**
 * An installed offline basemap package.
 *
 * A package is a vector tile archive (`.mbtiles`) plus the style it should be
 * rendered with. Both are required: tiles without a style render as nothing, and a
 * style without tiles has nothing to draw.
 *
 * [id] is untrusted input when it comes from an imported file, so it is never used
 * to build a path. See [OfflineBasemapStore.storageNameFor].
 */
data class BasemapPackage(
    val id: String,
    val displayName: String,
    val tilePath: Path,
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
 */
class OfflineBasemapStore(
    private val root: Path,
    /** Smallest package accepted; anything under this is a stub or a failed download. */
    private val minimumBytes: Long = 1024L,
) {

    private val manifest get() = root.resolve(MANIFEST)

    fun packages(): List<BasemapPackage> {
        if (!Files.exists(manifest)) return emptyList()
        return runCatching { Files.readAllLines(manifest) }
            .getOrDefault(emptyList())
            .mapNotNull { parseManifestLine(it) }
            // A package whose file vanished is not installed, whatever the manifest says.
            .filter { Files.exists(it.tilePath) }
            .sortedBy { it.displayName }
    }

    /**
     * Installs a package from a staged file.
     *
     * The file is copied into the store rather than referenced in place so the app
     * owns the only durable copy; a caller passing a temp file would otherwise see it
     * disappear on reboot.
     */
    fun install(
        id: String,
        displayName: String,
        stagedTiles: Path,
        styleJson: String,
        sizeBytes: Long = Files.size(stagedTiles),
        minZoom: Int = 0,
        maxZoom: Int = 16,
    ): InstallResult {
        if (sizeBytes < minimumBytes) {
            return InstallResult.Rejected(
                "package is ${sizeBytes} bytes, below the ${minimumBytes}-byte minimum",
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
            Files.createDirectories(root)
            val destination = root.resolve(storageName)
            Files.copy(stagedTiles, destination, StandardCopyOption.REPLACE_EXISTING)
            Files.writeString(root.resolve(storageName + STYLE_SUFFIX), styleJson)
            val pkg = BasemapPackage(
                id = id,
                displayName = displayName,
                tilePath = destination,
                styleJson = styleJson,
                sizeBytes = sizeBytes,
                minZoom = minZoom,
                maxZoom = maxZoom,
            )
            writeManifest(pkg)
            InstallResult.Installed(pkg)
        } catch (e: IOException) {
            InstallResult.Rejected("could not store package: ${e.message}")
        }
    }

    fun remove(id: String): Boolean {
        val pkg = packages().firstOrNull { it.id == id } ?: return false
        runCatching { Files.deleteIfExists(pkg.tilePath) }
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
        Files.createDirectories(root)
        Files.write(
            manifest,
            entries.map {
                // Free-text fields are Base64 encoded. A delimiter-separated format
                // cannot carry arbitrary text safely: a display name containing the
                // separator would split into extra fields and the whole record would
                // be lost on reload. Encoding removes the delimiter from the alphabet.
                listOf(
                    encode(it.id),
                    encode(it.displayName),
                    it.tilePath.fileName.toString(),
                    it.sizeBytes.toString(),
                    it.minZoom.toString(),
                    it.maxZoom.toString(),
                ).joinToString(FIELD_SEPARATOR)
            },
        )
    }

    private fun encode(value: String): String =
        Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))

    private fun decode(value: String): String =
        String(Base64.getDecoder().decode(value), Charsets.UTF_8)

    private fun parseManifestLine(line: String): BasemapPackage? {
        if (line.isBlank()) return null
        val parts = line.split(FIELD_SEPARATOR)
        if (parts.size < 6) return null
        return runCatching {
            // Resolve, then normalize and verify containment. A manifest is a file on
            // disk and is therefore editable; an entry pointing outside the store is
            // rejected rather than trusted.
            val resolved = root.resolve(parts[2]).normalize()
            require(resolved.startsWith(root.normalize())) { "manifest entry escapes the store root" }
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
        val stylePath = pkg.tilePath.fileName?.toString()?.let { root.resolve(it + STYLE_SUFFIX) }
            ?: return null
        return runCatching { Files.readString(stylePath) }.getOrNull()
    }

    /** Exposes the store root to security tests without widening the public API. */
    internal fun rootForTest(): Path = root

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
    private fun storageNameFor(id: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(id.toByteArray(Charsets.UTF_8))
            .take(12)
            .joinToString("") { "%02x".format(it) }
        return "pkg-$digest$PACKAGE_EXTENSION"
    }

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
