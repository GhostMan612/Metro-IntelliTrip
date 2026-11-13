package com.intellitrip.routing

import com.intellitrip.domain.FeedId
import com.intellitrip.domain.GeoMath
import com.intellitrip.domain.GeoPoint
import com.intellitrip.domain.Route
import com.intellitrip.domain.RouteKey
import com.intellitrip.domain.Stop
import com.intellitrip.domain.StopKey
import com.intellitrip.domain.Trip
import com.intellitrip.domain.TripKey

/**
 * Maps OpenTripPlanner entity ids back onto the local static feed.
 *
 * OTP returns its own identifiers: a stop may be `feedId:stopId`, a route may be
 * identified only by its short name, and a trip id may be namespaced. The local
 * planner uses [StopKey]/[RouteKey]/[TripKey] from the GTFS snapshot. Without this
 * bridge a remote journey could be shown but never replayed against the local
 * network: selecting it would fail to find the route shape, the stop, or the live
 * vehicle.
 *
 * Resolution is deliberately strict. A name match is accepted only when it is
 * unique, or when the OTP response carries a point and exactly the nearest stop is
 * within [MAX_NAME_MATCH_METERS]. A trip id is accepted only when its local route
 * matches the resolved route. Ambiguous or missing data returns null and the
 * adapter falls back to the raw OTP id, which is still truthfully feed-scoped but
 * visibly not locally resolvable.
 */
class OtpIdentityResolver(private val network: RoutingNetwork) {

    private val stopsById: Map<String, List<Stop>> =
        network.stops.groupBy { it.key.stopId }
    private val stopsByName: Map<String, List<Stop>> =
        network.stops.groupBy { normalizeName(it.name) }
    private val routesById: Map<String, Route> =
        network.routes.associateBy { it.key.routeId }
    private val routesByShortName: Map<String, List<Route>> =
        network.routes.filter { !it.shortName.isNullOrBlank() }
            .groupBy { normalizeName(it.shortName!!) }
    private val routesByLongName: Map<String, List<Route>> =
        network.routes.filter { !it.longName.isNullOrBlank() }
            .groupBy { normalizeName(it.longName!!) }
    private val tripsById: Map<String, Trip> =
        network.trips.associateBy { it.key.tripId }

    fun resolveStop(
        otpStopId: String?,
        name: String?,
        point: GeoPoint?,
        feedId: FeedId = network.feedId,
    ): StopKey? {
        if (feedId != network.feedId) return null
        otpStopId?.let { raw ->
            idCandidates(raw).forEach { candidate ->
                val matches = stopsById[candidate]
                if (!matches.isNullOrEmpty()) {
                    return pickStop(matches, point)?.key
                }
            }
        }
        val normalizedName = name?.takeIf { it.isNotBlank() }?.let(::normalizeName) ?: return null
        val matches = stopsByName[normalizedName] ?: return null
        if (matches.size == 1) {
            return matches.single().key.takeIf { it.feedId == feedId }
        }
        return point?.let { location ->
            matches
                .map { stop -> stop to GeoMath.distanceMeters(location, stop.location) }
                .filter { (_, meters) -> meters <= MAX_NAME_MATCH_METERS }
                .minByOrNull { (_, meters) -> meters }
                ?.first
                ?.key
                ?.takeIf { it.feedId == feedId }
        }
    }

    fun resolveRoute(
        otpRouteId: String?,
        shortName: String?,
        longName: String?,
        feedId: FeedId = network.feedId,
    ): RouteKey? {
        otpRouteId?.let { raw ->
            idCandidates(raw).forEach { candidate ->
                routesById[candidate]?.let { route ->
                    if (route.key.feedId == feedId) return route.key
                }
            }
        }
        shortName?.takeIf { it.isNotBlank() }?.let { raw ->
            val matches = routesByShortName[normalizeName(raw)].orEmpty()
            when (matches.size) {
                0 -> Unit
                1 -> return matches.single().key.takeIf { it.feedId == feedId }
                else -> {
                    // Same short name on several routes is only ambiguous until the
                    // long name narrows it. If it still does not, do not guess.
                    longName?.takeIf { it.isNotBlank() }?.let { ln ->
                        val narrowed = matches.filter {
                            normalizeName(it.longName.orEmpty()) == normalizeName(ln)
                        }
                        if (narrowed.size == 1) return narrowed.single().key
                    }
                    return null
                }
            }
        }
        longName?.takeIf { it.isNotBlank() }?.let { raw ->
            val matches = routesByLongName[normalizeName(raw)].orEmpty()
            if (matches.size == 1) return matches.single().key.takeIf { it.feedId == feedId }
        }
        return null
    }

    fun resolveTrip(
        otpTripId: String?,
        routeKey: RouteKey?,
        feedId: FeedId = network.feedId,
    ): TripKey? {
        val raw = otpTripId?.takeIf { it.isNotBlank() } ?: return null
        val trip = idCandidates(raw).firstNotNullOfOrNull { tripsById[it] } ?: return null
        if (trip.key.feedId != feedId) return null
        if (routeKey != null && trip.routeKey != routeKey) return null
        return trip.key
    }

    private fun pickStop(matches: List<Stop>, point: GeoPoint?): Stop? {
        if (matches.size == 1) return matches.single()
        val location = point ?: return null
        return matches
            .map { stop -> stop to GeoMath.distanceMeters(location, stop.location) }
            .filter { (_, meters) -> meters <= MAX_NAME_MATCH_METERS }
            .minByOrNull { (_, meters) -> meters }
            ?.first
    }

    private fun idCandidates(raw: String): List<String> {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return emptyList()
        return buildList {
            add(trimmed)
            add(trimmed.substringAfterLast(':'))
            // Some OTP deployments use `feed_stop` rather than `feed:stop`.
            add(trimmed.substringAfterLast('_'))
        }.filter { it.isNotBlank() }.distinct()
    }

    private fun normalizeName(value: String): String =
        value.trim().lowercase().replace(WHITESPACE, " ")

    private companion object {
        const val MAX_NAME_MATCH_METERS = 750.0
        val WHITESPACE = Regex("\\s+")
    }
}
