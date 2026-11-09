package com.intellitrip.gtfsrt

import com.intellitrip.contracts.ProviderResult
import com.intellitrip.domain.FeedId
import com.intellitrip.domain.RouteKey
import com.intellitrip.domain.TripKey
import com.intellitrip.domain.VehicleKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Verifies the JVM actual decoder: real Java-protobuf bytes decode into the
 * portable model and map identically to the pre-KMP expectations.
 */
class JvmFeedDecoderTest {

    private val feedId = FeedId("metro-transit-regional")
    private val mapper = GtfsRealtimeMapper(feedId)
    private val decoder = platformFeedDecoder()

    @Test
    fun decodesVehiclePositionsFromRealProtobuf() {
        val feed = decoder.decode(RealtimeProtoFixtures.vehiclePositionsFeed())
        val vehicles = mapper.vehicles(feed)
        assertEquals(1, vehicles.size)
        val vehicle = vehicles.first()
        assertEquals(VehicleKey(feedId, "bus-4242"), vehicle.key)
        assertEquals(RouteKey(feedId, "901"), vehicle.routeKey)
        assertEquals(TripKey(feedId, "1005229"), vehicle.tripKey)
        assertEquals(44.892864, vehicle.position.lat, absoluteTolerance = 0.0001)
        assertEquals(1_760_000_000L, feed.headerTimestamp)
    }

    @Test
    fun decodesTripUpdatesFromRealProtobuf() {
        val updates = mapper.tripUpdates(decoder.decode(RealtimeProtoFixtures.tripUpdatesFeed()))
        assertEquals(1, updates.size)
        val update = updates.first()
        assertEquals(TripKey(feedId, "1005229"), update.tripKey)
        assertEquals(VehicleKey(feedId, "bus-4242"), update.vehicleKey)
        assertEquals(90600, update.startTime?.secondsSinceServiceDayStart)
        val stopUpdate = update.stopTimeUpdates.single()
        assertEquals(120, stopUpdate.arrivalDelaySeconds)
        assertEquals(150, stopUpdate.departureDelaySeconds)
    }

    @Test
    fun decodesAlertsFromRealProtobuf() {
        val alerts = mapper.alerts(decoder.decode(RealtimeProtoFixtures.alertsFeed()))
        assertEquals(1, alerts.size)
        val alert = alerts.first()
        assertEquals("Blue Line: buses replace trains", alert.headline)
        assertEquals(setOf(RouteKey(feedId, "901")), alert.affectedRoutes)
        assertNull(alert.affectsAgency)
    }

    @Test
    fun malformedBytesFailAsGtfsRealtimeException() {
        // "this is definitely not a protobuf feed message" is not valid
        // proto for a FeedMessage; the decoder must surface it as a
        // GtfsRealtimeException (polled as MalformedResponse).
        assertFailsWith<GtfsRealtimeException> {
            decoder.decode(RealtimeProtoFixtures.malformedPayload())
        }
    }

    @Test
    fun pollingClientDecodesRealProtobufBytes() = runTest {
        val client = PollingGtfsRealtimeClient(
            feedId = feedId,
            urls = PollingGtfsRealtimeClient.RealtimeFeedUrls(
                vehiclePositions = "https://example.invalid/vp.pb",
                tripUpdates = "https://example.invalid/tu.pb",
                alerts = "https://example.invalid/alerts.pb",
            ),
            fetcher = { RealtimeProtoFixtures.vehiclePositionsFeed() },
            pollIntervalMillis = 1_000,
            maxBackoffMillis = 1_000,
            clock = { Instant.parse("2026-10-05T00:00:00Z") },
        )
        val result = client.vehiclePositions().first()
        assertIs<ProviderResult.Success<List<com.intellitrip.domain.Vehicle>>>(result)
        assertEquals(1, result.data.size)
        assertEquals(VehicleKey(feedId, "bus-4242"), result.data.first().key)
        assertTrue(result.freshness.sourceTimestamp != null)
    }
}
