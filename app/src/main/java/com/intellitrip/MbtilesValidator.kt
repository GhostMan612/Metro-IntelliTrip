package com.intellitrip

import android.database.sqlite.SQLiteDatabase
import com.intellitrip.offline.BasemapBounds
import com.intellitrip.offline.MbtilesMetadata
import java.io.File

internal object MbtilesValidator {

    sealed interface Result {
        data class Valid(val metadata: MbtilesMetadata) : Result
        data class Invalid(val reason: String) : Result
    }

    fun validate(file: File): Result = try {
        SQLiteDatabase.openDatabase(
            file.absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
        ).use { database ->
            val metadataColumns = columns(database, "metadata")
            require(metadataColumns.containsAll(setOf("name", "value"))) {
                "MBTiles metadata table must contain name and value columns"
            }
            val tileColumns = columns(database, "tiles")
            require(tileColumns.containsAll(setOf("zoom_level", "tile_column", "tile_row", "tile_data"))) {
                "MBTiles tiles table has an unsupported schema"
            }

            val metadata = mutableMapOf<String, String>()
            database.rawQuery("SELECT name, value FROM metadata", null).use { cursor ->
                while (cursor.moveToNext()) {
                    val key = cursor.getString(0)
                    val value = cursor.getString(1)
                    require(metadata.put(key, value) == null) { "MBTiles metadata contains duplicate '$key'" }
                }
            }
            val format = metadata.required("format").lowercase()
            require(format in SUPPORTED_FORMATS) { "MBTiles format '$format' is not supported" }
            require(metadata["scheme"].isNullOrBlank() || metadata["scheme"].equals("tms", true)) {
                "MBTiles scheme must be TMS (the MBTiles default)"
            }
            val minZoom = metadata.required("minzoom").toIntOrNull()
                ?: throw IllegalArgumentException("MBTiles minzoom must be an integer")
            val maxZoom = metadata.required("maxzoom").toIntOrNull()
                ?: throw IllegalArgumentException("MBTiles maxzoom must be an integer")
            require(minZoom in 0..24 && maxZoom in minZoom..24) {
                "MBTiles zoom range must be between 0 and 24"
            }
            val boundsValues = metadata.required("bounds").split(',').map { value ->
                value.trim().toDoubleOrNull()
                    ?: throw IllegalArgumentException("MBTiles bounds must contain four finite numbers")
            }
            require(boundsValues.size == 4 && boundsValues.all(Double::isFinite)) {
                "MBTiles bounds must contain four finite numbers"
            }
            val (minLon, minLat, maxLon, maxLat) = boundsValues
            require(minLon in -180.0..180.0 && maxLon in -180.0..180.0 &&
                minLat in -90.0..90.0 && maxLat in -90.0..90.0 &&
                minLon <= maxLon && minLat <= maxLat
            ) { "MBTiles bounds are outside the valid lon/lat range or inverted" }

            database.rawQuery("SELECT tile_data FROM tiles LIMIT 1", null).use { cursor ->
                require(cursor.moveToFirst() && !cursor.isNull(0)) { "MBTiles contains no tile data" }
            }
            Result.Valid(
                MbtilesMetadata(
                    minZoom = minZoom,
                    maxZoom = maxZoom,
                    bounds = BasemapBounds(minLat, minLon, maxLat, maxLon),
                    tileFormat = format,
                ),
            )
        }
    } catch (error: Exception) {
        Result.Invalid(error.message ?: "could not validate the MBTiles database")
    }

    private fun columns(database: SQLiteDatabase, table: String): Set<String> =
        database.rawQuery("PRAGMA table_info('$table')", null).use { cursor ->
            buildSet {
                while (cursor.moveToNext()) add(cursor.getString(1))
            }
        }

    private fun Map<String, String>.required(key: String): String =
        get(key)?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("MBTiles metadata is missing '$key'")

    private val SUPPORTED_FORMATS = setOf("pbf", "png", "jpg", "jpeg", "webp")
}
