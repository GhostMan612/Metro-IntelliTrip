package com.intellitrip.gtfsrt

import com.intellitrip.contracts.ProviderResult
import com.intellitrip.domain.DataScope
import com.intellitrip.domain.FeedId
import com.intellitrip.domain.GeoPoint
import com.intellitrip.domain.LatLngBounds
import com.intellitrip.domain.RadiusFilter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class PollingGtfsRealtimeClientTest {

    private val feedId = FeedId("metro-transit-regional")
    private val urls = PollingGtfsRealtimeClient.RealtimeFeedUrls(
        vehiclePositions = "https://svc.metrotransit.org/mtgtfs/vehiclepositions.pb",
        tripUpdates = "https://svc.metrotransit.org/mtgtfs/tripupdates.pb",
        alerts = "https://svc.metrotransit.org/mtgtfs/alerts.pb",
    )

    private fun fakeDecoder(feed: RtFeedMessage) = FeedDecoder { feed }

    private fun client(
        fetcher: GtfsRealtimeFetcher,
        decoder: FeedDecoder,
        backoff: Long = 1_000,
    ) = PollingGtfsRealtimeClient(
        feedId = feedId,
        urls = urls,
        fetcher = fetcher,
        pollIntervalMillis = 1_000,
        maxBackoffMillis = backoff,
        clock = { Instant.parse("2026-10-05T00:00:00Z") },
        decoder = decoder,
    )

    @Test
    fun emitsSuccessWithFreshness() = runTest {
        val result = client(
            { RealtimeFixtures.malformedPayload() },
            fakeDecoder(RealtimeFixtures.vehiclePositionsFeed()),
        ).vehiclePositions().first()
        assertIs<ProviderResult.Success<List<com.intellitrip.domain.Vehicle>>>(result)
        assertEquals(1, result.data.size)
        assertEquals("gtfs-realtime", result.freshness.providerId)
        assertTrue(result.freshness.age >= Duration.ZERO)
    }

    @Test
    fun emitsNetworkFailureWithoutCrashing() = runTest {
        val result = client(
            { throw RuntimeException("offline") },
            fakeDecoder(RealtimeFixtures.vehiclePositionsFeed()),
        ).vehiclePositions().first()
        assertIs<ProviderResult.NetworkFailure>(result)
    }

    @Test
    fun emitsMalformedResponseForInvalidPayload() {
        runTest {
            val result = client(
                { RealtimeFixtures.malformedPayload() },
                FeedDecoder { throw GtfsRealtimeException("malformed") },
            ).tripUpdates().first()
            assertIs<ProviderResult.MalformedResponse>(result)
        }
    }

    @Test
    fun emitsAlerts() = runTest {
        val result = client(
            { RealtimeFixtures.malformedPayload() },
            fakeDecoder(RealtimeFixtures.alertsFeed()),
        ).alerts().first()
        assertIs<ProviderResult.Success<List<com.intellitrip.domain.ServiceAlert>>>(result)
        assertEquals(1, result.data.size)
    }

    @Test
    fun scopeFilterLimitsVehicles() = runTest {
        val mapper = GtfsRealtimeMapper(feedId)
        val vehicles = mapper.vehicles(RealtimeFixtures.vehiclePositionsFeed())
        val bounds = LatLngBounds(GeoPoint(0.0, 0.0), GeoPoint(10.0, 10.0))
        val scope = DataScope(null, null, null, null, bounds, null, null, 30.seconds, false, false)
        assertTrue(RealtimeScopeFilter.filter(vehicles, scope).isEmpty())

        val radiusScope = DataScope(null, null, null, null, null, RadiusFilter(GeoPoint(44.89, -93.19), 5_000.0), null, 30.seconds, false, false)
        assertEquals(1, RealtimeScopeFilter.filter(vehicles, radiusScope).size)
    }
}
