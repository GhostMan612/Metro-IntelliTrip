package com.intellitrip.domain

import java.util.Locale

/**
 * GTFS schedule time expressed as a nonnegative offset in seconds from the
 * start of the transit service day. GTFS permits hours above 24, so values
 * beyond 23:59:59 are valid and must never be wrapped at midnight.
 */
data class GtfsServiceTime(val secondsSinceServiceDayStart: Int) {

    init {
        require(secondsSinceServiceDayStart in 0..MAX_SECONDS) {
            "GTFS service time out of valid range: $secondsSinceServiceDayStart"
        }
    }

    val hours: Int get() = secondsSinceServiceDayStart / 3600
    val minutes: Int get() = (secondsSinceServiceDayStart % 3600) / 60
    val seconds: Int get() = secondsSinceServiceDayStart % 60

    fun format(): String = String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)

    override fun toString(): String = format()

    fun plusSeconds(delta: Int): GtfsServiceTime = ofSeconds(secondsSinceServiceDayStart + delta)

    companion object {
        const val MAX_SECONDS: Int = 99 * 3600 + 59 * 60 + 59

        fun ofSeconds(seconds: Int): GtfsServiceTime = GtfsServiceTime(seconds)

        /**
         * Parses strict GTFS `H:MM:SS` / `HH:MM:SS` values. GTFS schedule times
         * do not use `java.time.LocalTime` because hours may exceed 24.
         */
        fun parse(value: String): GtfsServiceTime {
            val trimmed = value.trim()
            val parts = trimmed.split(':')
            require(parts.size == 3) { "Malformed GTFS time: $value" }
            require(parts[0].isNotEmpty() && parts[0].all { it.isDigit() }) {
                "Malformed GTFS hour in: $value"
            }
            require(parts[1].length == 2 && parts[1].all { it.isDigit() }) {
                "Malformed GTFS minute in: $value"
            }
            require(parts[2].length == 2 && parts[2].all { it.isDigit() }) {
                "Malformed GTFS second in: $value"
            }
            val hours = parts[0].toInt()
            val minutes = parts[1].toInt()
            val seconds = parts[2].toInt()
            require(minutes in 0..59) { "Malformed GTFS minute in: $value" }
            require(seconds in 0..59) { "Malformed GTFS second in: $value" }
            return ofSeconds(hours * 3600 + minutes * 60 + seconds)
        }
    }
}