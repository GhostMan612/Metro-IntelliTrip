package com.intellitrip.domain

import kotlin.time.Duration
import kotlinx.datetime.Instant as KInstant
import kotlinx.datetime.LocalDate as KLocalDate
import kotlinx.datetime.TimeZone as KTimeZone

/**
 * M2 bridge: `java.time <-> kotlinx.datetime` conversions for the KMP pivot.
 *
 * Production code stays on `java.time` (Android + published SDK) for now.
 * New common code should write against `kotlinx.datetime` and convert at the
 * platform boundary with these helpers. See `docs/blueprints/KMP-01-migration.md`.
 */
object TimeBridge {

    fun javaInstantToKotlinx(java: java.time.Instant): KInstant =
        KInstant.fromEpochMilliseconds(java.toEpochMilli())

    fun kotlinxToJavaInstant(kotlinx: KInstant): java.time.Instant =
        java.time.Instant.ofEpochMilli(kotlinx.toEpochMilliseconds())

    fun javaLocalDateToKotlinx(java: java.time.LocalDate): KLocalDate =
        KLocalDate.parse(java.toString())

    fun kotlinxToJavaLocalDate(kotlinx: KLocalDate): java.time.LocalDate =
        java.time.LocalDate.parse(kotlinx.toString())

    fun javaDurationToKotlinx(java: java.time.Duration): Duration =
        Duration.parse("${java.toMillis()}ms")

    fun kotlinxToJavaDuration(kotlinx: Duration): java.time.Duration =
        java.time.Duration.ofMillis(kotlinx.inWholeMilliseconds.toLong())

    fun javaZoneToKotlinx(zone: java.time.ZoneId?): KTimeZone? =
        zone?.let { KTimeZone.of(it.id) }

    fun kotlinxZoneToJava(zone: KTimeZone?): java.time.ZoneId? =
        zone?.let {
            // Wasm TimeZone may be ID-based without IANA db; fall back to UTC.
            runCatching { java.time.ZoneId.of(it.id) }.getOrElse { java.time.ZoneOffset.UTC }
        }

    fun javaDayToKotlinx(day: java.time.DayOfWeek): kotlinx.datetime.DayOfWeek =
        kotlinx.datetime.DayOfWeek.entries[day.value - 1]
}
