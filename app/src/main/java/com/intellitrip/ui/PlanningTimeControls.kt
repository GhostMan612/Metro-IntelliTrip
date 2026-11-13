package com.intellitrip.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.intellitrip.AppState
import com.intellitrip.PlanningTimeMode
import com.intellitrip.ui.theme.Space
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

/**
 * Depart-at / arrive-by controls shared by the plan sheet and the time sheet.
 *
 * The old "Now" chip held a private Compose boolean and never reached the
 * planner. This control edits the single typed planning request; every change
 * invalidates the previous journey list in the ViewModel.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlanningTimeControls(
    state: AppState,
    onMode: (PlanningTimeMode) -> Unit,
    onTime: (Instant?) -> Unit,
) {
    val zone = agencyZone(state)
    var showDate by remember { mutableStateOf(false) }
    var pendingDate by remember { mutableStateOf<LocalDate?>(null) }
    var showTime by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
        Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            PlanningTimeMode.entries.forEach { mode ->
                FilterChip(
                    selected = state.planningTimeMode == mode,
                    onClick = { onMode(mode) },
                    label = {
                        Text(
                            when (mode) {
                                PlanningTimeMode.DEPART_AT -> "Depart at"
                                PlanningTimeMode.ARRIVE_BY -> "Arrive by"
                            },
                        )
                    },
                )
            }
        }
        Surface(
            onClick = { showDate = true },
            shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(horizontal = Space.md, vertical = Space.sm)) {
                Text(
                    text = "When",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = planningTimeLabel(state, zone),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = "Tap to choose a date and time (${zone.id})",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
            if (state.planningTimeMode == PlanningTimeMode.DEPART_AT) {
                AssistChip(onClick = { onTime(null) }, label = { Text("Now") })
                AssistChip(
                    onClick = { onTime(Clock.System.now() + 15.minutes) },
                    label = { Text("+15 min") },
                )
                AssistChip(
                    onClick = { onTime(Clock.System.now() + 1.hours) },
                    label = { Text("+1 hour") },
                )
            } else {
                AssistChip(
                    onClick = { onTime(Clock.System.now() + 1.hours) },
                    label = { Text("In 1 hour") },
                )
                AssistChip(
                    onClick = { onTime(Clock.System.now() + 24.hours) },
                    label = { Text("Tomorrow") },
                )
            }
        }
        if (state.planningTimeMode == PlanningTimeMode.ARRIVE_BY) {
            Text(
                text = "The planner will look for a journey that arrives no later than this time.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (showDate) {
        val initial = state.planningTime ?: Clock.System.now()
        val initialDate = initial.toLocalDateTime(zone).date
        val dateState = rememberDatePickerState(
            initialSelectedDateMillis = initialDate.atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds(),
        )
        DatePickerDialog(
            onDismissRequest = { showDate = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDate = dateState.selectedDateMillis?.let {
                            Instant.fromEpochMilliseconds(it).toLocalDateTime(TimeZone.UTC).date
                        }
                        showDate = false
                        showTime = true
                    },
                ) { Text("Next") }
            },
            dismissButton = {
                TextButton(onClick = { showDate = false }) { Text("Cancel") }
            },
        ) {
            DatePicker(state = dateState)
        }
    }

    if (showTime) {
        val initial = state.planningTime ?: Clock.System.now()
        val initialLocal = initial.toLocalDateTime(zone)
        val initialTime = initialLocal.time
        val timeState = rememberTimePickerState(
            initialHour = initialTime.hour,
            initialMinute = initialTime.minute,
            is24Hour = false,
        )
        AlertDialog(
            onDismissRequest = { showTime = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        val date = pendingDate ?: initialLocal.date
                        val local = LocalDateTime(
                            date = date,
                            time = LocalTime(hour = timeState.hour, minute = timeState.minute),
                        )
                        onTime(local.toInstant(zone))
                        showTime = false
                        pendingDate = null
                    },
                ) { Text("Set time") }
            },
            dismissButton = {
                TextButton(onClick = { showTime = false }) { Text("Cancel") }
            },
            text = { TimePicker(state = timeState) },
        )
    }
}

fun planningTimeLabel(state: AppState, zone: TimeZone = agencyZone(state)): String {
    val instant = state.planningTime
    if (instant == null && state.planningTimeMode == PlanningTimeMode.DEPART_AT) return "Now"
    if (instant == null) return "Choose a time"
    return formatPlanningInstant(instant, zone)
}

/** Compact chip text: "Now", "8:15 AM", or "By 8:15 AM" with a date when needed. */
fun planningTimeShortLabel(state: AppState, zone: TimeZone = agencyZone(state)): String {
    val instant = state.planningTime
    if (instant == null) {
        return if (state.planningTimeMode == PlanningTimeMode.ARRIVE_BY) "Choose time" else "Now"
    }
    val local = instant.toLocalDateTime(zone)
    val today = Clock.System.now().toLocalDateTime(zone).date
    val hour = when {
        local.hour == 0 -> 12
        local.hour > 12 -> local.hour - 12
        else -> local.hour
    }
    val minute = local.minute.toString().padStart(2, '0')
    val suffix = if (local.hour < 12) "AM" else "PM"
    val clock = "$hour:$minute $suffix"
    val date = if (local.date == today) {
        ""
    } else {
        local.month.name.lowercase().replaceFirstChar { it.uppercase() }.take(3) +
            " ${local.dayOfMonth} · "
    }
    val prefix = if (state.planningTimeMode == PlanningTimeMode.ARRIVE_BY) "By " else ""
    return "$prefix$date$clock"
}

fun agencyZone(state: AppState): TimeZone =
    state.staticFeed?.agencies?.firstOrNull { it.timezone != null }?.timezone
        ?: TimeZone.currentSystemDefault()

fun formatPlanningInstant(instant: Instant, zone: TimeZone): String {
    val local = instant.toLocalDateTime(zone)
    val hour = when {
        local.hour == 0 -> 12
        local.hour > 12 -> local.hour - 12
        else -> local.hour
    }
    val minute = local.minute.toString().padStart(2, '0')
    val suffix = if (local.hour < 12) "AM" else "PM"
    val month = local.month.name.lowercase().replaceFirstChar { it.uppercase() }.take(3)
    val dayOfWeek = local.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }.take(3)
    return "$dayOfWeek, $month ${local.dayOfMonth} · $hour:$minute $suffix (${zone.id})"
}
