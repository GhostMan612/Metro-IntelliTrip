package com.intellitrip.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.intellitrip.domain.JourneyOption
import com.intellitrip.domain.Stop
import com.intellitrip.domain.Vehicle
import com.intellitrip.map.MapFeature
import com.intellitrip.map.MapSelection
import com.intellitrip.ui.theme.Radii
import com.intellitrip.ui.theme.Space
import com.intellitrip.ui.theme.status
import java.time.Duration
import java.time.Instant

/**
 * Detail for whatever the rider tapped on the map.
 *
 * The audit's flow B is "pan or locate, pick a stop, read departures, save the stop".
 * Every branch here has to be answerable without the map, because the accessibility
 * requirement is that map-only interaction always has a list equivalent.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapFeatureSheet(
    selection: MapSelection,
    stops: Map<String, Stop>,
    vehicles: Map<String, Vehicle>,
    journeys: List<JourneyOption>,
    departures: List<Departure>,
    now: Instant,
    onDismiss: () -> Unit,
    onSaveStop: (Stop) -> Unit,
    onSelectJourney: (String) -> Unit,
    onZoomToCluster: (MapFeature.VehicleCluster) -> Unit,
    onChooseCandidate: (MapFeature) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(modifier = Modifier.padding(horizontal = Space.lg)) {
            when (selection) {
                is MapSelection.Single -> SingleFeatureBody(
                    selection.feature, stops, vehicles, journeys, departures, now,
                    onSaveStop, onSelectJourney, onZoomToCluster,
                )

                is MapSelection.Ambiguous -> AmbiguousBody(
                    features = selection.features,
                    onChoose = onChooseCandidate,
                    onDismiss = onDismiss,
                )

                MapSelection.None -> EmptyPanel(
                    title = "Nothing selected",
                    body = "Tap a stop, vehicle or route on the map to see details here.",
                    actionLabel = null,
                    onAction = {},
                )
            }
        }
    }
}

@Composable
private fun SingleFeatureBody(
    feature: MapFeature,
    stops: Map<String, Stop>,
    vehicles: Map<String, Vehicle>,
    journeys: List<JourneyOption>,
    departures: List<Departure>,
    now: Instant,
    onSaveStop: (Stop) -> Unit,
    onSelectJourney: (String) -> Unit,
    onZoomToCluster: (MapFeature.VehicleCluster) -> Unit,
) {
    when (feature) {
        is MapFeature.Stop -> {
            val stop = stops[feature.key]
            StopDetailBody(
                stop = stop,
                fallbackName = feature.name,
                departures = departures.filter { it.stopName == (stop?.name ?: feature.name) },
                now = now,
                onSaveStop = { stop?.let(onSaveStop) },
            )
        }

        is MapFeature.Vehicle -> {
            val vehicle = vehicles[feature.vehicleId]
            VehicleDetailBody(vehicle = vehicle, fallback = feature, now = now)
        }

        is MapFeature.Journey -> {
            val journey = journeys.firstOrNull { it.id == feature.journeyId }
            JourneyDetailBody(
                journey = journey,
                onShowOnMap = { journey?.let { onSelectJourney(it.id) } },
            )
        }

        is MapFeature.VehicleCluster -> EmptyPanel(
            title = "${feature.vehicleCount} vehicles here",
            body = "These vehicles are grouped so the map stays readable at this zoom. " +
                "Zoom in to pick out an individual vehicle.",
            actionLabel = "Zoom in",
            onAction = { onZoomToCluster(feature) },
        )
    }
}

/**
 * Stop detail: the first half of rider flow B.
 *
 * Shows departures when they are known and says so plainly when they are not, rather
 * than rendering an empty list that reads as "this stop has no service".
 */
