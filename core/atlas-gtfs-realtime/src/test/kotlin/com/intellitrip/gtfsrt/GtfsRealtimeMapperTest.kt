package com.intellitrip.gtfsrt

import com.google.transit.realtime.GtfsRealtime
import com.intellitrip.domain.FeedId
import com.intellitrip.domain.RouteKey
import com.intellitrip.domain.TripKey
import com.intellitrip.domain.VehicleKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GtfsRealtimeMapperTest {

    private val feedId = FeedId("metro-transit-regional")
    private val mapper = GtfsRealtimeMapper(feedId)

    @Test
    fun mapsVehiclePositionsIntoFeedScopedKeys() {
        val vehicles = mapper.vehicles(GtfsRealtime.FeedMessage.parseFrom(RealtimeFixtures.vehiclePositionsFeed()))
        assertEquals(1, vehicles.size)
        val vehicle = vehicles.first()
        assertEquals(VehicleKey(feedId, "bus-4242"), vehicle.key)
        assertEquals(RouteKey(feedId, "901"), vehicle.routeKey)
        assertEquals(TripKey(feedId, "1005229"), vehicle.tripKey)
        assertEquals(44.892864, vehicle.position.lat, absoluteTolerance = 0.0001)
    }

    @Test
    fun dropsEntitiesWithoutStableVehicleIdentity() {
        val vehicles = mapper.vehicles(GtfsRealtime.FeedMessage.parseFrom(RealtimeFixtures.vehiclePositionsFeed()))
        assertTrue(vehicles.none { it.key.vehicleId.isBlank() })
    }

    @Test
    fun mapsTripUpdatesWithDelaysAndServiceTime() {
        val updates = mapper.tripUpdates(GtfsRealtime.FeedMessage.parseFrom(RealtimeFixtures.tripUpdatesFeed()))
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
    fun mapsAlertsToFeedScopedRoutesAndStops() {
        val alerts = mapper.alerts(GtfsRealtime.FeedMessage.parseFrom(RealtimeFixtures.alertsFeed()))
        assertEquals(1, alerts.size)
        val alert = alerts.first()
        assertEquals("Blue Line: buses replace trains", alert.headline)
        assertEquals(setOf(RouteKey(feedId, "901")), alert.affectedRoutes)
        assertNull(alert.affectsAgency)
    }

    @Test
    fun vehicleIdsFromDifferentFeedsDoNotCollide() {
        val other = GtfsRealtimeMapper(FeedId("other-feed"))
        val mine = mapper.vehicles(GtfsRealtime.FeedMessage.parseFrom(RealtimeFixtures.vehiclePositionsFeed()))
        val theirs = other.vehicles(GtfsRealtime.FeedMessage.parseFrom(RealtimeFixtures.vehiclePositionsFeed()))
        assertTrue(mine.first().key != theirs.first().key)
    }
}