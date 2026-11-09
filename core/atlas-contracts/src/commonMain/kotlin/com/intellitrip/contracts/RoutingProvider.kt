package com.intellitrip.contracts

import com.intellitrip.domain.JourneyOption
import com.intellitrip.domain.TripPlanRequest

/**
 * Provider-neutral routing contract.
 *
 * Topology is decided (ADR-007, docs/OTP_DEPLOYMENT.md): hybrid — the local
 * `StaticNetworkPlanner` answers first and an OpenTripPlanner adapter covers
 * what it cannot. The contract is unchanged by that decision.
 */
interface RoutingProvider {
    val providerId: String

    suspend fun plan(request: TripPlanRequest): ProviderResult<List<JourneyOption>>
}