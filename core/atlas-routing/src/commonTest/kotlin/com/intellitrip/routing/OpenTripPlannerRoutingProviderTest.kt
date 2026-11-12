package com.intellitrip.routing

import com.intellitrip.domain.FeedId
import com.intellitrip.domain.GeoPoint
import com.intellitrip.domain.JourneyOption
import com.intellitrip.domain.Leg
import com.intellitrip.domain.RouteKey
import com.intellitrip.domain.StopKey
import com.intellitrip.domain.TripKey
import com.intellitrip.domain.TripPlanRequest
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers the OTP response mapper against a realistic plan payload.
 *
 * The hand-rolled JSON reader is exercised here too; it exists because
 * `atlas-routing` is a published SDK module and a serialization dependency would
 * leak to every consumer.
 */
class OpenTripPlannerRoutingProviderTest {

    private val otpFeedId = FeedId("metro-transit-regional")

    private val otpRequest = TripPlanRequest(
        origin = GeoPoint(44.9765, -93.2650),
        destination = GeoPoint(44.9580, -93.1560),
        departAt = null,
        arriveBy = null,
        maxTransfers = 1,
    )

    /** Trimmed but structurally faithful OTP 2.x plan response. */
    private val planBody = """
        {
          "plan": {
            "itineraries": [
              {
                "id": "it-1",
                "startTime": 1797090000000,
                "endTime": 1797091800000,
                "legs": [
                  {
                    "startTime": 1797090000000,
                    "endTime": 1797090060000,
                    "distance": {"value": 220.5},
                    "transitLeg": false,
                    "mode": "WALK",
                    "from": {"stopId": "S-ORIGIN", "name": "Origin", "lat": 44.9765, "lon": -93.2650},
                    "to": {"stopId": "S-A", "name": "Stop A", "lat": 44.9780, "lon": -93.2620}
                  },
                  {
                    "startTime": 1797090120000,
                    "endTime": 1797091500000,
                    "transitLeg": true,
                    "mode": "BUS",
                    "tripId": "T-900",
                    "from": {"stopId": "S-A", "name": "Stop A", "lat": 44.9780, "lon": -93.2620},
                    "to": {"stopId": "S-B", "name": "Stop B", "lat": 44.9900, "lon": -93.2000},
                    "route": {"shortName": "9", "longName": "Route 9", "agencyName": "Metro Transit"}
                  },
                  {
                    "startTime": 1797091560000,
                    "endTime": 1797091800000,
                    "distance": {"value": 140.0},
                    "transitLeg": false,
                    "mode": "WALK",
                    "from": {"stopId": "S-B", "name": "Stop B", "lat": 44.9900, "lon": -93.2000},
                    "to": {"stopId": "S-DEST", "name": "Destination", "lat": 44.9910, "lon": -93.1990}
                  }
                ]
              }
            ]
          }
        }
    """.trimIndent()

    private fun parse(body: String = planBody) =
        OpenTripPlannerRoutingProvider.parsePlan(body, otpRequest, otpFeedId)

    @Test
    fun mapsItinerariesLegsAndTimes() {
        val journey = successJourneys().single()
        assertEquals("it-1", journey.id)
        assertEquals(3, journey.legs.size)

        val walk = journey.legs[0]
        assertTrue(walk is Leg.WalkLeg, "first leg should be a walk, was ${walk::class.simpleName}")
        assertEquals(220.5, walk.meters, 0.01)
        assertEquals(GeoPoint(44.9765, -93.2650), walk.from)
        assertEquals(GeoPoint(44.9780, -93.2620), walk.to)

        val transit = journey.legs[1]
        assertTrue(transit is Leg.Transit, "second leg should be transit, was ${transit::class.simpleName}")
        assertEquals("9", transit.routeKey.routeId)
        assertEquals("T-900", transit.tripKey.tripId)
        // Stop ids must be feed-scoped, not raw.
        assertEquals(otpFeedId, transit.fromStopKey.feedId)
        assertEquals(otpFeedId, transit.toStopKey.feedId)
        assertEquals("S-A", transit.fromStopKey.stopId)
    }

    @Test
    fun mapsEpochMillisToInstants() {
        val journey = successJourneys().single()
        assertEquals(1797090000000L, journey.departure.toEpochMilliseconds())
        assertEquals(1797091800000L, journey.arrival.toEpochMilliseconds())
    }

