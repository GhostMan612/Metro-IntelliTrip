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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

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
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val agencyZone: TimeZone = TimeZone.of("America/Chicago"),
    /**
     * Static network used to translate OTP entity ids back to local keys.
     * Null keeps the adapter usable as a standalone remote provider; callers then
     * receive raw OTP ids wrapped in feed-scoped keys.
     */
    network: RoutingNetwork? = null,
    override val providerId: String = "open-trip-planner",
) : RoutingProvider {

    private val identityResolver: OtpIdentityResolver? =
        network?.let { OtpIdentityResolver(it) }

    override suspend fun plan(request: TripPlanRequest): ProviderResult<List<JourneyOption>> {
        val url = planUrl(request)
        val body = try {
            withContext(ioDispatcher) { transport.get(url) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return ProviderResult.NetworkFailure(ProviderError("Routing service unreachable", e))
        }
        return parsePlan(body, request, feedId, resolver = identityResolver)
    }

    internal fun planUrl(request: TripPlanRequest): String {
        val from = "${request.origin.lat},${request.origin.lon}"
        val to = "${request.destination.lat},${request.destination.lon}"
        // Accept the deployment root, `/otp`, the router root, or a complete plan
        // endpoint. The documented deployment value is `https://host/otp`, while
        // OTP's own default path is `/otp/routers/default/plan`; supporting both
        // avoids a silent `.../otp/otp/...` 404.
        val trimmed = baseUrl.trimEnd('/')
        val planPath = when {
            trimmed.endsWith("/plan") -> ""
            trimmed.endsWith("/otp/routers/default") -> "/plan"
            trimmed.endsWith("/otp") -> "/routers/default/plan"
            else -> "/otp/routers/default/plan"
        }
        val builder = StringBuilder(trimmed)
            .append(planPath)
            .append("?from=")
            .append(urlEncode(from))
            .append("&to=")
            .append(urlEncode(to))
            .append("&arriveBy=")
            .append(request.arriveBy != null)
            .append("&numItineraries=")
            .append(DEFAULT_ITINERARIES)
            .append("&mode=")
            .append(urlEncode("TRANSIT,WALK"))
        // OTP expects calendar date + time-of-day, not epoch math. Format the
        // anchor instant (arriveBy wins when set) in the agency zone.
        val anchor = request.arriveBy ?: request.departAt
        if (anchor != null) {
            val local = anchor.toLocalDateTime(agencyZone)
            val datePart = local.year.toString().padStart(4, '0') +
                local.monthNumber.toString().padStart(2, '0') +
                local.dayOfMonth.toString().padStart(2, '0')
            val timePart = local.hour.toString().padStart(2, '0') +
                local.minute.toString().padStart(2, '0')
            builder.append("&date=")
                .append(datePart)
                .append("&time=")
                .append(timePart)
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
            now: Instant = Clock.System.now(),
            resolver: OtpIdentityResolver? = null,
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
                .mapNotNull { mapItinerary(it, feedId, now, resolver) }
            return ProviderResult.Success(journeys, FreshnessMetadata(now, now, Duration.ZERO, "open-trip-planner"))
        }

        /** Returns null when the itinerary cannot be represented faithfully. */
        private fun mapItinerary(
            itinerary: JsonValue,
            feedId: FeedId,
            now: Instant,
            resolver: OtpIdentityResolver?,
        ): JourneyOption? {
            val legs = itinerary.array("legs").mapNotNull { mapLeg(it, feedId, now, resolver) }
            if (legs.isEmpty()) return null
            val start = itinerary.long("startTime")?.let { Instant.fromEpochMilliseconds(it) }
                ?: (legs.firstOrNull() as? Leg.WalkLeg)?.start
                ?: return null
            val end = itinerary.long("endTime")?.let { Instant.fromEpochMilliseconds(it) }
                ?: (legs.lastOrNull() as? Leg.WalkLeg)?.end
                ?: return null
            return JourneyOption(
                id = itinerary.string("id") ?: "otp-${start.toEpochMilliseconds()}",
                legs = legs,
                transfers = buildTransfers(legs),
                departure = start,
                arrival = end,
            )
        }

        private fun mapLeg(
            leg: JsonValue,
            feedId: FeedId,
            now: Instant,
            resolver: OtpIdentityResolver?,
        ): Leg? {
            val from = leg.obj("from") ?: return null
            val to = leg.obj("to") ?: return null
            val fromPoint = point(from) ?: return null
            val toPoint = point(to) ?: return null
            val departure = leg.long("startTime")?.let { Instant.fromEpochMilliseconds(it) } ?: return null
            val arrival = leg.long("endTime")?.let { Instant.fromEpochMilliseconds(it) } ?: return null
            val duration = arrival - departure

            // OTP marks walking legs with mode WALK and no route; anything carrying a
            // route object or transitLeg=true is a transit leg. Route identity may
            // only have an id, so do not require a short/long name to classify it.
            val route = leg.obj("route")
            val isTransit = route != null || leg.bool("transitLeg") == true

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

            // Route identity: prefer the local route the OTP id/name resolves to.
            // The fallbacks are still feed-scoped and truthful, but a route that is
            // not in the loaded static feed cannot be replayed against local shapes
            // or live vehicles. Never replace a missing id with a display name alone
            // when no local match exists: the raw OTP id is more informative.
            val routeShortName = route?.string("shortName")?.takeIf { it.isNotBlank() }
            val routeLongName = route?.string("longName")?.takeIf { it.isNotBlank() }
            val otpRouteId = route?.string("id")?.takeIf { it.isNotBlank() }
                ?: route?.string("routeId")?.takeIf { it.isNotBlank() }
            val resolvedRoute = resolver?.resolveRoute(
                otpRouteId = otpRouteId,
                shortName = routeShortName,
                longName = routeLongName,
                feedId = feedId,
            )
            val routeKey = resolvedRoute ?: RouteKey(
                feedId,
                routeShortName ?: routeLongName ?: otpRouteId ?: return null,
            )
            val rawTripId = leg.string("tripId")?.takeIf { it.isNotBlank() } ?: return null
            val resolvedTrip = resolver?.resolveTrip(rawTripId, routeKey, feedId)
            val tripKey = resolvedTrip ?: TripKey(feedId, rawTripId)
            return Leg.Transit(
                agencyId = AgencyId(feedId.value),
                routeKey = routeKey,
                tripKey = tripKey,
                serviceDate = null,
                fromStopKey = stopKeyOf(from, feedId, resolver),
                toStopKey = stopKeyOf(to, feedId, resolver),
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
         * Resolves an OTP stop back to a local key when possible. OTP may omit stop
         * ids for synthesized walk-to-transit points, so the name/point are used as
         * a strict fallback. If nothing resolves, the raw OTP id or name is wrapped
         * in a feed-scoped key: still honest about what the remote service said, but
         * not claimed to be a local stop.
         */
        private fun stopKeyOf(
            node: JsonValue,
            feedId: FeedId,
            resolver: OtpIdentityResolver?,
        ): StopKey {
            val otpStopId = node.string("stopId")?.takeIf { it.isNotBlank() }
            val name = node.string("name")?.takeIf { it.isNotBlank() }
            val point = point(node)
            val resolved = resolver?.resolveStop(otpStopId, name, point, feedId)
            if (resolved != null) return resolved
            return StopKey(feedId, otpStopId ?: name ?: "unknown")
        }

        /**
         * Builds one [com.intellitrip.domain.TransferConnection] per transit-to-transit
         * handoff, so transfer intelligence can score OTP journeys. A walk leg
         * between transits supplies the walking duration.
         */
        private fun buildTransfers(legs: List<Leg>): List<com.intellitrip.domain.TransferConnection> {
            val transitIdx = legs.indices.filter { legs[it] is Leg.Transit }
            if (transitIdx.size < 2) return emptyList()
            return transitIdx.zipWithNext().mapNotNull { (aIdx, bIdx) ->
                val arriving = legs[aIdx] as Leg.Transit
                val departing = legs[bIdx] as Leg.Transit
                val between = legs.subList(aIdx + 1, bIdx)
                val walking = between.filterIsInstance<Leg.WalkLeg>().sumOf { it.duration.inWholeSeconds }.seconds
                com.intellitrip.domain.TransferConnection(
                    arrivingLeg = arriving,
                    departingLeg = departing,
                    transferStop = arriving.toStopKey,
                    scheduledBuffer = departing.departure - arriving.arrival,
                    predictedBuffer = null,
                    walkingDuration = walking,
                    confidence = com.intellitrip.domain.TransferConfidence.SCHEDULED_UNKNOWN,
                    rationale = "OTP itinerary transfer",
                )
            }
        }

        private fun realtimeStatus(leg: JsonValue, now: Instant): com.intellitrip.domain.RealtimeStatus {
            val delay = leg.number("departureDelay") ?: leg.number("delay")
            val hasRealtime = leg.get("realTime") != null || leg.get("realTimeData") != null || delay != null
            return when {
                delay != null && delay > 180 -> com.intellitrip.domain.RealtimeStatus.DELAYED
                delay != null && delay < -180 -> com.intellitrip.domain.RealtimeStatus.EARLY
                hasRealtime -> com.intellitrip.domain.RealtimeStatus.ON_TIME
                else -> com.intellitrip.domain.RealtimeStatus.SCHEDULED
            }
        }

        private fun point(node: JsonValue): GeoPoint? {
            val lat = node.number("lat") ?: return null
            val lon = node.number("lon") ?: return null
            return GeoPoint(lat, lon)
        }
    }
}

/**
 * Percent-encodes [value] for use as a URL query component.
 *
 * Replaces the JVM-only `URLEncoder.encode(value, UTF_8)` API, which is not
 * available in common Kotlin: every byte outside the unreserved set ([A-Za-z0-9] plus `-._~`)
 * is emitted as uppercase `%HH` over the UTF-8 byte representation.
 */
private fun urlEncode(value: String): String {
    val out = StringBuilder(value.length)
    for (byte in value.encodeToByteArray()) {
        val b = byte.toInt() and 0xFF
        val c = b.toChar()
        if ((c in 'A'..'Z') || (c in 'a'..'z') || (c in '0'..'9') || c == '-' || c == '.' || c == '_' || c == '~') {
            out.append(c)
        } else {
            out.append('%')
                .append("0123456789ABCDEF"[b shr 4])
                .append("0123456789ABCDEF"[b and 0x0F])
        }
    }
    return out.toString()
}

