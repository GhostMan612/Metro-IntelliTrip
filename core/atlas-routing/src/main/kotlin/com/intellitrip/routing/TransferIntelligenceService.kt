package com.intellitrip.routing

import com.intellitrip.domain.JourneyOption
import com.intellitrip.domain.Transfer
import com.intellitrip.domain.TransferConnection
import com.intellitrip.domain.TripUpdate
import java.time.Instant

/**
 * Applies transfer intelligence to journeys.
 *
 * Confidence is computed per [TransferConnection]; the journey itself stays
 * confidence-free. Scoring runs after planning so realtime data can be applied
 * without changing the underlying schedule.
 */
class TransferIntelligenceService(
    private val scorer: TransferConfidenceScorer = TransferConfidenceScorer(),
) {

    data class JourneyReport(
        val journey: JourneyOption,
        val worstConfidence: com.intellitrip.domain.TransferConfidence?,
        val warnings: List<String>,
    )

    fun evaluate(
        journeys: List<JourneyOption>,
        updates: List<TripUpdate>,
        transfers: List<Transfer> = emptyList(),
        now: Instant = Instant.now(),
    ): List<JourneyReport> = journeys.map { journey -> evaluate(journey, updates, transfers, now) }

    fun evaluate(
        journey: JourneyOption,
        updates: List<TripUpdate>,
        transfers: List<Transfer> = emptyList(),
        now: Instant = Instant.now(),
    ): JourneyReport {
        val scoredConnections = journey.transfers.map { connection ->
            scorer.score(
                TransferConfidenceScorer.Inputs(
                    connection = connection,
                    arrivingUpdate = updates.firstOrNull { it.tripKey == connection.arrivingLeg.tripKey },
                    departingUpdate = updates.firstOrNull { it.tripKey == connection.departingLeg.tripKey },
                    transfer = transfers.firstOrNull { it.fromStopKey == connection.transferStop },
                    now = now,
                )
            )
        }
        val updatedJourney = journey.copy(transfers = scoredConnections)
        val warnings = scoredConnections
            .filter { it.confidence == com.intellitrip.domain.TransferConfidence.LOW }
            .mapNotNull { it.rationale }
        return JourneyReport(
            journey = updatedJourney,
            worstConfidence = worstTransferConfidence(scoredConnections),
            warnings = warnings,
        )
    }
}