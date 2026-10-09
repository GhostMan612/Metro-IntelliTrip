package com.intellitrip.routing

import com.intellitrip.contracts.FreshnessMetadata
import com.intellitrip.contracts.ProviderError
import com.intellitrip.contracts.ProviderResult
import com.intellitrip.contracts.RoutingProvider
import com.intellitrip.domain.AgencyId
import com.intellitrip.domain.FeedId
import com.intellitrip.domain.GeoPoint
import com.intellitrip.domain.JourneyOption
import com.intellitrip.domain.Leg
import com.intellitrip.domain.RouteKey
import com.intellitrip.domain.ScheduleRelationship
import com.intellitrip.domain.ServiceKey
import com.intellitrip.domain.StopKey
import com.intellitrip.domain.TripKey
import com.intellitrip.domain.TripPlanRequest
import com.intellitrip.domain.TripUpdate
import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Transport abstraction so the adapter can be exercised without a live service. */
fun interface RoutingTransport {
    fun get(url: String): String
}

/**
 * OpenTripPlanner adapter spike.
 *
 * Topology is RESEARCH-GATED (ADR-007): this adapter assumes only that some
 * OpenTripPlanner-compatible service is reachable. Whether that service is
 * self-hosted, a trusted regional deployment or a local process remains
 * undecided and does not change the [RoutingProvider] contract.
 */
class OpenTripPlannerRoutingProvider(
    private val baseUrl: String,
    private val feedId: FeedId,
    private val transport: RoutingTransport,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    override val providerId: String = "open-trip-planner",
) : RoutingProvider {

    override suspend fun plan(request: TripPlanRequest): ProviderResult<List<JourneyOption>> {
        val url = planUrl(request)
        val body = try {
            withContext(ioDispatcher) { transport.get(url) }
        } catch (e: IOException) {
            return ProviderResult.NetworkFailure(ProviderError("Routing service unreachable", e))
        } catch (e: Exception) {
            return ProviderResult.MalformedResponse(ProviderError("Unreadable routing response", e))
        }
        return parsePlan(body, request)
    }

    internal fun planUrl(request: TripPlanRequest): String {
        val from = "${request.origin.lat},${request.origin.lon}"
        val to = "${request.destination.lat},${request.destination.lon}"
        val builder = StringBuilder(baseUrl.trimEnd('/'))
            .append("/otp/routers/default/plan?from=")
            .append(URLEncoder.encode(from, StandardCharsets.UTF_8))
            .append("&to=")
            .append(URLEncoder.encode(to, StandardCharsets.UTF_8))
            .append("&arriveBy=")
            .append(request.arriveBy != null)
            .append("&numItineraries=")
            .append(DEFAULT_ITINERARIES)
            .append("&mode=")
            .append(URLEncoder.encode("TRANSIT,WALK", StandardCharsets.UTF_8))
        val departAt = request.departAt
        if (departAt != null) {
            builder.append("&date=")
                .append(departAt.epochSecond / 86_400)
                .append("&time=")
                .append(departAt.epochSecond % 86_400)
        }
        return builder.toString()
    }

    internal companion object {
        const val DEFAULT_ITINERARIES = 5

        /**
         * Maps an OTP itinerary payload into provider-neutral journeys.
         *
         * OTP responses are large and vary by version, so this spike returns an
         * empty itinerary list plus freshness metadata; the static-network
         * planner is the authoritative implementation for now. A full OTP
         * itinerary mapper is deferred until the routing topology research gate
         * closes and a response fixture is captured.
         */
        fun parsePlan(
            body: String,
            request: TripPlanRequest,
            feedId: FeedId = FeedId("metro-transit-regional"),
            now: Instant = Instant.now(),
        ): ProviderResult<List<JourneyOption>> = ProviderResult.Success(
            emptyList(),
            FreshnessMetadata(now, now, Duration.ZERO, "open-trip-planner"),
        )
    }
}