    /** Narrows the sealed result so the test asserts on real data, not on types. */
    private fun successJourneys(body: String = planBody): List<JourneyOption> {
        val result = parse(body)
        assertTrue(
            result is com.intellitrip.contracts.ProviderResult.Success,
            "expected Success, got ${result::class.simpleName}",
        )
        return result.data
    }

    @Test
    fun departureUrlFormatsCalendarDateAndTime() {
        val provider = OpenTripPlannerRoutingProvider(
            baseUrl = "https://otp.example.org/",
            feedId = otpFeedId,
            transport = { "" },
        )
        val url = provider.planUrl(
            otpRequest.copy(departAt = Instant.parse("2026-11-12T14:30:00Z")),
        )
        // 14:30 UTC is 08:30 in America/Chicago (CST, UTC-6).
        assertTrue(url.contains("date=20261112"), url)
        assertTrue(url.contains("time=0830"), url)
    }

    @Test
    fun twoTransitLegsProduceTransferConnection() {
        val body = """
            {"plan":{"itineraries":[{
              "id":"it-2","startTime":1797090000000,"endTime":1797093600000,
              "legs":[
                {"startTime":1797090000000,"endTime":1797090600000,"transitLeg":true,"mode":"BUS","tripId":"T-1",
                 "from":{"stopId":"A","lat":44.97,"lon":-93.26},"to":{"stopId":"B","lat":44.98,"lon":-93.26},
                 "route":{"shortName":"1"}},
                {"startTime":1797090900000,"endTime":1797091800000,"transitLeg":true,"mode":"BUS","tripId":"T-2",
                 "from":{"stopId":"B","lat":44.98,"lon":-93.26},"to":{"stopId":"C","lat":44.99,"lon":-93.26},
                 "route":{"shortName":"2"}},
                {"startTime":1797091800000,"endTime":1797093600000,"transitLeg":false,"mode":"WALK",
                 "from":{"stopId":"C","lat":44.99,"lon":-93.26},"to":{"stopId":"D","lat":44.995,"lon":-93.26}}
              ]}]}}
        """.trimIndent()
        val journey = successJourneys(body).single()
        assertEquals(1, journey.transferCount)
        assertEquals(300L, journey.transfers.single().scheduledBuffer.inWholeSeconds)
    }

    @Test
    fun departureUrlCarriesDateAndTime() {
        val provider = OpenTripPlannerRoutingProvider(
            baseUrl = "https://otp.example.org/",
            feedId = otpFeedId,
            transport = { "" },
        )
        val url = provider.planUrl(
            otpRequest.copy(departAt = Instant.parse("2026-11-12T14:30:00Z")),
        )
        assertTrue(url.startsWith("https://otp.example.org/otp/routers/default/plan?"), url)
        assertTrue(url.contains("date="), url)
        assertTrue(url.contains("time="), url)
        assertTrue(url.contains("numItineraries=5"), url)
        assertTrue(url.contains("arriveBy=false"), url)
    }

    @Test
    fun nonJsonBodyIsReportedAsMalformed() {
        val result = parse("<html>502 Bad Gateway</html>")
        assertTrue(
            result is com.intellitrip.contracts.ProviderResult.MalformedResponse,
            "expected MalformedResponse, got ${result::class.simpleName}",
        )
    }

    @Test
    fun missingPlanEnvelopeIsMalformed() {
        val result = parse("""{"error":"graph not built"}""")
        assertTrue(result is com.intellitrip.contracts.ProviderResult.MalformedResponse)
    }

    @Test
    fun emptyItineraryListIsASuccessfulEmptyResult() {
        val result = parse("""{"plan":{"itineraries":[]}}""")
        assertTrue(result is com.intellitrip.contracts.ProviderResult.Success)
        assertTrue(result.data.isEmpty())
    }

    @Test
    fun legsMissingCoordinatesAreDroppedRatherThanGuessed() {
        val body = """
            {"plan":{"itineraries":[{
              "id":"it-x","startTime":1797090000000,"endTime":1797091800000,
              "legs":[
                {"startTime":1797090120000,"endTime":1797091500000,"transitLeg":true,
                 "mode":"BUS","tripId":"T-1",
                 "from":{"lat":44.9},
                 "to":{"stopId":"S-Z","lat":44.99,"lon":-93.2},
                 "route":{"shortName":"9"}}
              ]}]}}
        """.trimIndent()
        // The transit leg has no `to` lon, so it cannot be represented; the
        // itinerary is dropped rather than emitted with fabricated coordinates.
        assertTrue(successJourneys(body).isEmpty())
    }

