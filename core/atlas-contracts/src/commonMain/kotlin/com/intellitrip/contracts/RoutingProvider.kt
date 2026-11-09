package com.intellitrip.contracts

import com.intellitrip.domain.JourneyOption
import com.intellitrip.domain.TripPlanRequest

/**
 * Provider-neutral routing contract.
 *
 * Topology is RESEARCH-GATED (ADR-007): the provider may be a self-hosted
 * OpenTripPlanner service, a trusted public service, a local engine or a hybrid
 * strategy. The contract is unchanged by that decision.
 */
interface RoutingProvider {
    val providerId: String

    suspend fun plan(request: TripPlanRequest): ProviderResult<List<JourneyOption>>
}