package com.intellitrip.domain

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GeoMathTest {

    @Test
    fun distanceIsSymmetricAndZeroAtTheSamePoint() {
        val a = GeoPoint(44.9778, -93.2650)
        val b = GeoPoint(44.9580, -93.1560)
        assertEquals(0.0, GeoMath.distanceMeters(a, a), 0.001)
        assertEquals(GeoMath.distanceMeters(a, b), GeoMath.distanceMeters(b, a), 0.001)
        assertTrue(GeoMath.distanceMeters(a, b) in 8_000.0..10_000.0)
    }

    @Test
    fun walkingTimeRoundsUpSoARiderDoesNotMissABus() {
        assertEquals(0, GeoMath.walkMinutes(0.0))
        // 80 m is 59 s at 1.35 m/s: a claim of "1 min" would be optimistic.
        assertEquals(1, GeoMath.walkMinutes(80.0))
        // 90 s rounds to 2 minutes.
        assertEquals(2, GeoMath.walkMinutes(121.5))
        assertEquals(15, GeoMath.walkMinutes(1_215.0))
    }

    @Test
    fun oneDegreeOfLatitudeIsAboutOneHundredElevenKilometres() {
        val meters = GeoMath.distanceMeters(GeoPoint(44.0, -93.0), GeoPoint(45.0, -93.0))
        assertTrue(abs(meters - GeoMath.METERS_PER_LATITUDE_DEGREE) < 500.0)
    }

    @Test
    fun boundsCentreIsTheBoxMidpoint() {
        val bounds = LatLngBounds(GeoPoint(44.0, -94.0), GeoPoint(46.0, -92.0))
        assertEquals(GeoPoint(45.0, -93.0), bounds.center())
    }
}
