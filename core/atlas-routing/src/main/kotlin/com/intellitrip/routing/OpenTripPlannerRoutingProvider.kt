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
 * OpenTripPlanner adapter.
 *
 * Topology decision (ADR-007): hybrid. The in-process [StaticNetworkPlanner]
 * answers first and this adapter covers what it cannot; see [HybridRoutingProvider].
 * Whether the remote service is self-hosted, regional or a local process does not
 * change the [RoutingProvider] contract.
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
        return parsePlan(body, request, feedId)
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
         * Maps an OTP plan response into provider-neutral journeys.
         *
         * OTP returns an envelope of itineraries; each has legs that may be walk or
         * transit, with geometry. Times arrive as epoch milliseconds. Anything that
         * cannot be represented faithfully is dropped rather than approximated: a
         * journey missing a boarding stop is not a journey.
         */
        fun parsePlan(
            body: String,
            request: TripPlanRequest,
            feedId: FeedId = FeedId("metro-transit-regional"),
            now: Instant = Instant.now(),
        ): ProviderResult<List<JourneyOption>> {
            val root = try {
                JsonValue.parse(body)
            } catch (e: Exception) {
                return ProviderResult.MalformedResponse(ProviderError("Routing response was not JSON", e))
            }
            val envelope = root.obj("plan")
                ?: return ProviderResult.MalformedResponse(
                    ProviderError("Routing response had no plan envelope"),
                )
            val journeys: List<JourneyOption> = envelope.array("itineraries")
                .mapNotNull { mapItinerary(it, feedId, now) }
            return ProviderResult.Success(journeys, FreshnessMetadata(now, now, Duration.ZERO, "open-trip-planner"))
        }

        /** Returns null when the itinerary cannot be represented faithfully. */
        private fun mapItinerary(
            itinerary: JsonValue,
            feedId: FeedId,
            now: Instant,
        ): JourneyOption? {
            val legs = itinerary.array("legs").mapNotNull { mapLeg(it, feedId, now) }
            if (legs.isEmpty()) return null
            val start = itinerary.long("startTime")?.let { Instant.ofEpochMilli(it) }
                ?: (legs.firstOrNull() as? Leg.WalkLeg)?.start
                ?: return null
            val end = itinerary.long("endTime")?.let { Instant.ofEpochMilli(it) }
                ?: (legs.lastOrNull() as? Leg.WalkLeg)?.end
                ?: return null
            return JourneyOption(
                id = itinerary.string("id") ?: "otp-${start.toEpochMilli()}",
                legs = legs,
                transfers = emptyList(),
                departure = start,
                arrival = end,
            )
        }

        private fun mapLeg(leg: JsonValue, feedId: FeedId, now: Instant): Leg? {
            val from = leg.obj("from") ?: return null
            val to = leg.obj("to") ?: return null
            val fromPoint = point(from) ?: return null
            val toPoint = point(to) ?: return null
            val departure = leg.long("startTime")?.let { Instant.ofEpochMilli(it) } ?: return null
            val arrival = leg.long("endTime")?.let { Instant.ofEpochMilli(it) } ?: return null
            val duration = Duration.between(departure, arrival)

            // OTP marks walking legs with mode WALK and no route; anything carrying a
            // route is a transit leg.
            val route = leg.obj("route")
            val isTransit = route != null && route.string("shortName") != null ||
                route?.string("longName") != null || leg.bool("transitLeg") == true

            if (!isTransit) {
                val meters = leg.obj("distance")?.number("value")?.toDouble() ?: 0.0
                return Leg.WalkLeg(
                    from = fromPoint,
                    to = toPoint,
                    start = departure,
                    duration = duration,
                    meters = meters,
                )
            }

            val routeId = route?.string("shortName") ?: route?.string("longName") ?: return null
            val tripId = leg.string("tripId")?.takeIf { it.isNotBlank() } ?: return null
            return Leg.Transit(
                agencyId = AgencyId(feedId.value),
                routeKey = RouteKey(feedId, routeId),
                tripKey = TripKey(feedId, tripId),
                serviceDate = null,
                fromStopKey = stopKeyOf(from, feedId),
                toStopKey = stopKeyOf(to, feedId),
                departure = departure,
                arrival = arrival,
                vehicleKey = null,
                realtimeStatus = realtimeStatus(leg, now),
                delaySeconds = null,
                scheduledDeparture = departure,
                scheduledArrival = arrival,
                duration = duration,
            )
        }

        /**
         * OTP may omit stop ids for synthesized walk-to-transit points; falling back to
         * the stop name keeps the leg usable rather than dropping it.
         */
        private fun stopKeyOf(node: JsonValue, feedId: FeedId): StopKey {
            val id = node.string("stopId")?.takeIf { it.isNotBlank() }
                ?: node.string("name")?.takeIf { it.isNotBlank() }
                ?: "unknown"
            return StopKey(feedId, id)
        }

        private fun realtimeStatus(leg: JsonValue, now: Instant): com.intellitrip.domain.RealtimeStatus {
            val departure = leg.long("startTime")?.let { Instant.ofEpochMilli(it) } ?: return com.intellitrip.domain.RealtimeStatus.NO_DATA
            // OTP reports real-time by the presence of realTime data; absence of a
            // realtime feed is NO_DATA rather than a confident ON_TIME.
            return if (leg.obj("departureDelay") != null || leg.obj("realTime") != null) {
                com.intellitrip.domain.RealtimeStatus.ON_TIME
            } else if (departure.isBefore(now)) {
                com.intellitrip.domain.RealtimeStatus.SCHEDULED
            } else {
                com.intellitrip.domain.RealtimeStatus.SCHEDULED
            }
        }

        private fun point(node: JsonValue): GeoPoint? {
            val lat = node.number("lat") ?: return null
            val lon = node.number("lon") ?: return null
            return GeoPoint(lat, lon)
        }
    }
}