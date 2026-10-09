package com.intellitrip.weather

import com.intellitrip.domain.AlertSeverity
import com.intellitrip.domain.AlertUrgency
import com.intellitrip.domain.GeoPoint
import com.intellitrip.domain.RadarFrameKind
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

class WeatherProvidersTest {

    private val now = Instant.parse("2026-10-09T14:54:14Z")

    private val radarPayload = """
        {"version":"2.0","generated":1791557423,"host":"https://tilecache.rainviewer.com",
         "radar":{"past":[{"time":1791556800,"path":"/v2/radar/bfc337acb8b4"}],
                  "nowcast":[{"time":1791558000,"path":"/v2/radar/nowcast1"}]},
         "satellite":{"infrared":[]}}
    """.trimIndent()

    private val alertsPayload = """
        {"@context":{"@version":"1.1"},"type":"FeatureCollection","updated":"2026-10-09T14:54:14+00:00",
         "features":[
          {"id":"https://api.weather.gov/alerts/urn:oid:1","properties":{
             "id":"urn:oid:1","event":"Severe Thunderstorm Warning","severity":"Severe","urgency":"Immediate",
             "areaDesc":"Hennepin, Anoka","effective":"2026-10-09T14:00:00+00:00","expires":"2026-10-09T15:00:00+00:00",
             "headline":"Severe Thunderstorm Warning issued","description":"Take shelter."}}
         ]}
    """.trimIndent()

    private fun context(providerId: String, userAgent: String?) =
        WeatherRequestContext(providerId, userAgent, Clock.fixed(now, ZoneOffset.UTC))

    @Test
    fun radarIndexParsesPastAndNowcastFrames() {
        val index = RainViewerRadarProvider.parseIndex(radarPayload)
        assertNotNull(index)
        assertEquals(2, index.frames.size)
        assertEquals(RadarFrameKind.PAST, index.frames.first().kind)
        assertEquals(RadarFrameKind.NOWCAST, index.frames.last().kind)
        assertTrue(index.frames.first().tileUrlTemplate.startsWith("https://tilecache.rainviewer.com/v2/radar/"))
    }

    @Test
    fun radarIndexRejectsUnusablePayload() {
        assertNull(RainViewerRadarProvider.parseIndex("{\"version\":\"2.0\"}"))
    }

    @Test
    fun radarLatestFrameUsesFetchedFreshness() {
        runTest {
            val provider = RainViewerRadarProvider(
                http = { _, _ -> radarPayload.toByteArray() },
                context = context("rainviewer", "IntelliTrip/0.1"),
            )
            val result = provider.latestFrame()
            assertIs<com.intellitrip.contracts.ProviderResult.Success<RadarFrameAlias>>(result)
            assertEquals(now, result.freshness.fetchedAt)
            assertEquals(RadarFrameKind.NOWCAST, result.data.kind)
        }
    }

    @Test
    fun radarFramesFilterBySince() {
        runTest {
            val provider = RainViewerRadarProvider(
                http = { _, _ -> radarPayload.toByteArray() },
                context = context("rainviewer", "IntelliTrip/0.1"),
            )
            val result = provider.frames(Instant.ofEpochSecond(1791557400))
            assertIs<com.intellitrip.contracts.ProviderResult.Success<List<com.intellitrip.domain.RadarFrame>>>(result)
            assertEquals(1, result.data.size)
        }
    }

    @Test
    fun radarNetworkFailureIsReported() {
        runTest {
            val provider = RainViewerRadarProvider(
                http = { _, _ -> throw java.io.IOException("offline") },
                context = context("rainviewer", "IntelliTrip/0.1"),
            )
            assertIs<com.intellitrip.contracts.ProviderResult.NetworkFailure>(provider.latestFrame())
        }
    }

    @Test
    fun nwsRequiresIdentifyingUserAgent() {
        runTest {
            val provider = NwsWeatherAlertProvider(
                http = { _, _ -> alertsPayload.toByteArray() },
                context = context("nws", null),
            )
            val result = provider.alerts().first()
            assertIs<com.intellitrip.contracts.ProviderResult.AuthFailure>(result)
        }
    }

    @Test
    fun nwsAlertsAreMappedWithSeverityAndAreas() {
        runTest {
            var seenAgent: String? = null
            val provider = NwsWeatherAlertProvider(
                http = { _, headers ->
                    seenAgent = headers["User-Agent"]
                    alertsPayload.toByteArray()
                },
                context = context("nws", "IntelliTrip/0.1 (contact@example.com)"),
                pollInterval = java.time.Duration.ofMillis(1),
            )
            val result = provider.alerts().first()
            assertIs<com.intellitrip.contracts.ProviderResult.Success<List<com.intellitrip.domain.WeatherAlert>>>(result)
            val alert = result.data.single()
            assertEquals("Severe Thunderstorm Warning", alert.event)
            assertEquals(AlertSeverity.SEVERE, alert.severity)
            assertEquals(AlertUrgency.IMMEDIATE, alert.urgency)
            assertEquals(listOf("Hennepin", "Anoka"), alert.areas)
            assertEquals(now, result.freshness.sourceTimestamp)
            assertEquals("IntelliTrip/0.1 (contact@example.com)", seenAgent)
        }
    }

    @Test
    fun nwsAlertsReportNetworkFailure() {
        runTest {
            val provider = NwsWeatherAlertProvider(
                http = { _, _ -> throw java.io.IOException("offline") },
                context = context("nws", "IntelliTrip/0.1"),
                pollInterval = java.time.Duration.ofMillis(1),
            )
            assertIs<com.intellitrip.contracts.ProviderResult.NetworkFailure>(provider.alerts().first())
        }
    }

    @Test
    fun weatherLayersComposeBelowTransit() {
        val frame = RadarFrameAlias(
            providerId = "rainviewer",
            timestamp = now,
            tileUrlTemplate = "https://tiles/{z}/{x}/{y}.png",
            attribution = "RainViewer",
            kind = RadarFrameKind.PAST,
        )
        val alerts = listOf(
            com.intellitrip.domain.WeatherAlert(
                id = "urn:oid:1",
                providerId = "nws",
                event = "Winter Storm Warning",
                headline = "Winter storm",
                severity = AlertSeverity.SEVERE,
                urgency = AlertUrgency.EXPECTED,
                areas = listOf("Hennepin"),
                effective = now,
                expires = now,
                description = null,
            )
        )
        val composed = WeatherLayerComposer.compose(WeatherLayerState(radarEnabled = true, alertsEnabled = true), frame, alerts)
        assertEquals(listOf(LayerSlot.RADAR, LayerSlot.ALERTS), composed.layers.map { it.first })
        assertTrue(LayerSlot.RADAR.order < LayerSlot.TRANSIT_VEHICLES.order)
    }

    @Test
    fun weatherLayersStayOffByDefault() {
        val composed = WeatherLayerComposer.compose(WeatherLayerState(), null, emptyList())
        assertTrue(composed.layers.isEmpty())
    }

    @Test
    fun degradedWeatherKeepsTransitUsable() {
        assertTrue(WeatherLayerComposer.composeDegraded("radar unavailable").layers.isEmpty())
    }
}

private typealias RadarFrameAlias = com.intellitrip.domain.RadarFrame