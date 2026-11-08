package com.intellitrip.domain

import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

class TimeBridgeTest {
    @Test
    fun instantRoundTrips() {
        val java = Instant.parse("2026-11-08T04:05:06Z")
        assertEquals(java, TimeBridge.kotlinxToJavaInstant(TimeBridge.javaInstantToKotlinx(java)))
    }

    @Test
    fun localDateRoundTrips() {
        val java = LocalDate.of(2026, 11, 8)
        assertEquals(java, TimeBridge.kotlinxToJavaLocalDate(TimeBridge.javaLocalDateToKotlinx(java)))
    }

    @Test
    fun durationRoundTrips() {
        val java = Duration.ofMinutes(42)
        assertEquals(42L, TimeBridge.javaDurationToKotlinx(java).inWholeMinutes)
        assertEquals(java, TimeBridge.kotlinxToJavaDuration(TimeBridge.javaDurationToKotlinx(java)))
    }

    @Test
    fun zoneAndDayRoundTrip() {
        val zone = ZoneId.of("America/Chicago")
        assertEquals(zone.id, TimeBridge.javaZoneToKotlinx(zone)?.id)
        assertEquals(DayOfWeek.SUNDAY, TimeBridge.kotlinxToJavaLocalDate(kotlinx.datetime.LocalDate(2026, 11, 8)).dayOfWeek)
    }
}
