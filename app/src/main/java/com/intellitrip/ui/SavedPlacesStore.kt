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
 * Identity is the *pair* (feedId, stopId), never the stop id alone. Two agencies
 * routinely both number their first stop "1", and a multi-feed build would collapse
 * them into one entry: saving a stop on one feed would silently appear to overwrite a
 * different stop on another, with no warning. Every read, write and duplicate check
 * is scoped by feed for that reason.
 *
 * The stop is stored by feed and stop id rather than by coordinates, so the entry
 * survives a re-download of the feed and keeps working when the agency's stop moves a
 * few metres. The name is cached only so the list can render before the feed loads.
 */
data class SavedStop(
    val feedId: String,
    val stopId: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    val savedAt: Instant,
    val lastRouteId: String? = null,
) {
    /** Stable, feed-qualified identity used for lookup and duplicate detection. */
    val key: String get() = "$feedId/$stopId"
}

/**
 * A saved place could not be written safely.
 *
 * Thrown rather than swallowed, so the caller can tell the rider their change did not
 * save instead of showing a list that silently lost it.
 */
class SavedPlacesWriteException(message: String, cause: Throwable? = null) : Exception(message, cause)

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

    /**
     * Reads saved stops. A corrupt or missing file yields an empty list, never a crash.
     *
     * Records written by an earlier build carry no feed id. They are still read, tagged
     * with [LEGACY_FEED] so a later feed load can re-scope them, rather than dropped.
     * Silently discarding them would make a rider's saved list appear to vanish on
     * update, which is the kind of quiet data loss they correctly never forgive.
     */
    fun load(): List<SavedStop> {
        if (!Files.exists(file)) return emptyList()
        return runCatching {
            Files.readAllLines(file)
                .filter { it.isNotBlank() && !it.startsWith(HEADER_PREFIX) }
                .mapNotNull(::parseLine)
                .sortedByDescending { it.savedAt }
        }.getOrElse { emptyList() }
    }

    /** Adds or replaces a stop, keeping one entry per (feedId, stopId). */
    fun save(stop: SavedStop): List<SavedStop> {
        val updated = load().filterNot { it.feedId == stop.feedId && it.stopId == stop.stopId } + stop
        write(updated)
        return updated
    }

    fun remove(feedId: String, stopId: String): List<SavedStop> {
        val updated = load().filterNot { it.feedId == feedId && it.stopId == stopId }
        write(updated)
        return updated
    }

    fun isSaved(feedId: String, stopId: String): Boolean =
        load().any { it.feedId == feedId && it.stopId == stopId }

    /**
     * Replaces the store's file atomically, keeping a backup of the previous version.
     *
     * `ATOMIC_MOVE` is requested so the rename is a single filesystem operation and a
     * crash can never leave a half-written list. Not every filesystem supports it:
     * some Android external volumes and most network mounts reject the flag with
     * `AtomicMoveNotSupportedException`. That is not a reason to fall back to a
     * non-atomic copy, which reintroduces exactly the corruption this prevents.
     *
     * Instead the failure is surfaced. If the atomic rename cannot be performed the
     * previous file is left untouched and the caller is told, so a rider either has
     * their old saved list or gets an explicit failure, never a silently truncated one.
     *
     * The backup is taken before the new file is in place, so it always holds the last
     * list this build managed to write. It is what a migration can fall back to if
     * rewriting the records goes wrong.
     */
    private fun write(stops: List<SavedStop>) {
        Files.createDirectories(root)
        // Preserve the previous generation before overwriting it.
        if (Files.exists(file)) {
            runCatching {
                Files.copy(file, root.resolve(BACKUP_FILE), StandardCopyOption.REPLACE_EXISTING)
            }
        }
        val temp = root.resolve("$FILE_NAME.tmp")
        Files.write(temp, listOf(HEADER) + stops.map(::formatLine))
        try {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
            // Leave the previous file intact and remove the partial write.
            runCatching { Files.deleteIfExists(temp) }
            throw SavedPlacesWriteException(
                "Saved places could not be written atomically on this filesystem", e,
            )
        }
    }

    /**
     * Re-scope records that predate feed scoping, then persist if anything changed.
     *
     * A legacy record has no feed id, so it is matched against the loaded feed by stop
     * id. A match gets the feed's real id; no match keeps the legacy marker, which keeps
     * the entry visible and removable rather than dropping it.
     *
     * Returns the new list and whether anything was rewritten.
     */
    fun adoptFeedScope(
        feedId: String,
        knownStopIds: Set<String>,
    ): Pair<List<SavedStop>, Boolean> {
        val current = load()
        val rescoped = current.map { saved ->
            if (saved.feedId == LEGACY_FEED && knownStopIds.contains(saved.stopId)) {
                saved.copy(feedId = feedId)
            } else {
                saved
            }
        }
        val changed = rescoped != current
        if (changed) {
            // A failed rewrite is not fatal: the in-memory list is still correct, and the
            // caller keeps showing the adopted entries.
            runCatching { write(rescoped) }
        }
        return rescoped to changed
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
        stop.feedId,
        stop.stopId,
        stop.name,
        stop.lat.toString(),
        stop.lon.toString(),
        stop.savedAt.toString(),
        stop.lastRouteId.orEmpty(),
    ).joinToString(SEPARATOR) { escape(it) }

    private fun parseLine(line: String): SavedStop? {
        val parts = line.split(SEPARATOR)
        return runCatching {
            when {
                // Current: feed, stop, name, lat, lon, savedAt, route. Always seven
                // fields, because the route slot is written even when empty.
                parts.size >= 7 -> SavedStop(
                    feedId = unescape(parts[0]),
                    stopId = unescape(parts[1]),
                    name = unescape(parts[2]),
                    lat = parts[3].toDouble(),
                    lon = parts[4].toDouble(),
                    savedAt = Instant.parse(parts[5]),
                    lastRouteId = parts.getOrNull(6)?.takeIf { it.isNotEmpty() }?.let { unescape(it) },
                )

                // Legacy, written before stop ids were feed-scoped: stop, name, lat,
                // lon, savedAt and an optional trailing route. Always six fields, so
                // seven or more is unambiguously the current format and there is no
                // overlap to guess at.
                //
                // Read rather than dropped, and tagged with the legacy marker so it can
                // be adopted once the real feed is known. Discarding it would make a
                // rider's saved list appear to vanish after an update.
                parts.size == 5 || parts.size == 6 -> SavedStop(
                    feedId = LEGACY_FEED,
                    stopId = unescape(parts[0]),
                    name = unescape(parts[1]),
                    lat = parts[2].toDouble(),
                    lon = parts[3].toDouble(),
                    savedAt = Instant.parse(parts[4]),
                    lastRouteId = parts.getOrNull(5)?.takeIf { it.isNotEmpty() }?.let { unescape(it) },
                )

                else -> return null
            }
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

        /** Previous generation, kept so a bad migration is recoverable. */
        private const val BACKUP_FILE = "saved-places.bak"

        /** U+001F. Derived from its code point so it cannot be emptied by an editor. */
        private val SEPARATOR: String = 0x1F.toChar().toString()

        /** Format version, written as a header line and skipped when reading. */
        private const val HEADER_PREFIX = "#"
        private const val HEADER = "$HEADER_PREFIX v2 saved-places"

        /**
         * Placeholder feed for records written before stop ids were feed-scoped.
         *
         * Deliberately not a real feed id: a record must never be attributed to an
         * agency by accident. It is replaced by [adoptFeedScope] once the true feed is
         * known and actually contains that stop.
         */
        const val LEGACY_FEED = ""

        fun from(stop: Stop, now: Instant = Instant.now(), lastRouteId: String? = null) = SavedStop(
            feedId = stop.key.feedId.value,
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