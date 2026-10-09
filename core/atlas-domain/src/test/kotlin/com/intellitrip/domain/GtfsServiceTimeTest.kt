package com.intellitrip.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class GtfsServiceTimeTest {
    @Test
    fun parsesOrdinaryTimes() {
        assertEquals(84600, GtfsServiceTime.parse("23:30:00").secondsSinceServiceDayStart)
        assertEquals(3600, GtfsServiceTime.parse("1:00:00").secondsSinceServiceDayStart)
    }

    @Test
    fun preservesTimesBeyondMidnight() {
        assertEquals(87300, GtfsServiceTime.parse("24:15:00").secondsSinceServiceDayStart)
        assertEquals(91800, GtfsServiceTime.parse("25:30:00").secondsSinceServiceDayStart)
        assertEquals(93900, GtfsServiceTime.parse("26:05:00").secondsSinceServiceDayStart)
    }

    @Test
    fun doesNotWrapAtMidnight() {
        val t = GtfsServiceTime.parse("26:05:00")
        assertEquals(26, t.hours)
        assertEquals(93900, t.secondsSinceServiceDayStart)
    }

    @Test
    fun formatsDeterministically() {
        assertEquals("25:30:00", GtfsServiceTime.parse("25:30:00").format())
        assertEquals("1:05:00", GtfsServiceTime.parse("1:05:00").format())
    }

    @Test
    fun rejectsMalformedValues() {
        assertFailsWith<IllegalArgumentException> { GtfsServiceTime.parse("12:60:00") }
        assertFailsWith<IllegalArgumentException> { GtfsServiceTime.parse("12:00:60") }
        assertFailsWith<IllegalArgumentException> { GtfsServiceTime.parse("12:00") }
        assertFailsWith<IllegalArgumentException> { GtfsServiceTime.parse("ab:cd:ef") }
        assertFailsWith<IllegalArgumentException> { GtfsServiceTime.parse("12:0:00") }
    }

    @Test
    fun rejectsNegativeSeconds() {
        assertFailsWith<IllegalArgumentException> { GtfsServiceTime.ofSeconds(-1) }
    }

    @Test
    fun differsFromServiceDayStart() {
        assertNotEquals(GtfsServiceTime.ofSeconds(0), GtfsServiceTime.parse("24:00:00"))
    }
}