package com.intellitrip

import com.intellitrip.domain.FeedId
import com.intellitrip.domain.GeoPoint
import com.intellitrip.domain.Vehicle
import com.intellitrip.domain.VehicleKey
import com.intellitrip.domain.VehicleType
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class VehicleFollowTest {

    private val now = Instant.parse("2026-11-11T12:00:00Z")
    private val feedId = FeedId("metro-transit-regional")
    private val key = VehicleKey(feedId, "bus-4242")
    private val position = GeoPoint(44.9778, -93.2650)

    private fun vehicle(updatedAt: Instant, point: GeoPoint = position) = Vehicle(
        key = key,
        routeKey = null,
        tripKey = null,
        position = point,
        bearing = null,
        speedMps = null,
        updatedAt = updatedAt,
        vehicleType = VehicleType.BUS,
    )

    @Test
    fun freshFixBecomesTheCameraTarget() {
        val update = VehicleFollow.update(key, listOf(vehicle(now - 15.seconds)), now)
        assertEquals(position, update.target)
        assertTrue(update.live)
        assertTrue(update.status.contains("Following"))
        assertTrue(update.status.contains("15s old"))
    }

    @Test
    fun aNewerFixMovesTheTarget() {
        val moved = GeoPoint(44.9800, -93.2500)
        val update = VehicleFollow.update(
            key,
            listOf(vehicle(now, moved)),
            now,
        )
        assertEquals(moved, update.target)
        assertTrue(update.status.contains("live"))
    }

    @Test
    fun staleReportPausesFollowAndSaysWhy() {
        val update = VehicleFollow.update(key, listOf(vehicle(now - 5.minutes)), now)
        assertNull(update.target)
        assertFalse(update.live)
        assertTrue(update.status.contains("paused"))
        assertTrue(update.status.contains("5 min"))
    }

    @Test
    fun missingVehicleDoesNotInventAPosition() {
        val update = VehicleFollow.update(key, emptyList(), now)
        assertNull(update.target)
        assertFalse(update.live)
        assertTrue(update.status.contains("Waiting"))
    }

    @Test
    fun aDifferentVehicleIsNotThisVehicle() {
        val other = vehicle(now).copy(key = VehicleKey(feedId, "bus-9999"))
        val update = VehicleFollow.update(key, listOf(other), now)
        assertNull(update.target)
        assertFalse(update.live)
    }

    @Test
    fun clockSkewIntoTheFutureIsTreatedAsLiveWithoutFailing() {
        val update = VehicleFollow.update(key, listOf(vehicle(now + 30.seconds)), now)
        assertEquals(position, update.target)
        assertTrue(update.live)
        assertTrue(update.status.contains("live"))
    }
}
