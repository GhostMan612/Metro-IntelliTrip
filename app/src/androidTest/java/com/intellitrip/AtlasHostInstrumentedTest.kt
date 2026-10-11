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

    /**
     * Journey geometry must be derived from the feed's shapes rather than drawn from
     * straight lines between stops, and must hold up on the device's real heap.
     */
    @Test
    fun routeGeometryFollowsFeedShapes() {
        val feedId = com.intellitrip.domain.FeedId(FEED_ID)
        val feed = OfflineRegistry(
            StaticFeedSnapshotStore(Files.createTempDirectory("device-geometry")).also {
                it.activate(feedId, GtfsFixture(withShapes = true).zip(), com.intellitrip.domain.FeedMetadata(
                    feedId, null, null, null, "1", "h", java.time.Instant.now(), null, null, null,
                ))
            }
        ).loadStaticFeed(feedId)!!

        assertTrue("fixture should carry a shape", feed.shapes.isNotEmpty())
        val trip = feed.trips.first()
        val shapeKey = trip.shapeKey
        assertNotNull("trip should reference a shape", shapeKey)
        val shape = feed.shapes.first { it.key == shapeKey }
        assertTrue("shape should have several points", shape.points.size >= 3)

        val legs = RouteGeometry.build(
            journey = transitJourney(feedId, trip),
            shapes = feed.shapes,
            stops = feed.stops,
            trips = feed.trips,
        )
        assertEquals("one line per transit leg", 1, legs.size)
        val line = legs.single()
        // Walking the shape, not the two endpoints: a straight line has 2 points.
        assertTrue(
            "expected geometry along the shape, got ${line.coordinates.size} points",
            line.coordinates.size > 2,
        )
        // GeoJSON order is [lon, lat]; the shape is latitude-first in the domain.
        val firstShapePoint = shape.points.first()
        assertEquals(firstShapePoint.lon, line.coordinates.first()[0], 1e-6)
        assertEquals(firstShapePoint.lat, line.coordinates.first()[1], 1e-6)
    }

    /** A journey with no matching shape still draws a straight connector, not nothing. */
    @Test
    fun routeGeometryFallsBackWhenShapeIsMissing() {
        val feedId = com.intellitrip.domain.FeedId(FEED_ID)
        val feed = OfflineRegistry(
            StaticFeedSnapshotStore(Files.createTempDirectory("device-geometry-fallback")).also {
                it.activate(feedId, GtfsFixture(withShapes = false).zip(), com.intellitrip.domain.FeedMetadata(
                    feedId, null, null, null, "1", "h", java.time.Instant.now(), null, null, null,
                ))
            }
        ).loadStaticFeed(feedId)!!
        assertTrue("fixture should have no shapes", feed.shapes.isEmpty())

        val trip = feed.trips.first().copy(shapeKey = null)
        val legs = RouteGeometry.build(
            journey = transitJourney(feedId, trip),
            shapes = emptyList(),
            stops = feed.stops,
            trips = listOf(trip),
        )
        assertEquals("still one line per leg", 1, legs.size)
        assertEquals("a connector is the two endpoints", 2, legs.single().coordinates.size)
    }

    private fun transitJourney(
        feedId: com.intellitrip.domain.FeedId,
        trip: com.intellitrip.domain.Trip,
    ): com.intellitrip.domain.JourneyOption {
        val departure = java.time.Instant.parse("2026-10-05T13:00:00Z")
        return com.intellitrip.domain.JourneyOption(
            id = "test",
            legs = listOf(
                com.intellitrip.domain.Leg.Transit(
                    agencyId = null,
                    routeKey = trip.routeKey,
                    tripKey = trip.key,
                    serviceDate = null,
                    fromStopKey = com.intellitrip.domain.StopKey(feedId, "S1"),
                    toStopKey = com.intellitrip.domain.StopKey(feedId, "S2"),
                    departure = departure,
                    arrival = departure.plusSeconds(600),
                    vehicleKey = null,
                    realtimeStatus = com.intellitrip.domain.RealtimeStatus.Scheduled,
                    delaySeconds = null,
                    scheduledDeparture = null,
                    scheduledArrival = null,
                    duration = java.time.Duration.ofSeconds(600),
                )
            ),
            transfers = emptyList(),
            departure = departure,
            arrival = departure.plusSeconds(600),
        )
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