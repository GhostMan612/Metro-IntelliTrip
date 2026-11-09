package com.intellitrip.ui

import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import com.intellitrip.domain.Leg

/** Shared time formatting so every surface renders times identically. */
fun formatterTime(instant: Instant): String {
    val local = instant.toLocalDateTime(TimeZone.currentSystemDefault())
    val hour = local.hour.toString().padStart(2, '0')
    val minute = local.minute.toString().padStart(2, '0')
    return "$hour:$minute"
}

fun journeyHeadline(departure: Instant, arrival: Instant, minutes: Long): String =
    "${formatterTime(departure)} → ${formatterTime(arrival)} · ${minutes} min"

fun legSummary(leg: Leg): String = when (leg) {
    is Leg.WalkLeg -> "Walk ${leg.meters.toInt()} m"
    is Leg.Transit -> buildString {
        append("Route ${leg.routeKey.routeId}")
        leg.delaySeconds?.let { append(" (+${it}s)") }
        append(" ${formatterTime(leg.departure)}–${formatterTime(leg.arrival)}")
    }
    is Leg.Other -> "${leg.label} ${formatterTime(leg.departure)}"
}
