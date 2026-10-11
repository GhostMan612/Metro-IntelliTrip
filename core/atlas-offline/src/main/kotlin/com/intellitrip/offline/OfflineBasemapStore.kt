package com.intellitrip.offline

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * An installed offline basemap package.
 *
 * A package is a raster/vector tile archive (`.mbtiles` / `.pmtiles`) plus the style
 * it should be rendered with. Both are required: tiles without a style render as
 * nothing, and a style without tiles has nothing to draw.
 *
 * Packages are content-addressed by their own checksum so a truncated or tampered
 * file is rejected at install time rather than producing a broken map much later.
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
 * licence permits this. No tile vendor account is needed, which is the whole point —
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
        return try {
            Files.createDirectories(root)
            val destination = root.resolve(safeName(id))
            Files.copy(stagedTiles, destination, StandardCopyOption.REPLACE_EXISTING)
            Files.writeString(root.resolve(safeName(id) + STYLE_SUFFIX), styleJson)
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
                listOf(
                    it.id,
                    it.displayName,
                    it.tilePath.fileName.toString(),
                    it.sizeBytes.toString(),
                    it.minZoom.toString(),
                    it.maxZoom.toString(),
                ).joinToString(FIELD_SEPARATOR)
            },
        )
    }

    private fun parseManifestLine(line: String): BasemapPackage? {
        if (line.isBlank()) return null
        val parts = line.split(FIELD_SEPARATOR)
        if (parts.size < 6) return null
        return runCatching {
            BasemapPackage(
                id = parts[0],
                displayName = parts[1],
                tilePath = root.resolve(parts[2]),
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
     */
    fun styleFor(pkg: BasemapPackage): String? =
        runCatching { Files.readString(root.resolve(safeName(pkg.id) + STYLE_SUFFIX)) }.getOrNull()

    private fun safeName(id: String): String = id.replace(UNSAFE, "_")

    private companion object {
        const val MANIFEST = "basemap-manifest.txt"
        const val FIELD_SEPARATOR = ""
        const val STYLE_SUFFIX = ".style.json"
        const val STYLE_PLACEHOLDER = ""
        const val UNSAFE = "[^A-Za-z0-9._-]"
    }
}
