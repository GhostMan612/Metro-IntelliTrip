package com.intellitrip.offline

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The store's package ids may originate from an imported file, so they are untrusted
 * input. These tests exist because the original sanitizer looked correct and was
 * not: `id.replace("[^A-Za-z0-9._-]", "_")` is a literal replacement in Kotlin, not
 * a regex, so it left traversal sequences intact.
 *
 * The round-trip tests are equally load-bearing. A manifest separator regression
 * once left `FIELD_SEPARATOR` empty, which made every write silently unreadable
 * while the traversal tests still passed: they never reloaded a package.
 */
class OfflineBasemapStoreSecurityTest {

    private fun store(minimumBytes: Long = 1024L): OfflineBasemapStore =
        OfflineBasemapStore(
            Files.createTempDirectory("basemap-sec").toString(),
            minimumBytes = minimumBytes,
        )

    private fun staged(size: Int = 4096): Path =
        PmtilesFixture.write(Files.createTempFile("stage", ".pmtiles"), PmtilesFixture.bytes(size))

    private val style = PmtilesFixture.style()

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
        assertEquals(14, reloaded.single().maxZoom)
        assertEquals(44.8, reloaded.single().bounds.minLat)
        assertEquals(-93.4, reloaded.single().bounds.minLon)
        assertEquals(45.1, reloaded.single().bounds.maxLat)
        assertEquals(-92.9, reloaded.single().bounds.maxLon)
    }

    @Test
    fun styleSurvivesTheRoundTripAndIsBoundToTheInstalledArchive() {
        val store = store()
        val installed = store.install("metro", "Twin Cities", staged().toString(), style)
        assertTrue(installed is InstallResult.Installed)
        val reloaded = OfflineBasemapStore(store.rootForTest())
        val pkg = reloaded.packages().single()
        val bound = assertNotNull(reloaded.styleFor(pkg))

        assertTrue(bound.contains("\"sources\""), "style body was lost: $bound")
        assertTrue(
            bound.contains("pmtiles://file:"),
            "style was not bound to a local archive: $bound",
        )
        assertFalse(
            bound.contains("pmtiles://basemap.pmtiles"),
            "the placeholder archive URL survived binding: $bound",
        )
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
    fun reinstallOfTheSameIdReplacesRatherThanDuplicatesAndDeletesTheOldGeneration() {
        val store = store()
        val first = store.install("metro", "First", staged().toString(), style) as InstallResult.Installed
        val firstTile = Paths.get(first.pkg.tilePath)
        val second = store.install("metro", "Second", staged(8192).toString(), style) as InstallResult.Installed

        val reloaded = OfflineBasemapStore(store.rootForTest()).packages()
        assertEquals(1, reloaded.size, "reinstall created a duplicate entry")
        assertEquals("Second", reloaded.single().displayName)
        assertTrue(first.pkg.tilePath != second.pkg.tilePath, "reinstall reused the generation name")
        assertFalse(Files.exists(firstTile), "the previous generation was left on disk")
        assertEquals(8192L, reloaded.single().sizeBytes)
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

    // --- format validation: PMTiles v3 or host-validated MBTiles

    @Test
    fun mbtilesRequiresHostValidatedMetadata() {
        val store = store()
        val mbtiles = PmtilesFixture.write(
            Files.createTempFile("stage", ".mbtiles"),
            PmtilesFixture.mbtiles(),
        )
        val rejected = store.install("metro", "Old archive", mbtiles.toString(), style)
        assertTrue(rejected is InstallResult.Rejected)
        assertTrue(rejected.reason.contains("validated"), rejected.reason)
        assertTrue(!store.hasPackage())
    }

    @Test
    fun mbtilesFormatAndMetadataSurviveManifestRoundTrip() {
        val store = store()
        val mbtiles = PmtilesFixture.write(
            Files.createTempFile("stage", ".mbtiles"),
            PmtilesFixture.mbtiles(),
        )
        val mbtilesStyle = style.replace("pmtiles://", "mbtiles://")
        val metadata = MbtilesMetadata(
            minZoom = 2,
            maxZoom = 12,
            bounds = BasemapBounds(44.8, -93.4, 45.1, -92.9),
            tileFormat = "pbf",
        )
        val installed = store.install(
            "metro",
            "Twin Cities",
            mbtiles.toString(),
            mbtilesStyle,
            metadata,
        )
        assertTrue(installed is InstallResult.Installed, "install failed: $installed")

        val reloaded = OfflineBasemapStore(store.rootForTest())
        val pkg = reloaded.packages().single()
        assertEquals(BasemapArchiveFormat.MBTILES, pkg.archiveFormat)
        assertEquals("pbf", pkg.tileFormat)
        assertEquals(2, pkg.minZoom)
        assertEquals(12, pkg.maxZoom)
        val tileJsonUrl = "http://127.0.0.1:12345/${"a".repeat(32)}/tilejson.json"
        val bound = assertNotNull(reloaded.styleFor(pkg, tileJsonUrl))
        assertTrue(bound.contains(tileJsonUrl), "style was not bound to local TileJSON: $bound")
        assertFalse(bound.contains("mbtiles://"), "the MBTiles placeholder survived binding")
    }

    @Test
    fun unknownFormatIsRejected() {
        val store = store()
        val file = Files.createTempFile("stage", ".pmtiles")
        Files.write(file, ByteArray(4096) { 0x42 })
        val rejected = store.install("metro", "Junk", file.toString(), style)
        assertTrue(rejected is InstallResult.Rejected)
        assertTrue(rejected.reason.contains("Not a PMTiles v3 or MBTiles archive"), rejected.reason)
    }

    @Test
    fun truncatedPmtilesIsRejectedBeforeInstalling() {
        val store = store(minimumBytes = 1L)
        val file = Files.createTempFile("stage", ".pmtiles")
        // Magic present, but only 64 bytes: no room for a v3 header.
        Files.write(file, PmtilesFixture.bytes().copyOf(64))
        val rejected = store.install("metro", "Truncated", file.toString(), style)
        assertTrue(rejected is InstallResult.Rejected)
        assertTrue(rejected.reason.contains("too short"), rejected.reason)
    }

    @Test
    fun invalidZoomRangeInTheHeaderIsRejected() {
        val store = store()
        val file = PmtilesFixture.write(
            Files.createTempFile("stage", ".pmtiles"),
            PmtilesFixture.bytes(minZoom = 14, maxZoom = 2),
        )
        val rejected = store.install("metro", "Bad zoom", file.toString(), style)
        assertTrue(rejected is InstallResult.Rejected)
        assertTrue(rejected.reason.contains("zoom range"), rejected.reason)
    }

    @Test
    fun styleWithoutAPmtilesSourceIsRejected() {
        val store = store()
        val rejected = store.install(
            "metro",
            "Online only",
            staged().toString(),
            """{"version":8,"sources":{},"layers":[]}""",
        )
        assertTrue(rejected is InstallResult.Rejected)
        assertTrue(rejected.reason.contains("pmtiles://"), rejected.reason)
    }

    @Test
    fun malformedStyleIsRejected() {
        val store = store()
        val rejected = store.install(
            "metro",
            "Broken",
            staged().toString(),
            """{"version":8,"sources":{"offline":{"url":"pmtiles://x"}},"layers":[""",
        )
        assertTrue(rejected is InstallResult.Rejected)
        assertTrue(rejected.reason.contains("not valid JSON"), rejected.reason)
    }

    @Test
    fun styleWithEscapedSlashesIsAcceptedAndBound() {
        val store = store()
        val escaped = style.replace("/", "\\/")
        val installed = store.install("metro", "Escaped", staged().toString(), escaped)
        assertTrue(installed is InstallResult.Installed, "install failed: $installed")
        val bound = assertNotNull(store.styleFor(installed.pkg))
        assertTrue(bound.contains("pmtiles://file:"), bound)
        assertFalse(bound.contains("\\/\\/"), "escaped slashes survived binding: $bound")
    }

    @Test
    fun mbtilesStylesRejectEscapedRemoteResourcesButAllowAttributionLinks() {
        val remoteResources = listOf(
            """"url":"https://tiles.example.test/tiles.json"""",
            """"tiles":["https:\/\/tiles.example.test\/{z}/{x}/{y}.pbf"]""",
            """"sprite":"https://tiles.example.test/sprite"""",
            """"glyphs":"https://tiles.example.test/fonts/{fontstack}/{range}.pbf"""",
        )
        remoteResources.forEach { resource ->
            val styleJson =
                """{"version":8,"sources":{"offline":{"type":"vector","url":"mbtiles://archive"}},"layers":[],$resource}"""
            val result = BasemapStyleValidator.validate(styleJson, BasemapArchiveFormat.MBTILES)
            assertTrue(
                result is BasemapStyleValidator.Result.Invalid,
                "remote resource was accepted: $resource",
            )
        }

        val attributionOnly =
            """{"version":8,"sources":{"offline":{"type":"vector","url":"mbtiles://archive","attribution":"https://tiles.example.test/license"}},"layers":[]}"""
        assertEquals(
            BasemapStyleValidator.Result.Valid,
            BasemapStyleValidator.validate(attributionOnly, BasemapArchiveFormat.MBTILES),
        )
    }

    @Test
    fun corruptedInstalledArchiveIsNotBoundForRendering() {
        val store = store()
        val installed = store.install("metro", "Corrupted", staged().toString(), style)
            as InstallResult.Installed
        Files.write(Paths.get(installed.pkg.tilePath), byteArrayOf(0, 1, 2))

        assertNull(store.styleFor(installed.pkg))
    }

    @Test
    fun editedInstalledStyleIsNotBoundForRendering() {
        val store = store()
        val installed = store.install("metro", "Edited", staged().toString(), style)
            as InstallResult.Installed
        val stylePath = Paths.get(installed.pkg.tilePath).resolveSibling(
            Paths.get(installed.pkg.tilePath).fileName.toString() + ".style.json",
        )
        Files.writeString(stylePath, """{"version":8,"sources":{},"layers":[]}""")

        assertNull(store.styleFor(installed.pkg))
    }

    @Test
    fun zoomRangeAndBoundsComeFromTheArchiveHeader() {
        val store = store()
        val file = PmtilesFixture.write(
            Files.createTempFile("stage", ".pmtiles"),
            PmtilesFixture.bytes(minZoom = 4, maxZoom = 12),
        )
        val installed = store.install("metro", "Header metadata", file.toString(), style)
        assertTrue(installed is InstallResult.Installed, "install failed: $installed")
        assertEquals(4, installed.pkg.minZoom)
        assertEquals(12, installed.pkg.maxZoom)
        assertEquals(44.8, installed.pkg.bounds.minLat)
        assertEquals(-93.4, installed.pkg.bounds.minLon)
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
        val manifest = Paths.get(store.rootForTest()).resolve("basemap-manifest.txt")
        val good = Files.readAllLines(manifest).single()

        Files.writeString(
            manifest,
            listOf(
                listOf(
                    "ZXZpbA==",
                    "RXZpbA==",
                    "../../../../etc/passwd",
                    "10",
                    "0",
                    "14",
                    "-180",
                    "-90",
                    "180",
                    "90",
                ).joinToString(SEP),
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
    fun manifestEntriesWithNestedPathsOrImpossibleZoomAreIgnored() {
        val store = store()
        val installed = store.install("good", "Good", staged().toString(), style)
        assertTrue(installed is InstallResult.Installed)
        val manifest = Paths.get(store.rootForTest()).resolve("basemap-manifest.txt")
        val good = Files.readAllLines(manifest).single()
        val fields = good.split(SEP).toMutableList()

        val nested = fields.toMutableList().apply { this[2] = "nested/${fields[2]}" }
        val badZoom = fields.toMutableList().apply { this[5] = "99" }
        Files.writeString(manifest, listOf(nested.joinToString(SEP), badZoom.joinToString(SEP), good).joinToString("\n"))

        val packages = OfflineBasemapStore(store.rootForTest()).packages()
        assertEquals(1, packages.size)
        assertEquals("good", packages.single().id)
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

    @Test
    fun manifestEntryWhoseStyleIsMissingIsNotReportedInstalled() {
        val store = store()
        val installed = store.install("gone", "Gone", staged().toString(), style)
        assertTrue(installed is InstallResult.Installed)
        val tile = Paths.get(installed.pkg.tilePath)
        Files.delete(tile.resolveSibling(tile.fileName.toString() + ".style.json"))
        assertTrue(store.packages().none { it.id == "gone" }, "package without a style was reported installed")
    }
}
