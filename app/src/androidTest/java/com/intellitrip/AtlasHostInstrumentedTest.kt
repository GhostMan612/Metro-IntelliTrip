package com.intellitrip

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.intellitrip.gtfs.StaticFeedSnapshotStore
import com.intellitrip.gtfs.StaticFeedSnapshotStore as Store
import com.intellitrip.offline.OfflineRegistry
import com.intellitrip.routing.RoutingNetwork
import com.intellitrip.routing.StaticNetworkPlanner
import com.intellitrip.domain.TripPlanRequest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.file.Files

/**
 * Device-side smoke tests: verifies the engine works against the real app
 * process, real filesystem and real heap limits rather than a desktop JVM.
 */
@RunWith(AndroidJUnit4::class)
class AtlasHostInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun hostBuildsProvidersWithoutNetwork() {
        val host = AtlasHost(context)
        assertNotNull(host.feedId)
        assertEquals(com.intellitrip.domain.FeedId(FEED_ID), host.feedId)
        assertNotNull(host.snapshotStore)
        assertNotNull(host.realtime)
    }

    @Test
    fun snapshotStoreRoundTripsOnDevice() {
        val root = Files.createTempDirectory("device-store")
        val store = Store(root)
        val payload = GtfsFixture().zip()
        val metadata = com.intellitrip.domain.FeedMetadata(
            feedId = com.intellitrip.domain.FeedId(FEED_ID),
            publisherName = null,
            publisherUrl = null,
            lang = null,
            version = "1",
            hash = "abc",
            fetchedAt = java.time.Instant.now(),
            validFrom = null,
            validTo = null,
            sourceUrl = null,
        )
        store.activate(com.intellitrip.domain.FeedId(FEED_ID), payload, metadata)
        val snapshot = store.activeSnapshot(com.intellitrip.domain.FeedId(FEED_ID))
        assertNotNull(snapshot)
        assertEquals("abc", snapshot!!.metadata.hash)
        assertTrue(store.readPayload(com.intellitrip.domain.FeedId(FEED_ID)).isNotEmpty())
    }

    @Test
    fun offlineRegistryReportsMissingSnapshot() {
        val root = Files.createTempDirectory("device-offline")
        val registry = OfflineRegistry(StaticFeedSnapshotStore(root))
        val capabilities = registry.capabilities(com.intellitrip.domain.FeedId(FEED_ID))
        assertTrue(!capabilities.staticNetworkUsable)
        assertTrue(capabilities.notes.isNotEmpty())
    }

    @Test
    fun plannerRunsOnDeviceFilesystem() = runBlocking {
        val store = StaticFeedSnapshotStore(Files.createTempDirectory("device-plan"))
        val feedId = com.intellitrip.domain.FeedId(FEED_ID)
        store.activate(feedId, GtfsFixture().zip(), com.intellitrip.domain.FeedMetadata(
            feedId, null, null, null, "1", "h", java.time.Instant.now(), null, null, null,
        ))
        val registry = OfflineRegistry(store)
        val feed = registry.loadStaticFeed(feedId)
        assertNotNull("stored fixture should parse on device", feed)
        val planner = StaticNetworkPlanner(
            RoutingNetwork(
                feedId = feed!!.feedId,
                metadata = feed.metadata,
                routes = feed.routes,
                stops = feed.stops,
                trips = feed.trips,
                stopTimes = feed.stopTimes,
            )
        )
        val result = planner.plan(
            TripPlanRequest(
                origin = feed.stops.first().location,
                destination = feed.stops.last().location,
                departAt = java.time.LocalDate.of(2026, 10, 5).atTime(7, 0)
                    .toInstant(java.time.ZoneOffset.ofHours(-5)),
                arriveBy = null,
                maxTransfers = 0,
            )
        )
        assertNotNull(result)
    }
}

/** Small deterministic GTFS archive used by device tests (no network). */
internal class GtfsFixture {
    fun zip(): ByteArray {
        val files = linkedMapOf(
            "agency.txt" to "agency_id,agency_name,agency_url,agency_timezone\n1,Metro Transit,https://www.metrotransit.org,America/Chicago\n",
            "routes.txt" to "route_id,agency_id,route_short_name,route_long_name,route_type\n901,1,A,Blue Line,0\n",
            "stops.txt" to "stop_id,stop_name,stop_lat,stop_lon\nS1,First,44.9700,-93.2600\nS2,Second,44.9850,-93.2650\n",
            "trips.txt" to "route_id,service_id,trip_id,shape_id\n901,SVC,T1,SH1\n",
            "stop_times.txt" to "trip_id,arrival_time,departure_time,stop_id,stop_sequence\nT1,08:00:00,08:00:00,S1,1\nT1,25:30:00,25:30:00,S2,2\n",
            "calendar.txt" to "service_id,monday,tuesday,wednesday,thursday,friday,saturday,sunday,start_date,end_date\nSVC,1,1,1,1,1,0,0,20261001,20261231\n",
        )
        val out = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use { zip ->
            files.forEach { (name, content) ->
                zip.putNextEntry(java.util.zip.ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }
}