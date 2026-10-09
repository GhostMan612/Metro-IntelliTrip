package com.intellitrip.routing

import com.intellitrip.domain.Leg
import com.intellitrip.domain.RealtimeStatus
import com.intellitrip.domain.ScheduleRelationship
import com.intellitrip.domain.Transfer
import com.intellitrip.domain.TransferConfidence
import com.intellitrip.domain.TransferConnection
import com.intellitrip.domain.TripUpdate
import java.time.Duration
import java.time.Instant

/**
 * Scores a single [TransferConnection].
 *
 * Every outcome carries a rationale so the UI can explain itself. The scorer is
 * deliberately conservative: absent or stale realtime information yields
 * `UNKNOWN` rather than false reassurance.
 */
class TransferConfidenceScorer(
    private val comfortableBuffer: Duration = Duration.ofMinutes(5),
    private val tightBuffer: Duration = Duration.ofMinutes(2),
    private val staleAfter: Duration = Duration.ofMinutes(10),
) {

    data class Inputs(
        val connection: TransferConnection,
        val arrivingUpdate: TripUpdate? = null,
        val departingUpdate: TripUpdate? = null,
        val transfer: Transfer? = null,
        val now: Instant = Instant.now(),
    )

    fun score(inputs: Inputs): TransferConnection {
        val scored = evaluate(inputs)
        return inputs.connection.copy(confidence = scored.confidence, rationale = scored.rationale)
    }

    private data class Verdict(val confidence: TransferConfidence, val rationale: String)

    private fun evaluate(inputs: Inputs): Verdict {
        val connection = inputs.connection
        val arriving = inputs.arrivingUpdate
        val departing = inputs.departingUpdate

        if (departing?.scheduleRelationship == ScheduleRelationship.CANCELED ||
            connection.departingLeg.realtimeStatus == RealtimeStatus.CANCELED
        ) {
            return Verdict(TransferConfidence.LOW, "Departing trip is canceled")
        }

        // Without usable realtime information we do not claim certainty.
        if (arriving == null || departing == null) {
            return Verdict(TransferConfidence.SCHEDULED_UNKNOWN, "No realtime information for this transfer")
        }
        val stale = isStale(arriving, inputs.now) || isStale(departing, inputs.now)
        if (stale) {
            return Verdict(TransferConfidence.SCHEDULED_UNKNOWN, "Realtime information is stale")
        }

        val minTransfer = inputs.transfer?.minTransferSeconds
        val buffer = connection.predictedBuffer ?: connection.scheduledBuffer

        if (buffer.isNegative) {
            return Verdict(TransferConfidence.LOW, "Predicted arrival is after the connecting departure")
        }
        if (minTransfer != null && buffer.seconds < minTransfer) {
            return Verdict(
                TransferConfidence.LOW,
                "Buffer ${buffer.seconds}s is below the minimum transfer time of ${minTransfer}s",
            )
        }
        if (buffer < tightBuffer) {
            return Verdict(TransferConfidence.MEDIUM, "Tight transfer buffer of ${buffer.seconds}s")
        }
        if (buffer >= comfortableBuffer) {
            return Verdict(TransferConfidence.HIGH, "Comfortable transfer buffer of ${buffer.seconds}s")
        }
        return Verdict(TransferConfidence.MEDIUM, "Transfer buffer of ${buffer.seconds}s")
    }

    private fun isStale(update: TripUpdate, now: Instant): Boolean {
        val timestamp = update.timestamp ?: return true
        val age = Duration.between(timestamp, now)
        return age.isNegative || age > staleAfter
    }
}

/** Worst confidence across a journey's connections; journeys without transfers are `null`. */
fun worstTransferConfidence(connections: List<TransferConnection>): TransferConfidence? =
    connections.minByOrNull { it.confidence.ordinal }?.confidence

fun Leg.Transit.isRealtimeKnown(): Boolean = realtimeStatus != RealtimeStatus.NO_DATA