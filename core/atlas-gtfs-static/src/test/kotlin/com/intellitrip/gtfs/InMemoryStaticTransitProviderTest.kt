package com.intellitrip.gtfs

import com.intellitrip.contracts.ProviderResult
import com.intellitrip.contracts.TransitStaticProvider
import com.intellitrip.domain.AgencyId
import com.intellitrip.domain.FeedId
import com.intellitrip.domain.FeedMetadata
import com.intellitrip.domain.RouteKey
import com.intellitrip.domain.TripKey
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class InMemoryStaticTransitProviderTest {

    private val feedId = FeedId("metro-transit-regional")
    private val metadata = FeedMetadata(feedId, null, null, null, "1", "hash", Instant.EPOCH, null, null, null)

    private fun provider(): TransitStaticProvider =
        InMemoryStaticTransitProvider(GtfsStaticParser.parse(feedId, GtfsFixtures.minimalFeed, metadata))

    @Test
    fun contractsReturnProviderResults() {
        runBlocking {
            val provider = provider()
            assertIs<ProviderResult.Success<*>>(provider.agencies())
            assertIs<ProviderResult.Success<*>>(provider.routes())
            assertIs<ProviderResult.Success<*>>(provider.stops())
            assertIs<ProviderResult.Success<*>>(provider.trips())
            assertIs<ProviderResult.Success<*>>(provider.stopTimes(TripKey(feedId, "1001")))
            assertIs<ProviderResult.Success<*>>(provider.calendar())
            assertIs<ProviderResult.Success<*>>(provider.calendarDates())
            assertIs<ProviderResult.Success<*>>(provider.shapes())
            assertIs<ProviderResult.Success<*>>(provider.frequencies())
            assertIs<ProviderResult.Success<*>>(provider.transfers())
            assertIs<ProviderResult.Success<*>>(provider.feedMetadata())
        }
    }

    @Test
    fun routesFilterByAgency() {
        runBlocking {
            val result = provider().routes(AgencyId("metro-transit-regional/1"))
            assertIs<ProviderResult.Success<List<com.intellitrip.domain.Route>>>(result)
            assertEquals(1, result.data.size)
        }
    }

    @Test
    fun stopTimesAreScopedByTrip() {
        runBlocking {
            val result = provider().stopTimes(TripKey(feedId, "unknown"))
            assertIs<ProviderResult.Success<*>>(result)
            assertTrue((result.data as List<*>).isEmpty())
        }
    }

    @Test
    fun shapesAreScopedByRoute() {
        runBlocking {
            val result = provider().shapes(RouteKey(feedId, "901"))
            assertIs<ProviderResult.Success<*>>(result)
            assertEquals(1, (result.data as List<*>).size)
        }
    }
}