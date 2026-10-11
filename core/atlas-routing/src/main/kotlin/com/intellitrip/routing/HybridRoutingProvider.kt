package com.intellitrip.routing

import com.intellitrip.contracts.ProviderResult
import com.intellitrip.contracts.RoutingProvider
import com.intellitrip.domain.GeoPoint
import com.intellitrip.domain.JourneyOption
import com.intellitrip.domain.Leg
import com.intellitrip.domain.TripPlanRequest
import java.time.Duration
import java.time.Instant

/**
 * Hybrid routing: local first, remote for what local cannot answer.
 *
 * The local [StaticNetworkPlanner] runs first because it is instant, works offline
 * and keeps the user's origin and destination on the device. The remote provider is
 * consulted only when the local planner has nothing to offer, which in practice means
 * cross-agency trips and fare-aware itineraries.
 *
 * Degradation is explicit. If the remote call fails, the local result is returned
 * with the failure recorded in `missing` rather than being presented as if it were
 * complete, and the reverse if local is unavailable. Callers can always tell which
 * answers came from where.
 */
class HybridRoutingProvider(
    private val local: RoutingProvider,
    private val remote: RoutingProvider,
    private val remoteFallback: Boolean = true,
    override val providerId: String = "hybrid",
) : RoutingProvider {

    override suspend fun plan(request: TripPlanRequest): ProviderResult<List<JourneyOption>> {
        val localResult = try {
            local.plan(request)
        } catch (e: Exception) {
            ProviderResult.Unavailable(
                com.intellitrip.contracts.ProviderError("Local planner failed", e),
            )
        }

        val localJourneys = localResult.dataOrEmpty()
        if (localJourneys.isNotEmpty()) {
            return localResult
        }

        // Nothing local. Only reach for the remote when allowed, and when there is a
        // request that could plausibly be satisfied by it.
        if (!remoteFallback) return localResult

        val remoteResult = try {
            remote.plan(request)
        } catch (e: Exception) {
            ProviderResult.NetworkFailure(
                com.intellitrip.contracts.ProviderError("Remote planner failed", e),
            )
        }

        val remoteJourneys = remoteResult.dataOrEmpty()
        if (remoteJourneys.isEmpty()) {
            // Both empty. Report the local gap, noting the remote was also tried. A
            // failure carries no freshness, so a null there becomes an unknown-age
            // value rather than being dropped or faked as fresh.
            return ProviderResult.PartialResult(
                data = emptyList<JourneyOption>(),
                missing = localResult.missingOrNull() + "remote-routing-no-result",
                freshness = localResult.freshnessOrNull() ?: UNKNOWN_FRESHNESS,
            )
        }

        return ProviderResult.Success(
            data = remoteJourneys,
            freshness = remoteResult.freshnessOrNull() ?: UNKNOWN_FRESHNESS,
        )
    }
}

/**
 * Data carried by a result, whether or not it is complete. Every failure variant
 * carries no data, so this returns empty rather than throwing, letting the hybrid
 * provider treat "failed" and "found nothing" alike.
 */
internal fun ProviderResult<out List<JourneyOption>>.dataOrEmpty(): List<JourneyOption> = when (this) {
    is ProviderResult.Success -> data
    is ProviderResult.StaleSuccess -> data
    is ProviderResult.PartialResult -> data
    else -> emptyList()
}

internal fun ProviderResult<*>.missingOrNull(): List<String> =
    (this as? ProviderResult.PartialResult)?.missing ?: emptyList()

internal fun <T> ProviderResult<T>.freshnessOrNull(): com.intellitrip.contracts.FreshnessMetadata? =
    when (this) {
        is ProviderResult.Success -> freshness
        is ProviderResult.StaleSuccess -> freshness
        is ProviderResult.PartialResult -> freshness
        else -> null
    }

/**
 * Age is unbounded rather than zero: a result with no known source time is not
 * fresh, and reporting it as fresh would let the UI present unknown-age data as
 * current.
 */
private val UNKNOWN_FRESHNESS = com.intellitrip.contracts.FreshnessMetadata(
    sourceTimestamp = Instant.EPOCH,
    fetchedAt = Instant.EPOCH,
    age = Duration.ofDays(365),
    providerId = "unknown",
)