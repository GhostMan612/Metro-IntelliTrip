package com.intellitrip.offline

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The store's package ids may originate from an imported file, so they are untrusted
 * input. These tests exist because the original sanitizer looked correct and was
 * not: `id.replace("[^A-Za-z0-9._-]", "_")` is a literal replacement in Kotlin, not
 * a regex, so it left traversal sequences intact.
 *
 * The round-trip tests are equally load-bearing. A manifest separator regression
 * once left `FIELD_SEPARATOR` empty, which made every write silently unreadable
 * while the traversal tests still passed — they never reloaded a package.
 */
class OfflineBasemapStoreSecurityTest {

    private fun store(): OfflineBasemapStore =
        OfflineBasemapStore(Files.createTempDirectory("basemap-sec").toString())

    private fun staged(bytes: Int = 4096): Path {
        val file = Files.createTempFile("stage", ".mbtiles")
        Files.write(file, ByteArray(bytes) { 0x42 })
        return file
    }

    private val style = """{"version":8,"sources":{}}"""

    /** Matches the store manifest separator. */
    private val SEP = 0x1F.toChar().toString()

    // --- round-trip: a legitimate package must survive being written and read back

    @Test
    fun installedPackageSurvivesManifestRoundTrip() {
        val store = store()
        val installed = store.install("metro", "Twin Cities", staged().toString(), style)
        assertTrue(installed is InstallResult.Installed, "install failed: $installed")

        // A fresh store object reading the same root is equivalent to a relaunch.
        val reloaded = OfflineBasemapStore(store.rootForTest()).packages()
        assertEquals(1, reloaded.size, "package did not survive the manifest round trip")
        assertEquals("metro", reloaded.single().id)
        assertEquals("Twin Cities", reloaded.single().displayName)
        assertEquals(installed.pkg.tilePath, reloaded.single().tilePath)
        assertEquals(4096L, reloaded.single().sizeBytes)
        assertEquals(0, reloaded.single().minZoom)
        assertEquals(16, reloaded.single().maxZoom)
    }

    @Test
    fun styleSurvivesTheRoundTrip() {
        val store = store()
        val installed = store.install("metro", "Twin Cities", staged().toString(), style)
        assertTrue(installed is InstallResult.Installed)
        val reloaded = OfflineBasemapStore(store.rootForTest())
        val pkg = reloaded.packages().single()
        assertEquals(style, reloaded.styleFor(pkg))
    }

    @Test
    fun multiplePackagesAllSurvive() {
        val store = store()
        store.install("a", "Alpha", staged().toString(), style)
        store.install("b", "Bravo", staged(8192).toString(), style)
        store.install("c", "Charlie", staged(16384).toString(), style)

        val reloaded = OfflineBasemapStore(store.rootForTest()).packages()
        assertEquals(3, reloaded.size)
        assertEquals(setOf("a", "b", "c"), reloaded.map { it.id }.toSet())
        // Distinct sizes prove the fields did not bleed into one another.
        assertEquals(setOf(4096L, 8192L, 16384L), reloaded.map { it.sizeBytes }.toSet())
    }

    @Test
    fun removalPersistsAcrossRelaunch() {
        val store = store()
        store.install("keep", "Keep", staged().toString(), style)
        store.install("drop", "Drop", staged().toString(), style)
        assertEquals(2, store.packages().size)

        assertTrue(store.remove("drop"))
        assertEquals(1, OfflineBasemapStore(store.rootForTest()).packages().size)
        assertEquals("keep", store.packages().single().id)
    }

    @Test
    fun removalDeletesTileArchiveAndPairedStyleWithoutTouchingAnotherPackage() {
        val store = store()
        val drop = store.install("drop", "Drop", staged().toString(), style) as InstallResult.Installed
        val keep = store.install("keep", "Keep", staged().toString(), style) as InstallResult.Installed
        val droppedTile = Paths.get(drop.pkg.tilePath)
        val droppedStyle = Paths.get(drop.pkg.tilePath + ".style.json")
        val keptTile = Paths.get(keep.pkg.tilePath)
        val keptStyle = Paths.get(keep.pkg.tilePath + ".style.json")
        assertTrue(Files.exists(droppedTile))
        assertTrue(Files.exists(droppedStyle))

        assertTrue(store.remove("drop"))
        assertTrue(!Files.exists(droppedTile), "tile archive leaked on removal")
        assertTrue(!Files.exists(droppedStyle), "style file leaked on removal")
        assertTrue(Files.exists(keptTile), "unrelated tile archive was deleted")
        assertTrue(Files.exists(keptStyle), "unrelated style was deleted")
        assertEquals(listOf("keep"), OfflineBasemapStore(store.rootForTest()).packages().map { it.id })
        assertTrue(!store.remove("drop"), "removing an absent package must not claim success")
    }

