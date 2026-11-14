package com.intellitrip.offline

import com.intellitrip.domain.FeedId
import com.intellitrip.domain.FeedMetadata
import com.intellitrip.gtfs.PlatformFileStore
import com.intellitrip.gtfs.StaticFeedBinaryCache
import com.intellitrip.gtfs.StaticFeedSnapshotStore
import kotlinx.datetime.Instant
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class JvmOfflineLoadFeedTest {

    private fun zipOf(files: Map<String, String>): ByteArray {
        val buffer = ByteArrayOutputStream()
        ZipOutputStream(buffer).use { zip ->
            files.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return buffer.toByteArray()
    }

    @Test
    fun loadStaticFeedWithoutShapesSkipsShapePointsAndPersistsV2Cache() {
        val root = createTempDirectory("jvm-offline-shapes")
        val store = StaticFeedSnapshotStore(PlatformFileStore(root.toString()))
        val feedId = FeedId("metro-transit-regional")
        val metadata = FeedMetadata(
            feedId = feedId,
            publisherName = null,
            publisherUrl = null,
            lang = null,
            version = "1",
            hash = "hash-shapes",
            fetchedAt = Instant.parse("2026-10-07T00:00:00Z"),
            validFrom = null,
            validTo = null,
            sourceUrl = "https://example/gtfs.zip",
        )
        val minimalFeed = linkedMapOf(
            "agency.txt" to "agency_id,agency_name,agency_url,agency_timezone\n1,Metro Transit,https://www.metrotransit.org,America/Chicago\n",
            "routes.txt" to "route_id,agency_id,route_short_name,route_long_name,route_type\n901,1,A,Blue Line,0\n",
            "stops.txt" to "stop_id,stop_name,stop_lat,stop_lon\n34,Chicago Ave,44.97,-93.26\n39,17th St,44.96,-93.27\n",
            "trips.txt" to "route_id,service_id,trip_id,trip_headsign,direction_id,shape_id\n901,S1,1001,Northbound,0,SH1\n",
            "stop_times.txt" to "trip_id,arrival_time,departure_time,stop_id,stop_sequence\n1001,23:45:00,23:50:00,34,1\n1001,25:10:00,25:15:00,39,2\n",
            "calendar.txt" to "service_id,monday,tuesday,wednesday,thursday,friday,saturday,sunday,start_date,end_date\nS1,1,1,1,1,1,0,0,20260101,20261231\n",
            "shapes.txt" to "shape_id,shape_pt_lat,shape_pt_lon,shape_pt_sequence\nSH1,44.97,-93.26,1\nSH1,44.98,-93.27,2\n",
            "feed_info.txt" to "feed_publisher_name,feed_lang,feed_start_date,feed_end_date,feed_version\nMetro Transit,en,20260101,20261231,1\n",
        )
        store.activate(feedId, zipOf(minimalFeed), metadata)
        val registry = OfflineRegistry(JvmOfflineSnapshotPort(store))

        // First load parses the ZIP, writes the v2 cache (including shapes), and returns shapes omitted.
        val first = registry.loadStaticFeed(feedId, includeShapes = false)
        assertNotNull(first)
        assertTrue(first.shapes.isEmpty())
        assertTrue(first.stops.isNotEmpty())
        val cachedBytes = assertNotNull(store.readCache(feedId))
        assertEquals(StaticFeedBinaryCache.VERSION, StaticFeedBinaryCache.versionOf(cachedBytes))

        // Second load from v2 cache with includeShapes = false still skips shapes.
        val warmNoShapes = registry.loadStaticFeed(feedId, includeShapes = false)
        assertNotNull(warmNoShapes)
        assertTrue(warmNoShapes.shapes.isEmpty())
        assertEquals(first.stops, warmNoShapes.stops)

        // Default caller recovers full shapes from the persisted v2 cache.
        val withShapes = registry.loadStaticFeed(feedId)
        assertNotNull(withShapes)
        assertEquals(1, withShapes.shapes.size)
    }
}
