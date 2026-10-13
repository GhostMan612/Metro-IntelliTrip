package com.intellitrip.ui

import com.intellitrip.domain.GeoPoint
import com.intellitrip.domain.Stop
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant

/**
 * A stop the rider has chosen to keep.
 *
 * A stop is stored by its feed and stop ids rather than by coordinates, so the entry
 * survives a re-download of the feed and keeps working when the agency's stop moves a
 * few metres. The name is cached only so the list can render before the feed loads.
 */
data class SavedStop(
    val stopId: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    val savedAt: Instant,
    val lastRouteId: String? = null,
)

/**
 * Everything the rider has saved, on device.
 *
 * Saved data is local and account-free by design. The recovery plan requires Saved to
 * be "fully usable without login", so there is no identifier here that only this app
 * understands and no path that requires a server round trip to read or write.
 *
 * Writes are atomic: the list is written to a temporary file and moved over the real
 * one. A process death mid-write otherwise leaves a truncated file, and the next
 * launch silently loses every saved stop, which is exactly the kind of quiet data loss
 * a rider will not forgive.
 */
class SavedPlacesStore(private val root: Path) {

    private val file: Path get() = root.resolve(FILE_NAME)

    /** Reads saved stops. A corrupt or missing file yields an empty list, never a crash. */
    fun load(): List<SavedStop> {
        if (!Files.exists(file)) return emptyList()
        return runCatching {
            Files.readAllLines(file)
                .filter { it.isNotBlank() }
                .mapNotNull(::parseLine)
                .sortedByDescending { it.savedAt }
        }.getOrElse { emptyList() }
    }

    /** Adds or replaces a stop, keeping one entry per stop id. */
    fun save(stop: SavedStop): List<SavedStop> {
        val updated = load().filterNot { it.stopId == stop.stopId } + stop
        write(updated)
        return updated
    }

    fun remove(stopId: String): List<SavedStop> {
        val updated = load().filterNot { it.stopId == stopId }
        write(updated)
        return updated
    }

    fun isSaved(stopId: String): Boolean = load().any { it.stopId == stopId }

    private fun write(stops: List<SavedStop>) {
        Files.createDirectories(root)
        val temp = root.resolve("$FILE_NAME.tmp")
        Files.write(temp, stops.map(::formatLine))
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING)
    }

    /**
     * One line per stop, fields escaped.
     *
     * The separator is the unit separator character and the escape is a backslash,
     * so a stop whose real name contains the separator still round-trips. Written with
     * escapes built from code points, because a literal control character in source has
     * already been silently stripped once in this file's history.
     */
    private fun formatLine(stop: SavedStop): String = listOf(
        stop.stopId,
        stop.name,
        stop.lat.toString(),
        stop.lon.toString(),
        stop.savedAt.toString(),
        stop.lastRouteId.orEmpty(),
    ).joinToString(SEPARATOR) { escape(it) }

    private fun parseLine(line: String): SavedStop? {
        val parts = line.split(SEPARATOR)
        if (parts.size < 5) return null
        return runCatching {
            SavedStop(
                stopId = unescape(parts[0]),
                name = unescape(parts[1]),
                lat = parts[2].toDouble(),
                lon = parts[3].toDouble(),
                savedAt = Instant.parse(parts[4]),
                lastRouteId = parts.getOrNull(5)?.takeIf { it.isNotEmpty() }?.let { unescape(it) },
            )
        }.getOrNull()
    }

    private fun escape(value: String): String =
        value.replace("\\", "\\\\").replace(SEPARATOR, "\\u")

    private fun unescape(value: String): String {
        val out = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '\\' && i + 1 < value.length) {
                when (value[i + 1]) {
                    '\\' -> { out.append('\\'); i += 2 }
                    'u' -> { out.append(SEPARATOR); i += 2 }
                    else -> { out.append(c); i++ }
                }
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }

    companion object {
        private const val FILE_NAME = "saved-places.txt"

        /** U+001F. Derived from its code point so it cannot be emptied by an editor. */
        private val SEPARATOR: String = 0x1F.toChar().toString()

        fun from(stop: Stop, now: Instant = Instant.now(), lastRouteId: String? = null) = SavedStop(
            stopId = stop.key.stopId,
            name = stop.name,
            lat = stop.location.lat,
            lon = stop.location.lon,
            savedAt = now,
            lastRouteId = lastRouteId,
        )

        fun locationOf(stop: SavedStop) = GeoPoint(stop.lat, stop.lon)
    }
}