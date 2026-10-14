package com.intellitrip.weather

import com.intellitrip.domain.GeoPoint
import com.intellitrip.domain.LatLngBounds
import com.intellitrip.domain.WeatherAlert
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A regional sweep is the only way to cover a viewport with this API, so the sampling
 * itself needs pinning: too coarse and a storm at the edge is missed, and a degenerate
 * viewport must not collapse to no requests at all.
 */
class NwsRegionalQueryTest {

    private val metro = LatLngBounds(
        southWest = GeoPoint(44.94, -93.30),
        northEast = GeoPoint(45.06, -93.16),
    )

    @Test
    fun aViewportProducesSeveralSamplePoints() {
        val points = NwsWeatherAlertProvider.samplePoints(metro)
        assertTrue("a single point cannot cover a viewport", points.size > 1)
        assertEquals(9, points.size)
    }

    @Test
    fun everySamplePointSitsInsideTheViewport() {
        NwsWeatherAlertProvider.samplePoints(metro).forEach { (lat, lon) ->
            assertTrue("lat $lat outside", lat in metro.southWest.lat..metro.northEast.lat)
            assertTrue("lon $lon outside", lon in metro.southWest.lon..metro.northEast.lon)
        }
    }

    @Test
    fun samplesSpanTheViewportSoAnAlertOnEitherSideIsCovered() {
        val points = NwsWeatherAlertProvider.samplePoints(metro)
        val lats = points.map { it.first }
        val lons = points.map { it.second }
        // A sample in the northern third and one in the southern third: this is what
        // catches a storm over the top of the map when the centre is clear.
        assertTrue("expected coverage north of centre", lats.any { it > 45.0 })
        assertTrue("expected coverage south of centre", lats.any { it < 45.0 })
        assertTrue("expected coverage east of centre", lons.any { it > -93.23 })
        assertTrue("expected coverage west of centre", lons.any { it < -93.23 })
    }

    @Test
    fun aDegenerateViewportStillYieldsOnePoint() {
        val point = LatLngBounds(GeoPoint(45.0, -93.2), GeoPoint(45.0, -93.2))
        val points = NwsWeatherAlertProvider.samplePoints(point)
        assertEquals("zero requests would mean zero alerts", 1, points.size)
        assertEquals(45.0, points.single().first, 1e-9)
        assertEquals(-93.2, points.single().second, 1e-9)
    }

    @Test
    fun aVeryThinViewportDoesNotCollapseToNothing() {
        val thin = LatLngBounds(GeoPoint(45.0, -93.2), GeoPoint(45.0001, -93.1999))
        assertTrue(NwsWeatherAlertProvider.samplePoints(thin).isNotEmpty())
    }

    @Test
    fun anAlertOverlappingTheViewportIsKept() {
        val alert = alertWithBounds(GeoPoint(44.95, -93.28), GeoPoint(45.05, -93.18))
        assertTrue(alert.mayAffect(metro))
    }

    @Test
    fun anAlertFarFromTheViewportIsDropped() {
        val alert = alertWithBounds(GeoPoint(40.0, -100.0), GeoPoint(41.0, -99.0))
        assertFalse(alert.mayAffect(metro))
    }

    @Test
    fun anAlertWithUnknownBoundsIsKeptRatherThanHidden() {
        val unknown = WeatherAlert(
            id = "u",
            providerId = "nws",
            event = "Warning",
            headline = "Warning",
            severity = com.intellitrip.domain.AlertSeverity.SEVERE,
            urgency = com.intellitrip.domain.AlertUrgency.IMMEDIATE,
            areas = emptyList(),
            effective = null,
            expires = null,
            description = null,
            areaBounds = null,
        )
        assertTrue(
            "hiding a severe warning because its geometry was unreadable is the worse failure",
            unknown.mayAffect(metro),
        )
    }

    @Test
    fun withoutAViewportEveryAlertIsKept() {
        val alert = alertWithBounds(GeoPoint(40.0, -100.0), GeoPoint(41.0, -99.0))
        assertTrue(alert.mayAffect(null))
    }

    @Test
    fun geometryIsParsedIntoAnAreaBox() {
        val payload = """
            {"@context":{"@version":"1.1"},"type":"FeatureCollection","updated":"2026-10-05T12:00:00+00:00",
             "features":[
              {"id":"urn:oid:1","geometry":{"type":"Polygon","coordinates":[[[-93.4,44.9],[-93.1,44.9],[-93.1,45.1],[-93.4,45.1],[-93.4,44.9]]]},
               "properties":{"id":"urn:oid:1","event":"Severe Thunderstorm Warning","severity":"Severe",
                 "urgency":"Immediate","areaDesc":"Hennepin","effective":"2026-10-05T12:00:00+00:00",
                 "expires":"2026-10-05T13:00:00+00:00","headline":"Storm","description":"Take shelter."}}]}
        """.trimIndent()

        val alerts = NwsWeatherAlertProvider.parseAlerts(payload)
        assertEquals(1, alerts.size)
        val bounds = alerts.single().areaBounds
        assertTrue("geometry must yield a box", bounds != null)
        assertEquals(44.9, bounds!!.southWest.lat, 1e-6)
        assertEquals(-93.4, bounds.southWest.lon, 1e-6)
        assertEquals(45.1, bounds.northEast.lat, 1e-6)
        assertEquals(-93.1, bounds.northEast.lon, 1e-6)
        assertTrue("it must be relevant to a metro-sized viewport", alerts.single().mayAffect(metro))
    }

    @Test
    fun aMultiPointGeometryStillYieldsABox() {
        val payload = """
            {"type":"FeatureCollection","features":[
              {"id":"m","geometry":{"type":"MultiPolygon","coordinates":[[[[-93.4,44.9],[-93.3,44.9],[-93.3,45.0],[-93.4,45.0]]],[[[-93.2,45.1],[-93.1,45.1],[-93.1,45.2],[-93.2,45.2]]]]},
               "properties":{"id":"m","event":"Warning","severity":"Severe","urgency":"Immediate"}}]}
        """.trimIndent()
        val bounds = NwsWeatherAlertProvider.parseAlerts(payload).single().areaBounds
        assertTrue(bounds != null)
        // Both polygons must contribute to the box, not just the first.
        assertEquals(-93.4, bounds!!.southWest.lon, 1e-6)
        assertEquals(-93.1, bounds.northEast.lon, 1e-6)
        assertEquals(44.9, bounds.southWest.lat, 1e-6)
        assertEquals(45.2, bounds.northEast.lat, 1e-6)
    }

    private fun alertWithBounds(
        southWest: GeoPoint,
        northEast: GeoPoint,
    ) = WeatherAlert(
        id = "a",
        providerId = "nws",
        event = "Warning",
        headline = "Warning",
        severity = com.intellitrip.domain.AlertSeverity.SEVERE,
        urgency = com.intellitrip.domain.AlertUrgency.IMMEDIATE,
        areas = emptyList(),
        effective = null,
        expires = null,
        description = null,
        areaBounds = LatLngBounds(southWest, northEast),
    )
}