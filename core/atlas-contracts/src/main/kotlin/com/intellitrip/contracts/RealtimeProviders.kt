package com.intellitrip.contracts

import com.intellitrip.domain.DataScope
import com.intellitrip.domain.ServiceAlert
import com.intellitrip.domain.TripUpdate
import com.intellitrip.domain.Vehicle
import kotlinx.coroutines.flow.Flow

/**
 * Provider-neutral realtime transit access.
 *
 * Streaming contract rule: every operation is `Flow<ProviderResult<T>>`.
 * A realtime feed must be explicitly associated with the static `FeedId` it
 * extends before entities are mapped into feed-scoped keys (ADR-011).
 */
interface TransitRealtimeProvider {
    val feedId: com.intellitrip.domain.FeedId

    fun vehiclePositions(scope: DataScope): Flow<ProviderResult<List<Vehicle>>>
}

interface TripUpdateProvider {
    val feedId: com.intellitrip.domain.FeedId

    fun tripUpdates(scope: DataScope): Flow<ProviderResult<List<TripUpdate>>>
}

interface ServiceAlertProvider {
    val feedId: com.intellitrip.domain.FeedId

    fun alerts(): Flow<ProviderResult<List<ServiceAlert>>>
}