package com.intellitrip.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/**
 * Saved places are the only rider-owned data in the app, and the review found three
 * ways that could go wrong without the rider noticing.
 *
 * Removal depending on the feed, storage failures escaping as crashes, and a format
 * change silently discarding existing records are all covered here, because each one
 * looks correct in code and only fails for someone who saved a stop and came back.
 */
class SavedPlacesStoreTest {

    private val root: Path = Files.createTempDirectory("saved-places-test")

    private fun stop(feed: String, id: String, name: String, at: Instant = Instant.parse("2026-10-05T12:00:00Z")) =
        SavedStop(feed, id, name, 44.97, -93.26, at)

    @Test
    fun aSavedStopSurvivesRestart() {
        val store = SavedPlacesStore(root)
        store.save(stop("metro", "S1", "Nicollet Mall"))

        // A new store instance is equivalent to relaunching the app.
        val reloaded = SavedPlacesStore(root).load()
        assertEquals(1, reloaded.size)
        assertEquals("Nicollet Mall", reloaded.single().name)
        assertEquals("metro", reloaded.single().feedId)
        assertEquals("metro/S1", reloaded.single().key)
    }

    /**
     * Removal must not need the transit feed.
     *
     * The store is the only thing involved here: there is no network, no snapshot and
     * no feed object in this test at all. That is the point.
     */
    @Test
    fun removalWorksWithNoFeedPresent() {
        val store = SavedPlacesStore(root)
        store.save(stop("metro", "S1", "Nicollet Mall"))
        assertEquals(1, store.load().size)

        val remaining = store.remove("metro", "S1")
        assertTrue(remaining.isEmpty())
        assertTrue("removal must persist", SavedPlacesStore(root).load().isEmpty())
    }

    @Test
    fun removalOfAnUnknownStopIsHarmless() {
        val store = SavedPlacesStore(root)
        store.save(stop("metro", "S1", "Nicollet Mall"))
        val remaining = store.remove("metro", "does-not-exist")
        assertEquals("a no-op removal must not delete anything", 1, remaining.size)
    }

    @Test
    fun identicallyNumberedStopsOnDifferentFeedsCoexist() {
        val store = SavedPlacesStore(root)
        store.save(stop("metro", "S1", "Nicollet Mall"))
        store.save(stop("other", "S1", "Main St"))

        val loaded = store.load()
        assertEquals("two agencies both numbering a stop 1 must stay separate", 2, loaded.size)
        assertEquals(setOf("metro/S1", "other/S1"), loaded.map { it.key }.toSet())

        // Removing one must not take the other with it.
        store.remove("metro", "S1")
        assertEquals(listOf("other/S1"), store.load().map { it.key })
    }

    @Test
    fun savingTheSameKeyTwiceReplacesRatherThanDuplicates() {
        val store = SavedPlacesStore(root)
        store.save(stop("metro", "S1", "First name"))
        store.save(stop("metro", "S1", "Second name"))
        val loaded = store.load()
        assertEquals(1, loaded.size)
        assertEquals("Second name", loaded.single().name)
    }

    /**
     * A record written before feed ids existed must not vanish.
     *
     * The legacy layout has no feed field. Dropping it would make a rider's saved list
     * appear empty after an update, which is exactly the silent data loss a rider would
     * correctly never forgive.
     */
    @Test
    fun legacyRecordsAreReadRatherThanDiscarded() {
        val separator = 0x1F.toChar().toString()
        val legacyLine = listOf(
            "S1",
            "Nicollet Mall",
            "44.97",
            "-93.26",
            "2026-10-05T12:00:00Z",
        ).joinToString(separator)
        Files.write(root.resolve("saved-places.txt"), listOf(legacyLine))

        val loaded = SavedPlacesStore(root).load()
        assertEquals("legacy records must survive an upgrade", 1, loaded.size)
        assertEquals("Nicollet Mall", loaded.single().name)
        assertEquals(
            "a legacy record has no known feed and must not be attributed to one",
            SavedPlacesStore.LEGACY_FEED,
            loaded.single().feedId,
        )
    }

    @Test
    fun legacyRecordsAdoptTheRealFeedWhenTheStopIsKnown() {
        val separator = 0x1F.toChar().toString()
        val legacyLine = listOf("S1", "Nicollet Mall", "44.97", "-93.26", "2026-10-05T12:00:00Z")
            .joinToString(separator)
        Files.write(root.resolve("saved-places.txt"), listOf(legacyLine))

        val store = SavedPlacesStore(root)
        val (rescoped, changed) = store.adoptFeedScope("metro", setOf("S1", "S2"))

        assertTrue("re-scoping a known stop is a change", changed)
        assertEquals("metro", rescoped.single().feedId)
        // And it must be persisted, not just held in memory.
        assertEquals("metro", SavedPlacesStore(root).load().single().feedId)
    }

    @Test
    fun legacyRecordsForUnknownStopsKeepTheirMarker() {
        val separator = 0x1F.toChar().toString()
        Files.write(
            root.resolve("saved-places.txt"),
            listOf(listOf("GONE", "Removed Stop", "44.97", "-93.26", "2026-10-05T12:00:00Z", "").joinToString(separator)),
        )

        val store = SavedPlacesStore(root)
        val (rescoped, changed) = store.adoptFeedScope("metro", setOf("S1"))

        assertFalse("nothing matched, so nothing changed", changed)
        assertEquals("an unmatched legacy record stays visible and removable", 1, rescoped.size)
    }

    @Test
    fun theFormatHeaderIsWrittenAndIgnoredOnRead() {
        val store = SavedPlacesStore(root)
        store.save(stop("metro", "S1", "Nicollet Mall"))
        val lines = Files.readAllLines(root.resolve("saved-places.txt"))
        assertTrue("a version header is written", lines.first().startsWith("#"))
        // It must not be mistaken for a record.
        assertEquals(1, SavedPlacesStore(root).load().size)
    }

    @Test
    fun thePreviousFileIsBackedUpBeforeBeingReplaced() {
        val store = SavedPlacesStore(root)
        store.save(stop("metro", "S1", "First"))
        store.save(stop("metro", "S2", "Second"))

        val backup = root.resolve("saved-places.bak")
        assertTrue("a backup must exist after a rewrite", Files.exists(backup))
        // The backup holds the previous generation, so it must not contain S2 yet.
        val backupText = String(Files.readAllBytes(backup), Charsets.UTF_8)
        assertTrue(backupText.contains("First"))
        assertFalse("the backup must be the previous version", backupText.contains("Second"))
    }

    @Test
    fun namesContainingTheSeparatorRoundTrip() {
        val store = SavedPlacesStore(root)
        val awkward = "Nicollet${0x1F.toChar()}Mall"
        store.save(stop("metro", "S1", awkward))
        assertEquals(awkward, SavedPlacesStore(root).load().single().name)
    }

    @Test
    fun aCorruptFileYieldsAnEmptyListRatherThanACrash() {
        Files.write(root.resolve("saved-places.txt"), listOf("not", "a", "valid", "record"))
        assertNotNull(SavedPlacesStore(root).load())
        assertTrue(SavedPlacesStore(root).load().isEmpty())
    }

    @Test
    fun isSavedIsScopedByFeed() {
        val store = SavedPlacesStore(root)
        store.save(stop("metro", "S1", "Nicollet Mall"))
        assertTrue(store.isSaved("metro", "S1"))
        assertFalse(store.isSaved("other", "S1"))
    }
}