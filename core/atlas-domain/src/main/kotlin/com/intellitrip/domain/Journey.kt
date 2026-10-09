package com.intellitrip.domain

import java.time.Duration
import java.time.Instant
import java.time.LocalDate

data class TripPlanRequest(
    val origin: GeoPoint,
    val destination: GeoPoint,
    val departAt: Instant?,
    val arriveBy: Instant?,
    val maxTransfers: Int?,
    val walkSpeedMetersPerSecond: Double = 1.35,
    val maxWalkMeters: Double = 2_000.0,
)

/**
 * A multimodal journey. Transfer confidence lives on each [TransferConnection],
 * never on the journey as a whole (ADR-008 / journey model).
 */
data class JourneyOption(
    val id: String,
    val legs: List<Leg>,
    val transfers: List<TransferConnection>,
    val departure: Instant,
    val arrival: Instant,
) {
    val transferCount: Int get() = transfers.size
    val duration: Duration get() = Duration.between(departure, arrival)
}

sealed interface Leg {
    val duration: Duration

    data class WalkLeg(
        val from: GeoPoint,
        val to: GeoPoint,
        val start: Instant,
        override val duration: Duration,
        val meters: Double,
    ) : Leg {
        val end: Instant get() = start.plus(duration)
    }

    data class Transit(
        val agencyId: AgencyId?,
        val routeKey: RouteKey,
        val tripKey: TripKey,
        val serviceDate: LocalDate?,
        val fromStopKey: StopKey,
        val toStopKey: StopKey,
        val departure: Instant,
        val arrival: Instant,
        val vehicleKey: VehicleKey?,
        val realtimeStatus: RealtimeStatus,
        val delaySeconds: Int?,
        val scheduledDeparture: Instant?,
        val scheduledArrival: Instant?,
        override val duration: Duration,
    ) : Leg {
        fun delaySecondsOrZero(): Int = delaySeconds ?: 0
    }
}

/**
 * A single connection between an arriving leg and a departing leg. Phase 07
 * scores these objects rather than the journey.
 */
data class TransferConnection(
    val arrivingLeg: Leg.Transit,
    val departingLeg: Leg.Transit,
    val transferStop: StopKey,
    val scheduledBuffer: Duration,
    val predictedBuffer: Duration?,
    val walkingDuration: Duration?,
    val confidence: TransferConfidence,
    val rationale: String?,
)

enum class TransferConfidence { HIGH, MEDIUM, LOW, UNKNOWN, SCHEDULED_UNKNOWN }

enum class RealtimeStatus { SCHEDULED, ON_TIME, DELAYED, EARLY, CANCELED, NO_DATA }