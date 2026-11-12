package com.intellitrip

import com.intellitrip.domain.ScheduleRelationship
import com.intellitrip.domain.StopTimeRelationship
import com.intellitrip.domain.TripUpdate
import com.intellitrip.ui.DepartureStatus
import kotlin.math.abs
import kotlin.time.Duration.Companion.seconds
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate

/**
 * Resolves a scheduled departure against GTFS-Realtime trip updates.
 *
 * This is deliberately pure and strict:
 *
 * - the update must match the trip, stop and service date;
 * - a frequency run must match the provider's start time;
 * - the provider timestamp must be fresh;
 * - a vehicle-position-only feed never produces a prediction;
 * - skipped/canceled stops are explicit states, not a fabricated ETA.
 *
 * Every rejection returns null, which the board renders as Schedule.
 */
internal object DepartureRealtime {

    /** Same freshness window as the vehicle-follow and live-badge rules. */
    const val MAX_PREDICTION_AGE_SECONDS: Long = 120L

    /** Guards against applying a prediction for a different occurrence. */
    private const val MAX_DEVIATION_SECONDS: Long = 6L * 60L * 60L

    /** Frequency start times are minute-granular in GTFS. */
    private const val FREQUENCY_START_TOLERANCE_SECONDS: Int = 60

    data class Prediction(
        val time: Instant,
        val reportedAt: Instant,
        val status: DepartureStatus,
    )

    fun resolve(
        updates: List<TripUpdate>,
        tripId: String,
        stopId: String,
        serviceDate: LocalDate,
        scheduled: Instant,
        now: Instant,
        fetchedAt: Instant?,
        frequencyRunStartSeconds: Int? = null,
    ): Prediction? {
        for (update in updates) {
            if (update.tripKey.tripId != tripId) continue
            // A provider that supplies a service date is authoritative. An
            // update for yesterday's run must never upgrade today's board.
            if (update.startDate != null && update.startDate != serviceDate) continue

            if (frequencyRunStartSeconds != null) {
                val start = update.startTime?.secondsSinceServiceDayStart ?: continue
                if (abs(start - frequencyRunStartSeconds) > FREQUENCY_START_TOLERANCE_SECONDS) continue
            }

            val reportedAt = update.timestamp ?: fetchedAt ?: continue
            val age = (now - reportedAt).inWholeSeconds
            if (age !in 0..MAX_PREDICTION_AGE_SECONDS) continue

            if (update.scheduleRelationship == ScheduleRelationship.CANCELED) {
                return Prediction(scheduled, reportedAt, DepartureStatus.CANCELED)
            }

            val stopUpdate = update.stopTimeUpdates.firstOrNull {
                it.stopKey.stopId == stopId
            } ?: continue

            when (stopUpdate.scheduleRelationship) {
                StopTimeRelationship.SKIPPED ->
                    return Prediction(scheduled, reportedAt, DepartureStatus.SKIPPED)
                StopTimeRelationship.NO_DATA,
                StopTimeRelationship.UNSCHEDULED,
                -> continue

                StopTimeRelationship.SCHEDULED -> Unit
            }

            // Prefer the provider's absolute time; otherwise apply its delay to
            // this occurrence. Never use the vehicle timestamp as an ETA.
            val absolute = stopUpdate.departureTime ?: stopUpdate.arrivalTime
            val delay = stopUpdate.departureDelaySeconds ?: stopUpdate.arrivalDelaySeconds
            val predicted = absolute ?: delay?.let { scheduled + it.seconds } ?: continue

            if (abs((predicted - scheduled).inWholeSeconds) > MAX_DEVIATION_SECONDS) continue

            return Prediction(predicted, reportedAt, DepartureStatus.LIVE)
        }
        return null
    }
}
