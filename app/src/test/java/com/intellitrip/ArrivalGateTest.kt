package com.intellitrip

import com.intellitrip.domain.GeoPoint
import com.intellitrip.domain.UserLocation
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.minutes
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant

class ArrivalGateTest {

    private val zone = TimeZone.of("America/Chicago")
    private val now = LocalDateTime(2026, 10, 5, 8, 0).toInstant(zone)
    private val destination = GeoPoint(44.97, -93.26)

    private fun fix(
        position: GeoPoint = destination,
        accuracyMeters: Double = 12.0,
        at: Instant = now,
    ) = UserLocation(position = position, accuracyMeters = accuracyMeters, timestamp = at)

    @Test
    fun freshAccurateFixAtTheDestinationCuesOnce() {
        val first = ArrivalGate.shouldCue(fix(), destination, "journey-1", null, now)
        assertTrue(first)
        val second = ArrivalGate.shouldCue(fix(), destination, "journey-1", "journey-1", now)
        assertFalse("the same journey must not ring twice", second)
    }

    @Test
    fun aNewJourneyGetsItsOwnCue() {
        assertTrue(
            ArrivalGate.shouldCue(fix(), destination, "journey-2", "journey-1", now),
        )
    }

    @Test
    fun staleFixIsNotProofOfArrival() {
        val stale = fix(at = now - 2.minutes)
        assertFalse(ArrivalGate.shouldCue(stale, destination, "journey-1", null, now))
    }

    @Test
    fun coarseFixIsNotProofOfArrival() {
        val coarse = fix(accuracyMeters = 250.0)
        assertFalse(ArrivalGate.shouldCue(coarse, destination, "journey-1", null, now))
    }

    @Test
    fun aFixOutsideTheRadiusDoesNotCue() {
        val far = fix(position = GeoPoint(44.98, -93.26))
        assertFalse(ArrivalGate.shouldCue(far, destination, "journey-1", null, now))
    }
}
