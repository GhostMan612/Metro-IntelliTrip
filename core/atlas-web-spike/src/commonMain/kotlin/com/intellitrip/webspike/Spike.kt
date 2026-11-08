package com.intellitrip.webspike

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone

/** Spike: proves common code compiles for JVM + Wasm without platform deps. */
expect fun platformNow(): Instant

fun describeNow(): String {
    val now = platformNow()
    return "Now=${now} zone=${TimeZone.UTC} date=${LocalDate(2026, 11, 8)}"
}
