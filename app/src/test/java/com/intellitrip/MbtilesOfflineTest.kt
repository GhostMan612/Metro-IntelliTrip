package com.intellitrip

import android.database.sqlite.SQLiteDatabase
import com.intellitrip.offline.BasemapArchiveFormat
import com.intellitrip.offline.BasemapBounds
import com.intellitrip.offline.BasemapPackage
import java.io.File
import java.net.Socket
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MbtilesOfflineTest {

    @Test
    fun validatesRequiredMetadataAndTables() {
        val archive = createArchive(withTile = true)

        val result = MbtilesValidator.validate(archive)

        assertTrue("expected validated MBTiles, got $result", result is MbtilesValidator.Result.Valid)
        val metadata = (result as MbtilesValidator.Result.Valid).metadata
        assertEquals(1, metadata.minZoom)
        assertEquals(2, metadata.maxZoom)
        assertEquals("pbf", metadata.tileFormat)
        assertEquals(BasemapBounds(44.0, -94.0, 46.0, -92.0), metadata.bounds)
    }

    @Test
    fun rejectsDatabaseWithoutRequiredTables() {
        val archive = File.createTempFile("bad-map", ".mbtiles")
        SQLiteDatabase.openOrCreateDatabase(archive, null).use { database ->
            database.execSQL("CREATE TABLE metadata (name TEXT, value TEXT)")
        }

        val result = MbtilesValidator.validate(archive)

        assertTrue(result is MbtilesValidator.Result.Invalid)
        assertTrue((result as MbtilesValidator.Result.Invalid).reason.contains("tiles table"))
    }

    @Test
    fun rejectsMalformedBoundsInsteadOfDroppingInvalidCoordinates() {
        val result = MbtilesValidator.validate(
            createArchive(withTile = true, bounds = "-94,44,invalid,-92,46"),
        )

        assertTrue(result is MbtilesValidator.Result.Invalid)
        assertTrue((result as MbtilesValidator.Result.Invalid).reason.contains("bounds"))
    }

    @Test
    fun servesTileJsonAndMapsXyzRowsToMbtilesTms() {
        val archive = createArchive(withTile = true)
        val packageMetadata = (MbtilesValidator.validate(archive) as MbtilesValidator.Result.Valid).metadata
        val pkg = BasemapPackage(
            id = "test-package",
            displayName = "Test",
            tilePath = archive.absolutePath,
            styleJson = "",
            sizeBytes = archive.length(),
            minZoom = packageMetadata.minZoom,
            maxZoom = packageMetadata.maxZoom,
            bounds = packageMetadata.bounds,
            archiveFormat = BasemapArchiveFormat.MBTILES,
            tileFormat = packageMetadata.tileFormat,
        )
        MbtilesTileServer().use { server ->
            val tileJsonUrl = server.tileJsonUrl(pkg) ?: error("server failed to start")
            val base = java.net.URL(tileJsonUrl)
            val tileJson = httpGet(base.port, base.path)
            assertTrue(tileJson.contains("\"scheme\":\"xyz\""))
            assertTrue(tileJson.contains("/${base.path.split('/')[1]}/{z}/{x}/{y}.pbf"))

            val token = base.path.split('/')[1]
            val tileResponse = httpGet(base.port, "/$token/1/0/0.pbf")
            assertTrue(tileResponse.startsWith("HTTP/1.1 200 OK"))
            assertTrue(tileResponse.endsWith("expected-tile"))
        }
    }

    private fun createArchive(
        withTile: Boolean,
        bounds: String = "-94,44,-92,46",
    ): File {
        val file = File.createTempFile("map", ".mbtiles")
        SQLiteDatabase.openOrCreateDatabase(file, null).use { database ->
            database.execSQL("CREATE TABLE metadata (name TEXT, value TEXT)")
            database.execSQL(
                "CREATE TABLE tiles (zoom_level INTEGER, tile_column INTEGER, tile_row INTEGER, tile_data BLOB)",
            )
            listOf(
                "format" to "pbf",
                "minzoom" to "1",
                "maxzoom" to "2",
                "bounds" to bounds,
            ).forEach { (name, value) ->
                database.execSQL("INSERT INTO metadata (name, value) VALUES (?, ?)", arrayOf(name, value))
            }
            if (withTile) {
                database.execSQL(
                    "INSERT INTO tiles (zoom_level, tile_column, tile_row, tile_data) VALUES (1, 0, 1, ?)",
                    arrayOf("expected-tile".toByteArray(StandardCharsets.UTF_8)),
                )
            }
        }
        return file
    }

    private fun httpGet(port: Int, path: String): String =
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 5000
            socket.getOutputStream().write(
                "GET $path HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n"
                    .toByteArray(StandardCharsets.US_ASCII),
            )
            socket.getOutputStream().flush()
            socket.getInputStream().readBytes().toString(StandardCharsets.ISO_8859_1)
        }
}
