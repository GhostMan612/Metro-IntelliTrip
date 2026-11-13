package com.intellitrip

import com.intellitrip.domain.FeedId
import com.intellitrip.domain.GtfsServiceTime
import com.intellitrip.domain.RouteKey
import com.intellitrip.domain.ScheduleRelationship
import com.intellitrip.domain.StopKey
import com.intellitrip.domain.StopTimeRelationship
import com.intellitrip.domain.StopTimeUpdate
import com.intellitrip.domain.TripKey
import com.intellitrip.domain.TripUpdate
import com.intellitrip.ui.DepartureStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.time.Duration.Companion.seconds
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant

class DepartureRealtimeTest {

    private val zone = TimeZone.of("America/Chicago")
    private val feedId = FeedId("test")
    private val now = LocalDateTime(2026, 10, 5, 7, 0).toInstant(zone)
    private val scheduled = now + 600.seconds
    private val serviceDate = LocalDate(2026, 10, 5)

    private fun update(
        stopId: String = "S1",
        startDate: LocalDate? = serviceDate,
        timestamp: Instant? = now - 10.seconds,
        stopRelationship: StopTimeRelationship = StopTimeRelationship.SCHEDULED,
        departureTime: Instant? = scheduled,
        departureDelay: Int? = null,
        tripRelationship: ScheduleRelationship = ScheduleRelationship.SCHEDULED,
        startTime: String? = null,
    ) = TripUpdate(
        tripKey = TripKey(feedId, "T1"),
        routeKey = RouteKey(feedId, "R1"),
        vehicleKey = null,
        directionId = null,
        startDate = startDate,
        startTime = startTime?.let { GtfsServiceTime.parse(it) },
        timestamp = timestamp,
        scheduleRelationship = tripRelationship,
        stopTimeUpdates = listOf(
            StopTimeUpdate(
                stopKey = StopKey(feedId, stopId),
                arrivalDelaySeconds = null,
                departureDelaySeconds = departureDelay,
                departureTime = departureTime,
                scheduleRelationship = stopRelationship,
            ),
        ),
    )

    private fun resolve(
        updates: List<TripUpdate>,
        frequencyRunStartSeconds: Int? = null,
    ) = DepartureRealtime.resolve(
        updates = updates,
        tripId = "T1",
        stopId = "S1",
        serviceDate = serviceDate,
        scheduled = scheduled,
        now = now,
        fetchedAt = now - 5.seconds,
        frequencyRunStartSeconds = frequencyRunStartSeconds,
    )

    @Test
    fun vehicleOnlyFeedProducesNoPrediction() {
        assertNull(resolve(emptyList()))
    }

    @Test
    fun absoluteStopTimeIsALivePrediction() {
        val result = resolve(listOf(update(departureTime = now + 120.seconds)))!!
        assertEquals(DepartureStatus.LIVE, result.status)
        assertEquals(now + 120.seconds, result.time)
        assertEquals(now - 10.seconds, result.reportedAt)
    }

    @Test
    fun delayIsAppliedToTheScheduledOccurrence() {
        val result = resolve(listOf(update(departureTime = null, departureDelay = 180)))!!
        assertEquals(DepartureStatus.LIVE, result.status)
        assertEquals(scheduled + 180.seconds, result.time)
    }

    @Test
    fun predictionForAnotherServiceDateIsIgnored() {
        assertNull(resolve(listOf(update(startDate = LocalDate(2026, 10, 4)))))
    }

    @Test
    fun stalePredictionFallsBackToSchedule() {
        assertNull(resolve(listOf(update(timestamp = now - 121.seconds))))
    }

    @Test
    fun predictionForAnotherStopIsIgnored() {
        assertNull(resolve(listOf(update(stopId = "S2"))))
    }

    @Test
    fun skippedStopIsAnExplicitStateNotALiveTime() {
        val result = resolve(
            listOf(update(stopRelationship = StopTimeRelationship.SKIPPED)),
        )!!
        assertEquals(DepartureStatus.SKIPPED, result.status)
    }

    @Test
    fun canceledTripIsAnExplicitState() {
        val result = resolve(
            listOf(update(tripRelationship = ScheduleRelationship.CANCELED)),
        )!!
        assertEquals(DepartureStatus.CANCELED, result.status)
    }

    @Test
    fun frequencyPredictionMustMatchTheRunStartTime() {
        assertNull(
            resolve(
                listOf(update(startTime = "06:00:00")),
                frequencyRunStartSeconds = 7 * 3_600,
            ),
        )
        val matching = resolve(
            listOf(update(startTime = "07:00:00")),
            frequencyRunStartSeconds = 7 * 3_600,
        )
        assertEquals(DepartureStatus.LIVE, matching?.status)
    }
}
