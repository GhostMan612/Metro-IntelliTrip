package com.intellitrip.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.intellitrip.AppState
import com.intellitrip.domain.GeoPoint
import com.intellitrip.domain.JourneyOption
import com.intellitrip.domain.Leg
import com.intellitrip.domain.TransferConfidence
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun TripPlannerPanel(
    state: AppState,
    onOrigin: (GeoPoint) -> Unit,
    onDestination: (GeoPoint) -> Unit,
    onPlan: () -> Unit,
) {
    Column(modifier = Modifier.padding(top = 8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { onOrigin(GeoPoint(DEFAULT_LAT_ORIGIN(), DEFAULT_LON_ORIGIN())) },
            ) { Text("Set origin") }
            Button(
                onClick = { onDestination(GeoPoint(DEFAULT_LAT_DEST(), DEFAULT_LON_DEST())) },
            ) { Text("Set destination") }
        }
        Button(
            onClick = onPlan,
            enabled = !state.planning && state.staticFeed != null,
            modifier = Modifier.padding(top = 8.dp),
        ) { Text(if (state.planning) "Planning…" else "Plan trip") }

        if (state.staticFeed == null) {
            Text(
                text = "Load the static network first to plan trips.",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        state.journeys.take(5).forEach { journey ->
            JourneyCard(journey)
        }

        if (state.journeyWarnings.isNotEmpty()) {
            Text(
                text = "Transfer warnings:",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 8.dp),
            )
            state.journeyWarnings.forEach { warning ->
                Text(text = "• $warning", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun JourneyCard(journey: JourneyOption) {
    val formatter = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
    Card(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "${formatter.format(journey.departure)} → ${formatter.format(journey.arrival)} " +
                    "(${journey.duration.toMinutes()} min)",
                style = MaterialTheme.typography.titleSmall,
            )
            journey.legs.forEach { leg ->
                Text(
                    text = describeLeg(leg),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            if (journey.transfers.isNotEmpty()) {
                journey.transfers.forEach { transfer ->
                    Row(modifier = Modifier.padding(top = 4.dp)) {
                        Text(
                            text = "Transfer: ${transfer.confidence.label()} · " +
                                "buffer ${(transfer.predictedBuffer ?: transfer.scheduledBuffer).toMinutes()} min",
                            style = MaterialTheme.typography.labelMedium,
                            color = transfer.confidence.color(),
                        )
                    }
                    transfer.rationale?.let {
                        Text(text = "  $it", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

private fun describeLeg(leg: Leg): String = when (leg) {
    is Leg.WalkLeg -> "Walk ${leg.meters.toInt()} m"
    is Leg.Transit -> buildString {
        append("Route ${leg.routeKey.routeId}")
        leg.realtimeStatus.takeIf { it != com.intellitrip.domain.RealtimeStatus.NO_DATA }?.let {
            append(" · ${it.name.lowercase()}")
        }
        leg.delaySeconds?.let { append(" (+${it}s)") }
        append(" ${formatterTime(leg.departure)}–${formatterTime(leg.arrival)}")
    }
}

private fun formatterTime(instant: java.time.Instant): String =
    DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault()).format(instant)

private fun TransferConfidence.label(): String = when (this) {
    TransferConfidence.HIGH -> "high"
    TransferConfidence.MEDIUM -> "medium"
    TransferConfidence.LOW -> "low"
    TransferConfidence.SCHEDULED_UNKNOWN -> "scheduled only"
    TransferConfidence.UNKNOWN -> "unknown"
}

@Composable
private fun TransferConfidence.color(): Color = when (this) {
    TransferConfidence.HIGH -> Color(0xFF15803D)
    TransferConfidence.MEDIUM -> Color(0xFFB45309)
    TransferConfidence.LOW -> Color(0xFFB91C1C)
    else -> Color(0xFF6B7280)
}

private fun DEFAULT_LAT_ORIGIN() = com.intellitrip.DEFAULT_LAT
private fun DEFAULT_LON_ORIGIN() = com.intellitrip.DEFAULT_LON
private fun DEFAULT_LAT_DEST() = com.intellitrip.DEFAULT_DEST_LAT
private fun DEFAULT_LON_DEST() = com.intellitrip.DEFAULT_DEST_LON