    @Test
    fun reinstallOfTheSameIdReplacesRatherThanDuplicates() {
        val store = store()
        store.install("metro", "First", staged().toString(), style)
        store.install("metro", "Second", staged(8192).toString(), style)

        val reloaded = OfflineBasemapStore(store.rootForTest()).packages()
        assertEquals(1, reloaded.size, "reinstall created a duplicate entry")
        assertEquals("Second", reloaded.single().displayName)
    }

    @Test
    fun displayNamesContainingSeparatorsSurviveIntact() {
        val store = store()
        // A real-world name could contain anything; it must not corrupt the record.
        val awkward = "Twin Cities $SEP" + "metro"
        val installed = store.install("metro", awkward, staged().toString(), style)
        assertTrue(installed is InstallResult.Installed)
        val reloaded = OfflineBasemapStore(store.rootForTest()).packages().single()
        assertEquals(awkward, reloaded.displayName)
    }

    // --- containment: untrusted ids must not escape the store root

    @Test
    fun traversalIdentifiersDoNotEscapeTheStoreRoot() {
        val store = store()
        val hostile = listOf(
            "../escape",
            "../../escape",
            "..",
            "a/../../escape",
            "sub/dir",
            "..\\windows",
        )
        val root = Paths.get(store.rootForTest()).normalize()
        hostile.forEach { id ->
            when (val result = store.install(id, "Hostile", staged().toString(), style)) {
                is InstallResult.Installed -> assertTrue(
                    Paths.get(result.pkg.tilePath).normalize().startsWith(root),
                    "$id escaped to ${result.pkg.tilePath}",
                )
                is InstallResult.Rejected -> Unit
            }
        }
        // Every entry that was written must still be readable and still be contained.
        OfflineBasemapStore(store.rootForTest()).packages().forEach { pkg ->
            assertTrue(Paths.get(pkg.tilePath).normalize().startsWith(root), "reload escaped: ${pkg.tilePath}")
        }
    }

    @Test
    fun storedNamesAreOpaqueAndContainNoPathSeparators() {
        val store = store()
        val result = store.install("../evil/id", "Evil", staged().toString(), style)
        assertNotNull(result as? InstallResult.Installed)
        val name = Paths.get(result.pkg.tilePath).fileName.toString()
        assertTrue(name.startsWith("pkg-"), "expected an opaque name, got $name")
        assertTrue('/' !in name && '\\' !in name, "name contained a separator: $name")
        assertTrue(".." !in name, "name contained a traversal sequence: $name")
    }

    @Test
    fun distinctIdentifiersProduceDistinctStorage() {
        val store = store()
        val a = store.install("a/b", "One", staged().toString(), style) as InstallResult.Installed
        val b = store.install("a\\b", "Two", staged().toString(), style) as InstallResult.Installed
        assertTrue(
            a.pkg.tilePath != b.pkg.tilePath,
            "distinct ids collided onto one file",
        )
        assertEquals(2, OfflineBasemapStore(store.rootForTest()).packages().size)
    }

    @Test
    fun manifestEntriesPointingOutsideTheStoreAreIgnored() {
        val store = store()
        val installed = store.install("good", "Good", staged().toString(), style)
        assertTrue(installed is InstallResult.Installed)
        val sep = SEP
        val good = listOf(
            java.util.Base64.getEncoder().encodeToString(installed.pkg.id.toByteArray()),
            java.util.Base64.getEncoder().encodeToString(installed.pkg.displayName.toByteArray()),
            Paths.get(installed.pkg.tilePath).fileName.toString(),
            installed.pkg.sizeBytes.toString(),
            installed.pkg.minZoom.toString(),
            installed.pkg.maxZoom.toString(),
        ).joinToString(sep)

        Files.writeString(
            Paths.get(store.rootForTest()).resolve("basemap-manifest.txt"),
            listOf(
                listOf("ZXZpbA==", "RXZpbA==", "../../../../etc/passwd", "10", "0", "14").joinToString(sep),
                good,
            ).joinToString("\n"),
        )

        val packages = OfflineBasemapStore(store.rootForTest()).packages()
        assertTrue(
            packages.none { Paths.get(it.tilePath).normalize().toString().contains("passwd") },
            "a traversing manifest entry was accepted: ${packages.map { it.tilePath }}",
        )
        assertEquals(1, packages.size, "the legitimate entry should still load")
    }

    @Test
    fun manifestEntryForAVanishedFileIsNotReportedInstalled() {
        val store = store()
        val installed = store.install("gone", "Gone", staged().toString(), style)
        assertTrue(installed is InstallResult.Installed)
        Files.delete(Paths.get(installed.pkg.tilePath))
        assertTrue(store.packages().none { it.id == "gone" }, "deleted package still reported installed")
        assertTrue(!store.hasPackage())
    }
}
