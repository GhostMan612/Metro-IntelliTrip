package com.intellitrip.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.intellitrip.AppState
import com.intellitrip.ui.theme.Radii
import com.intellitrip.ui.theme.Space
import com.intellitrip.ui.theme.status

/**
 * Saved stops and recent places.
 *
 * Fully usable with no account and no network, which is the point: a rider who has
 * saved their stop should see it on a plane, not a sign-in prompt.
 */
@Composable
fun SavedScreen(
    saved: List<SavedStop>,
    onOpenStop: (SavedStop) -> Unit,
    onRemoveStop: (SavedStop) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = Space.lg)) {
        Text(text = "Saved", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(Space.md))

        if (saved.isEmpty()) {
            EmptyPanel(
                title = "Nothing saved yet",
                body = "Tap a stop on the map, then choose \"Save this stop\", and it " +
                    "will appear here. Saved places work offline and need no account.",
                actionLabel = null,
                onAction = {},
            )
            return
        }

        LazyColumn(
            modifier = Modifier.heightIn(max = 520.dp),
            verticalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            items(saved, key = { it.key }) { stop ->
                Surface(
                    shape = RoundedCornerShape(Radii.card),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenStop(stop) }
                        .semantics {
                            contentDescription = "${stop.name}, saved stop. Double tap to open."
                        },
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = Space.lg, vertical = Space.md),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stop.name,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = "Saved ${stop.savedAt}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { onRemoveStop(stop) }) { Text("Remove") }
                    }
                }
            }
        }
    }
}

/**
 * Layers, weather, offline and preferences, with engine tooling nested underneath.
 *
 * Section 6.3 asks for engine administration to be separated from consumer tasks
 * rather than deleted, so the advanced controls are here, labelled as advanced, below
 * the things a rider actually reaches for.
 */
@Composable
fun MoreScreen(
    state: AppState,
    onToggleRadar: () -> Unit,
    onToggleAutoRefresh: () -> Unit,
    onLoadAlerts: () -> Unit,
    onLoadStatic: () -> Unit,
    onOpenScope: () -> Unit,
    onOpenData: () -> Unit,
    onLocateMe: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth().padding(horizontal = Space.lg),
        verticalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        item {
            Text(text = "More", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(Space.md))
        }

        item { SectionLabel("Map and data") }
        item {
            ToggleRow(
                title = "Precipitation radar",
                subtitle = "Observed radar frames from RainViewer",
                checked = state.radarEnabled,
                onCheckedChange = { onToggleRadar() },
            )
        }
        item {
            ToggleRow(
                title = "Auto refresh",
                subtitle = "Keep vehicle positions updating while the app is open",
                checked = state.autoRefresh,
                onCheckedChange = { onToggleAutoRefresh() },
            )
        }
        item { ActionRow("Weather alerts", "Severe weather near the map", onLoadAlerts) }
        item { ActionRow("Download schedules", "Offline timetable for planning", onLoadStatic) }
        item { ActionRow("Use my location", "Center on your position", onLocateMe) }

        item {
            Spacer(Modifier.height(Space.md))
            HorizontalDivider()
            Spacer(Modifier.height(Space.md))
        }

        item { SectionLabel("Advanced") }
        item {
            Text(
                text = "Engine controls. These change how the map renders and are not " +
                    "needed for everyday use.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item { ActionRow("Data and layers", "Raw counters and source detail", onOpenData) }
        item { ActionRow("Scope", "Render budgets by focus", onOpenScope) }

        item {
            Spacer(Modifier.height(Space.lg))
            Text(
                text = state.status,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.status.scheduled,
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = Space.sm, bottom = Space.xs),
    )
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 48dp floor so the switch is comfortably tappable, not just the row text.
            .heightIn(min = 56.dp)
            .padding(vertical = Space.xs)
            .semantics(mergeDescendants = true) {
                contentDescription = "$title. $subtitle"
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun ActionRow(title: String, subtitle: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(vertical = Space.sm)
            .semantics(mergeDescendants = true) {
                contentDescription = "$title. $subtitle"
            },
    ) {
        Text(text = title, style = MaterialTheme.typography.bodyLarge)
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}