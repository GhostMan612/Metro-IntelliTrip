package com.intellitrip.offline

import com.intellitrip.domain.FeedId
import com.intellitrip.domain.FeedMetadata
import com.intellitrip.domain.Vehicle
import com.intellitrip.domain.VehicleKey
import com.intellitrip.domain.VehicleType
import com.intellitrip.gtfs.GtfsStaticParser
import com.intellitrip.gtfs.StaticFeedSnapshotStore
import java.time.Duration
import java.time.Instant
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StalenessModelTest {

    private val now = Instant.parse("2026-10-09T12:00:00Z")
    private val model = StalenessModel(
        FreshnessPolicy(freshWithin = Duration.ofHours(1), usableWithin = Duration.ofDays(1))
    )

    @Test
    fun recentDataIsFresh() {
        assertEquals(Freshness.FRESH, model.classify(now.minusSeconds(600), now))
    }

    @Test
    fun agingDataIsStaleButUsable() {
        assertEquals(Freshness.STALE, model.classify(now.minus(Duration.ofHours(6)), now))
    }

    @Test
    fun oldDataIsExpired() {
        assertEquals(Freshness.EXPIRED, model.classify(now.minus(Duration.ofDays(3)), now))
    }

    @Test
    fun missingDataIsAbsent() {
        assertEquals(Freshness.ABSENT, model.classify(null, now))
    }

    @Test
    fun onlyFreshRealtimeIsPresentableAsLive() {
        assertTrue(model.isPresentableAsLive(CacheEntry(FeedId("f"), now.minusSeconds(60)), now))
        assertFalse(model.isPresentableAsLive(CacheEntry(FeedId("f"), now.minus(Duration.ofHours(6))), now))
        assertFalse(model.isPresentableAsLive(null, now))
    }

    @Test
    fun staticStaysUsableWhileNotExpired() {
        assertTrue(model.isUsableStatic(CacheEntry(FeedId("f"), now.minus(Duration.ofHours(6))), now))
        assertFalse(model.isUsableStatic(CacheEntry(FeedId("f"), now.minus(Duration.ofDays(3))), now))
    }
}

class OfflineRegistryTest {

    private val feedId = FeedId("metro-transit-regional")
    private val now = Instant.parse("2026-10-09T12:00:00Z")

    private val payload: ByteArray = GtfsFixtureBytes.validFeed()
    private val store = StaticFeedSnapshotStore(createTempDirectory("offline-store"))

    private fun storedMetadata(fetchedAt: Instant = now) = FeedMetadata(
        feedId = feedId,
        publisherName = null,
        publisherUrl = null,
        lang = null,
        version = "1",
        hash = "abc123",
        fetchedAt = fetchedAt,
        validFrom = java.time.LocalDate.parse("20261001", java.time.format.DateTimeFormatter.BASIC_ISO_DATE),
        validTo = java.time.LocalDate.parse("20261231", java.time.format.DateTimeFormatter.BASIC_ISO_DATE),
        sourceUrl = "https://svc.metrotransit.org/mtgtfs/gtfs.zip",
    )

    private fun vehicle() = Vehicle(
        key = VehicleKey(feedId, "bus-1"),
        routeKey = null,
        tripKey = null,
        position = com.intellitrip.domain.GeoPoint(44.97, -93.26),
        bearing = null,
        speedMps = null,
        updatedAt = now,
        vehicleType = VehicleType.BUS,
    )

    @Test
    fun noSnapshotMeansNoStaticNetwork() {
        val capabilities = OfflineRegistry(store).capabilities(feedId, now = now)
        assertFalse(capabilities.staticNetworkUsable)
        assertEquals(Freshness.ABSENT, capabilities.staticFreshness)
        assertTrue(capabilities.notes.isNotEmpty())
        assertNull(OfflineRegistry(store).loadStaticFeed(feedId))
    }

