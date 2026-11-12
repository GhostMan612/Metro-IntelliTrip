package com.intellitrip.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Badge
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import kotlinx.datetime.Clock
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.intellitrip.AppState
import com.intellitrip.ui.theme.Radii
import com.intellitrip.ui.theme.Space
import com.intellitrip.ui.theme.status

/**
 * The departure board from the wireframe in section 5.2.
 *
 * Only observed and scheduled times are ever shown. A row without a vehicle report
 * carries a "Schedule" badge, never a green Live dot, because the recovery plan is
 * explicit that a fabricated prediction is the single least acceptable thing this
 * surface could do.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NearbyDeparturesSheet(
    departures: List<Departure>,
    feedAgeLabel: String?,
    hasFeed: Boolean,
    onDownloadSchedules: () -> Unit,
    onDismiss: () -> Unit,
    onSelectDeparture: (Departure) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    // Ticker: recompute waits every 30s without refetching. Sheet dismissal
    // cancels the loop via composition disposal.
    var tick by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            tick += 30
        }
    }
    val display = remember(tick, departures) {
        departures.map {
            val mins = ((it.scheduledTime - Clock.System.now()).inWholeSeconds / 60).toInt()
            it.copy(minutesUntil = mins.coerceAtLeast(0))
        }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(modifier = Modifier.padding(horizontal = Space.lg)) {
            SheetHeaderRow(title = "Nearby departures")

            if (!hasFeed) {
                EmptyPanel(
                    title = "Download schedules to see departures",
                    body = "Departures come from the transit agency's published " +
                        "timetable. Nothing is estimated or invented on this screen.",
                    actionLabel = "Download schedules",
                    onAction = onDownloadSchedules,
                )
            } else if (display.isEmpty()) {
                EmptyPanel(
                    title = "No departures nearby",
                    body = "No scheduled service was found near the current map area. " +
                        "Pan the map or zoom out to include more stops.",
                    actionLabel = null,
                    onAction = {},
                )
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 380.dp),
                    verticalArrangement = Arrangement.spacedBy(Space.sm),
                ) {
                    items(display, key = { "${it.stopName}-${it.scheduledTime}-${it.routeShortName}" }) { d ->
                        DepartureRow(departure = d, onClick = { onSelectDeparture(d) })
                    }
                }
            }

            if (feedAgeLabel != null) {
                Text(
                    text = feedAgeLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.status.scheduled,
                    modifier = Modifier.padding(top = Space.md, bottom = Space.sm),
                )
            }
            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
        }
    }
}

@Composable
private fun SheetHeaderRow(title: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = Space.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = title, style = MaterialTheme.typography.titleLarge)
    }
}

/**
 * One departure.
 *
 * The hierarchy is the point: the wait is the largest number, the route and direction
 * sit under it, and provenance is a small badge. A rider reads the left column and
 * stops; a screen reader gets the same facts as one sentence from
 * [Departure.accessibilityLabel].
 */
@Composable
fun DepartureRow(
    departure: Departure,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            // 48dp minimum target, from the accessibility floor in section 6.1.
            .heightIn(min = 72.dp)
            .semantics { contentDescription = departure.accessibilityLabel() },
        shape = RoundedCornerShape(Radii.card),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Space.lg, vertical = Space.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.width(72.dp)) {
                Text(
                    text = departure.wait,
                    style = MaterialTheme.typography.displayMedium.copy(
                        fontFeatureSettings = "tnum",
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                ProvenanceBadge(
                    status = departure.status,
                    vehicleReporting = departure.vehicleReporting,
                )
            }

            Spacer(Modifier.width(Space.md))

            Column(modifier = Modifier.weight(1f)) {
                RoutePill(
                    shortName = departure.routeShortName,
                    longName = departure.routeLongName,
                    color = parseRouteColor(departure.routeColor),
                    headway = departure.headwayMinutes,
                )
                departure.headsign?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = departure.stopName +
                        (departure.distanceMeters?.let { " · ${it.toInt()} m" } ?: "") +
                        (departure.walkMinutes?.let { " · ${it} min walk" } ?: ""),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            RouteColorChip(departure.routeColor)
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RoutePill(shortName: String?, longName: String?, color: Color?, headway: Int?) {
    val label = shortName?.let { "Route $it" } ?: longName ?: "Bus"
    val suffix = headway?.let { " · every $it min" } ?: ""
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = color ?: MaterialTheme.colorScheme.primary,
        contentColor = Color.White,
        tonalElevation = 0.dp,
    ) {
        Text(
            text = label + suffix,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Live, Schedule, Canceled or Skipped.
 *
 * Green is reserved for a fresh stop-specific prediction. A vehicle that is
 * reporting but supplied no stop ETA stays "Schedule" with a small dot; the
 * timetable is still the only supported departure time.
 */
@Composable
private fun ProvenanceBadge(
    status: DepartureStatus,
    vehicleReporting: Boolean,
) {
    val colors = MaterialTheme.status
    val label = when (status) {
        DepartureStatus.LIVE -> "Live"
        DepartureStatus.CANCELED -> "Canceled"
        DepartureStatus.SKIPPED -> "Skipped"
        DepartureStatus.SCHEDULED -> "Schedule"
    }
    val tint = when (status) {
        DepartureStatus.LIVE -> colors.live
        DepartureStatus.CANCELED -> colors.critical
        DepartureStatus.SKIPPED -> colors.warning
        DepartureStatus.SCHEDULED -> colors.scheduled
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .clearAndSetSemantics { }
                .background(
                    when {
                        status == DepartureStatus.LIVE -> tint
                        vehicleReporting -> tint.copy(alpha = 0.55f)
                        else -> Color.Transparent
                    },
                    CircleShape,
                )
                .width(8.dp)
                .height(8.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = tint,
            modifier = Modifier.padding(start = Space.xs),
        )
    }
}

/**
 * Agency route color, always paired with the route number already in the row text.
 *
 * Color is never the only identifier, which is both an accessibility requirement and
 * a practical one: agency palettes collide.
 */
@Composable
private fun RouteColorChip(hex: String?) {
    val color = remember(hex) { parseRouteColor(hex) }
    if (color == null) return
    Box(
        modifier = Modifier
            .clearAndSetSemantics { }
            .width(6.dp)
            .height(40.dp)
            .background(color, RoundedCornerShape(3.dp)),
    )
}

private fun parseRouteColor(hex: String?): Color? {
    val cleaned = hex?.trim()?.removePrefix("#") ?: return null
    if (cleaned.length != 6) return null
    val value = cleaned.toLongOrNull(16) ?: return null
    return Color(0xFF000000 or value)
}

/** Shared empty/error panel so no screen invents its own wording. */
@Composable
fun EmptyPanel(
    title: String,
    body: String,
    actionLabel: String?,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = Space.lg),
        verticalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        Text(text = title, style = MaterialTheme.typography.titleMedium)
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (actionLabel != null) {
            TextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

/**
 * The single dominant entry action from the wireframe in section 5.2.
 *
 * One search field, not a row of choices. The plan calls this out specifically: a rider
 * arriving with one intent should not have to decide which mode they are in first.
 */
@Composable
fun DestinationSearchEntry(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    hint: String = "Where to?",
) {
    Surface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .semantics { contentDescription = "Search for a destination" },
        shape = RoundedCornerShape(Radii.chip),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Space.lg, vertical = Space.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = hint,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = Space.md),
            )
        }
    }
}