    @Test
    fun networkResolverMapsOtpIdsToLocalRouteStopAndTripKeys() {
        val body = """
            {"plan":{"itineraries":[{
              "id":"it-local","startTime":1797090000000,"endTime":1797091800000,
              "legs":[
                {"startTime":1797090000000,"endTime":1797090060000,"distance":{"value":220.5},
                 "transitLeg":false,"mode":"WALK",
                 "from":{"stopId":"S1","name":"First","lat":44.9778,"lon":-93.2650},
                 "to":{"stopId":"S2","name":"Second","lat":44.9860,"lon":-93.2650}},
                {"startTime":1797090120000,"endTime":1797091500000,"transitLeg":true,"mode":"BUS",
                 "tripId":"T1",
                 "from":{"stopId":"S2","name":"Second","lat":44.9860,"lon":-93.2650},
                 "to":{"stopId":"S3","name":"Third","lat":44.9950,"lon":-93.2650},
                 "route":{"id":"901","shortName":"A","longName":"Blue Line"}}
              ]}]}}
        """.trimIndent()
        val result = OpenTripPlannerRoutingProvider.parsePlan(
            body = body,
            request = otpRequest,
            feedId = otpFeedId,
            resolver = OtpIdentityResolver(RoutingFixtures.network),
        )
        assertTrue(result is com.intellitrip.contracts.ProviderResult.Success, "${result::class.simpleName}")
        val transit = result.data.single().legs.filterIsInstance<Leg.Transit>().single()
        assertEquals(RouteKey(otpFeedId, "901"), transit.routeKey)
        assertEquals(TripKey(otpFeedId, "T1"), transit.tripKey)
        assertEquals(StopKey(otpFeedId, "S2"), transit.fromStopKey)
        assertEquals(StopKey(otpFeedId, "S3"), transit.toStopKey)
    }

    @Test
    fun unresolvedOtpIdsStayFeedScopedRatherThanUsingDisplayNames() {
        val body = """
            {"plan":{"itineraries":[{
              "id":"it-remote","startTime":1797090000000,"endTime":1797091800000,
              "legs":[
                {"startTime":1797090120000,"endTime":1797091500000,"transitLeg":true,"mode":"BUS",
                 "tripId":"remote-trip",
                 "from":{"stopId":"remote-a","name":"Remote A","lat":44.9,"lon":-93.2},
                 "to":{"stopId":"remote-b","name":"Remote B","lat":44.99,"lon":-93.2},
                 "route":{"id":"remote-route","shortName":"ZZ","longName":"Unknown"}}
              ]}]}}
        """.trimIndent()
        val result = OpenTripPlannerRoutingProvider.parsePlan(
            body = body,
            request = otpRequest,
            feedId = otpFeedId,
            resolver = OtpIdentityResolver(RoutingFixtures.network),
        )
        assertTrue(result is com.intellitrip.contracts.ProviderResult.Success, "${result::class.simpleName}")
        val transit = result.data.single().legs.filterIsInstance<Leg.Transit>().single()
        assertEquals(RouteKey(otpFeedId, "ZZ"), transit.routeKey)
        assertEquals(TripKey(otpFeedId, "remote-trip"), transit.tripKey)
        assertEquals(StopKey(otpFeedId, "remote-a"), transit.fromStopKey)
        assertEquals(StopKey(otpFeedId, "remote-b"), transit.toStopKey)
    }

    @Test
    fun jsonReaderHandlesEscapesAndNesting() {
        val json = JsonValue.parse(
            """{"a":"line\nbreak \"quoted\"","b":[1,2.5,-3e2],"c":{"d":true,"e":null},"f":"é"}""",
        )
        assertEquals("line\nbreak \"quoted\"", json.string("a"))
        assertEquals(3, json.array("b").size)
        assertEquals(-300.0, json.array("b")[2].let { (it as JsonValue.Num).value }, 0.001)
        assertEquals(true, json.obj("c")?.bool("d"))
        assertNull(json.obj("c")?.get("e")?.takeIf { it !is JsonValue.Null })
        assertEquals("é", json.string("f"))
    }
}