    @Test
    fun storedSnapshotEnablesStaticPlanning() {
        store.activate(feedId, payload, storedMetadata())
        val registry = OfflineRegistry(store)
        val capabilities = registry.capabilities(feedId, now = now)
        assertTrue(capabilities.staticNetworkUsable)
        assertTrue(capabilities.routingAvailable)
        val feed = registry.loadStaticFeed(feedId)
        assertNotNull(feed)
        assertTrue(feed.stops.isNotEmpty())
        assertEquals(feedId, feed.feedId)
    }

    @Test
    fun realtimeIsNotUsableWithoutCachedData() {
        store.activate(feedId, payload, storedMetadata())
        val capabilities = OfflineRegistry(store).capabilities(feedId, now = now)
        assertFalse(capabilities.realtimeUsable)
        assertFalse(capabilities.realtimeCached)
        assertEquals(Freshness.ABSENT, capabilities.realtimeFreshness)
    }

    @Test
    fun staleRealtimeIsCachedButNeverPresentedAsLive() {
        val registry = OfflineRegistry(store)
        // default policy: fresh for 12h, usable for 7d -> 13h old is stale
        val entry = CacheEntry(feedId, now.minus(Duration.ofHours(13)))
        val capabilities = registry.capabilities(feedId, realtimeEntry = entry, now = now)
        assertTrue(capabilities.realtimeCached)
        assertFalse(capabilities.realtimeUsable)
        assertEquals(Freshness.STALE, capabilities.realtimeFreshness)
        assertTrue(registry.liveVehicles(entry, listOf(vehicle()), now).isEmpty())
        assertTrue(capabilities.notes.any { it.contains("stale") })
    }

    @Test
    fun freshRealtimeIsPresentedAsLive() {
        val entry = CacheEntry(feedId, now.minusSeconds(60))
        val registry = OfflineRegistry(store)
        assertEquals(1, registry.liveVehicles(entry, listOf(vehicle()), now).size)
    }

    @Test
    fun missingOfflineBasemapIsReported() {
        val capabilities = OfflineRegistry(store).capabilities(
            feedId = feedId,
            basemap = BasemapAvailability.OfflineOnlyMissing,
            now = now,
        )
        assertTrue(capabilities.notes.any { it.contains("basemap") })
    }

    @Test
    fun offlineParsingRejectsCorruptPayload() {
        store.activate(feedId, "not-a-zip".toByteArray(), storedMetadata())
        assertNull(OfflineRegistry(store).loadStaticFeed(feedId))
    }

    @Test
    fun offlinePlannerUsesStoredNetwork() {
        store.activate(feedId, payload, storedMetadata())
        val feed = OfflineRegistry(store).loadStaticFeed(feedId)
        assertNotNull(feed)
        val planner = com.intellitrip.routing.StaticNetworkPlanner(
            com.intellitrip.routing.RoutingNetwork(
                feedId = feed.feedId,
                metadata = feed.metadata,
                routes = feed.routes,
                stops = feed.stops,
                trips = feed.trips,
                stopTimes = feed.stopTimes,
                transfers = feed.transfers,
            )
        )
        val result = kotlinx.coroutines.runBlocking {
            planner.plan(
                com.intellitrip.domain.TripPlanRequest(
                    origin = feed.stops.first().location,
                    destination = feed.stops.last().location,
                    departAt = now,
                    arriveBy = null,
                    maxTransfers = 0,
                )
            )
        }
        // offline planning runs entirely from the stored snapshot
        assertNotNull(result)
    }
}

class BasemapPolicyTest {

    @Test
    fun onlineOnlyWhenNoPackageInstalled() {
        assertEquals(BasemapPreference.Online, BasemapPolicy(onlineEnabled = true).preference())
    }

    @Test
    fun offlinePackagePreferredWhenInstalled() {
        assertEquals(
            BasemapPreference.OfflinePackage,
            BasemapPolicy(onlineEnabled = true, offlineInstalled = true).preference(),
        )
    }

    @Test
    fun noBasemapWhenOfflineOnlyAndMissing() {
        assertEquals(
            BasemapPreference.None,
            BasemapPolicy(onlineEnabled = false, offlineInstalled = false).preference(),
        )
    }
}