@Composable
private fun StopDetailBody(
    stop: Stop?,
    fallbackName: String,
    departures: List<Departure>,
    now: Instant,
    onSaveStop: () -> Unit,
) {
    Text(text = stop?.name ?: fallbackName, style = MaterialTheme.typography.headlineMedium)
    if (departures.isEmpty()) {
        EmptyPanel(
            title = "No upcoming departures",
            body = "Departures appear once the schedule for this area is downloaded.",
            actionLabel = null,
            onAction = {},
        )
    } else {
        LazyColumn(
            modifier = Modifier.heightIn(max = 360.dp),
            verticalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            items(departures, key = { "${it.routeShortName}-${it.scheduledTime}" }) { d ->
                Surface(
                    shape = RoundedCornerShape(Radii.chip),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = Space.md, vertical = Space.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = d.routeShortName ?: d.routeLongName ?: "Bus",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.width(56.dp),
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = d.headsign?.takeIf { it.isNotBlank() } ?: d.stopName,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = if (d.isLive) "Live" else "Schedule",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (d.isLive) MaterialTheme.status.live else MaterialTheme.status.scheduled,
                            )
                        }
                        Text(text = d.wait, style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
    }
    Spacer(Modifier.padding(top = Space.sm))
    TextButton(onClick = onSaveStop, enabled = stop != null) { Text("Save this stop") }
}

/**
 * Vehicle detail.
 *
 * States plainly how old the position is. A vehicle with no recent report is shown
 * rather than hidden, because "the bus stopped reporting" and "there is no bus" are
 * very different situations for someone waiting.
 */
@Composable
private fun VehicleDetailBody(
    vehicle: Vehicle?,
    fallback: MapFeature.Vehicle,
    now: Instant,
) {
    val route = vehicle?.routeKey?.routeId ?: fallback.routeShortName
    val headsign = vehicle?.tripKey?.let { _ -> fallback.headsign }
    Text(
        text = route?.let { "Route $it" } ?: "Vehicle",
        style = MaterialTheme.typography.headlineMedium,
    )
    headsign?.takeIf { it.isNotBlank() }?.let {
        Text(text = it, style = MaterialTheme.typography.titleMedium)
    }

    val updatedAt = vehicle?.updatedAt ?: fallback.updatedAt
    val ageSeconds = Duration.between(updatedAt, now).seconds.coerceAtLeast(0)
    val (label, color) = when {
        ageSeconds > 300 -> "Last reported ${describeAge(ageSeconds)}" to MaterialTheme.status.warning
        ageSeconds > 120 -> "Updated ${describeAge(ageSeconds)}" to MaterialTheme.status.warning
        else -> "Live position" to MaterialTheme.status.live
    }
    Text(text = label, style = MaterialTheme.typography.bodyMedium, color = color)
}

private fun describeAge(seconds: Long): String = when {
    seconds > 86_400 -> "${seconds / 86_400}d ago"
    seconds > 3_600 -> "${seconds / 3_600}h ago"
    else -> "${(seconds / 60).coerceAtLeast(1)} min ago"
}

/**
 * One itinerary line, in rider language.
 *
 * A walk is minutes and a distance, because that is all a rider needs to decide
 * whether to take it. A transit leg names the route and the stops, and states whether
 * it ran on time, delayed, or with no realtime data at all. Nothing here invents an
 * arrival: when the feed sent no prediction, the scheduled time is labelled as such.
 */
private fun describeLeg(leg: com.intellitrip.domain.Leg): String = when (leg) {
    is com.intellitrip.domain.Leg.WalkLeg ->
        "Walk ${leg.duration.toMinutes()} min (${leg.meters.toInt()} m)"

    is com.intellitrip.domain.Leg.Transit -> {
        val route = leg.routeKey.routeId
        val status = when (leg.realtimeStatus) {
            com.intellitrip.domain.RealtimeStatus.ON_TIME -> "on time"
            com.intellitrip.domain.RealtimeStatus.DELAYED -> "delayed ${leg.delaySecondsOrZero()}s"
            com.intellitrip.domain.RealtimeStatus.EARLY -> "running early"
            com.intellitrip.domain.RealtimeStatus.CANCELED -> "canceled"
            com.intellitrip.domain.RealtimeStatus.NO_DATA -> "scheduled, no live data"
            com.intellitrip.domain.RealtimeStatus.SCHEDULED -> "scheduled"
        }
        // A canceled leg is stated rather than hidden: someone already on their way to
        // this platform needs to know now, not by discovering the service never runs.
        "Route $route, ${leg.fromStopKey.stopId} to ${leg.toStopKey.stopId}, $status"
    }

    is com.intellitrip.domain.Leg.Other -> leg.label
}

/** Trip detail: the second half of rider flow C. */
@Composable
private fun JourneyDetailBody(journey: JourneyOption?, onShowOnMap: () -> Unit) {
    Text(
        text = journey?.let { "${it.duration.toMinutes()} min trip" } ?: "Trip",
        style = MaterialTheme.typography.headlineMedium,
    )
    if (journey == null) {
        EmptyPanel(
            title = "This route is no longer available",
            body = "It belonged to an earlier plan. Run the plan again to see current options.",
            actionLabel = null,
            onAction = {},
        )
        return
    }

    LazyColumn(
        modifier = Modifier.heightIn(max = 320.dp),
        verticalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        items(journey.legs, key = { it.hashCode().toString() }) { leg ->
            Text(text = describeLeg(leg), style = MaterialTheme.typography.bodyLarge)
        }
    }
    Spacer(Modifier.padding(top = Space.sm))
    TextButton(onClick = onShowOnMap) { Text("Show on map") }
}

/**
 * Several features under one tap.
 *
 * Offered as a list rather than resolved to a guess. A dense downtown routinely puts a
 * bus and a stop on the same pixel, and silently picking one of them is how a rider
 * ends up reading the wrong departure.
 *
 * Every candidate is selectable and opens its own detail. A list the rider can read but
 * not act on would be worse than guessing: it shows the right information and then
 * refuses to act on it.
 */
@Composable
private fun AmbiguousBody(
    features: List<MapFeature>,
    onChoose: (MapFeature) -> Unit,
    onDismiss: () -> Unit,
) {
    Text(text = "Several things here", style = MaterialTheme.typography.titleLarge)
    LazyColumn(
        modifier = Modifier.heightIn(max = 320.dp),
        verticalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        items(features, key = { it.id }) { feature ->
            val label = when (feature) {
                is MapFeature.Stop -> feature.name
                is MapFeature.Vehicle -> feature.routeShortName?.let { "Route $it vehicle" } ?: "Vehicle"
                is MapFeature.Journey -> "Planned trip"
                is MapFeature.VehicleCluster -> "${feature.vehicleCount} vehicles here"
            }
            Surface(
                onClick = { onChoose(feature) },
                shape = RoundedCornerShape(Radii.chip),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "$label. Double tap to open details." },
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(horizontal = Space.md, vertical = Space.md),
                )
            }
        }
    }
    Spacer(Modifier.padding(top = Space.sm))
    TextButton(onClick = onDismiss) { Text("Close") }
}