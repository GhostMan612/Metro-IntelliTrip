package com.intellitrip.routing

import com.intellitrip.contracts.ProviderResult
import com.intellitrip.domain.FeedId
import com.intellitrip.domain.GeoPoint
import com.intellitrip.domain.TripPlanRequest
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days

/**
 * Opt-in live check against a real OpenTripPlanner deployment.
 *
 * Nothing here runs in normal CI. Set `INTELLITRIP_OTP_ENDPOINT` to a reachable
 * deployment to exercise the transport, URL construction and response mapper
 * end-to-end. This is the one check that cannot be replaced by a fixture: it is
 * what catches field-level drift between OTP versions.
 *
 * The trip is planned for 08:00 tomorrow in the agency zone, because a plan
 * requested at the current instant can legitimately return no journeys late at
 * night. The test still asserts that the endpoint answered with at least one
 * itinerary; a deployment that cannot answer a normal weekday-morning trip is not
 * serving the app correctly.
 */
class OtpLiveSmokeTest {

    @Test
    fun plansAgainstConfiguredOtpEndpoint() = runBlocking {
        val endpoint = System.getenv("INTELLITRIP_OTP_ENDPOINT")?.trim().orEmpty()
        if (endpoint.isEmpty()) {
            println("Skipping live OTP smoke test: INTELLITRIP_OTP_ENDPOINT is not set")
            return@runBlocking
        }

        val zone = TimeZone.of("America/Chicago")
        val tomorrow = Clock.System.now().plus(1.days).toLocalDateTime(zone).date
        val departAt = LocalDateTime(
            year = tomorrow.year,
            monthNumber = tomorrow.monthNumber,
            dayOfMonth = tomorrow.dayOfMonth,
            hour = 8,
            minute = 0,
        ).toInstant(zone)

        val provider = OpenTripPlannerRoutingProvider(
            baseUrl = endpoint,
            feedId = FeedId("metro-transit-regional"),
            transport = JvmHttpTransport(),
        )
        val result = provider.plan(
            TripPlanRequest(
                origin = GeoPoint(44.9765, -93.2650),
                destination = GeoPoint(44.9580, -93.1560),
                departAt = departAt,
                arriveBy = null,
                maxTransfers = 1,
            ),
        )

        assertTrue(
            result is ProviderResult.Success,
            "Expected a plan result, got ${result::class.simpleName}",
        )
        assertTrue(
            result.data.isNotEmpty(),
            "OTP answered but produced no itineraries for a weekday-morning trip",
        )
        assertTrue(
            result.data.all { it.legs.isNotEmpty() },
            "Every mapped journey must have at least one leg",
        )
    }

    private class JvmHttpTransport : RoutingTransport {
        private val client = HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(15))
            .build()

        override fun get(url: String): String {
            val request = HttpRequest.newBuilder(URI.create(url))
                .timeout(java.time.Duration.ofSeconds(60))
                .header("Accept", "application/json")
                .header("User-Agent", "IntelliTrip/0.1 (OTP live smoke test)")
                .GET()
                .build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            check(response.statusCode() in 200..299) {
                "OTP returned HTTP ${response.statusCode()}"
            }
            return response.body()
        }
    }
}
