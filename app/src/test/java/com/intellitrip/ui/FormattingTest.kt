package com.intellitrip.ui

import com.intellitrip.domain.GeoPoint
import com.intellitrip.domain.Leg
import kotlinx.datetime.Instant
import kotlin.time.Duration.Companion.seconds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the shared rider-facing formatting: HH:mm in the device zone,
 * headline with duration, and per-leg summaries. Follows DepartureBoard /
 * MapFeatureSheet expectations so surfaces cannot drift apart.
 */
class FormattingTest {

    private val t = Instant.parse("2026-10-05T12:07:00Z")

    @Test
    fun timeIsZeroPaddedHhMm() {
        val out = formatterTime(t)
        assertEquals(5, out.length)
        assertEquals(":", out[2].toString())
        // Hour/minute digits must be numeric after padding.
        assertTrue(out.substring(0, 2).all { it.isDigit() })
        assertTrue(out.substring(3, 5).all { it.isDigit() })
    }

    @Test
    fun headlineContainsTimesAndDuration() {
        val dep = Instant.parse("2026-10-05T12:00:00Z")
        val arr = Instant.parse("2026-10-05T12:30:00Z")
        val out = journeyHeadline(dep, arr, 30L)
        assertTrue(out.endsWith("· 30 min"))
        assertTrue(out.contains("→"))
    }

    @Test
    fun walkSummaryShowsMeters() {
        val leg = Leg.WalkLeg(
            from = GeoPoint(44.9, -93.2),
            to = GeoPoint(44.91, -93.21),
            start = t,
            duration = 300.seconds,
            meters = 412.7,
        )
        assertEquals("Walk 412 m", legSummary(leg))
    }

    @Test
    fun otherSummaryShowsLabel() {
        val out = legSummary(Leg.Other(
            from = GeoPoint(44.9, -93.2),
            to = GeoPoint(44.91, -93.21),
            departure = t,
            duration = 300.seconds,
            label = "Wait",
        ))
        assertTrue(out.startsWith("Wait "))
    }
}
