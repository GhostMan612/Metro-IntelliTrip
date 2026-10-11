package com.intellitrip.ui

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import com.intellitrip.domain.Leg

private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())

/** Shared time formatting so every surface renders times identically. */
fun formatterTime(instant: Instant): String = TIME.format(instant)

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
