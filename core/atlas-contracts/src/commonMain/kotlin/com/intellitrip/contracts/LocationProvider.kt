package com.intellitrip.contracts

import com.intellitrip.domain.UserLocation
import kotlinx.coroutines.flow.Flow

/**
 * The user's own position.
 *
 * A contract rather than an Android type so the engine stays portable (ADR-004) and
 * a JVM host can substitute a fixed point. Silence from [positions] is legitimate:
 * it means permission was denied or no provider is enabled, and callers must not
 * treat it as an error.
 */
interface LocationProvider {

    /**
     * Emits the position when it changes, seeded with the best last-known fix so
     * the map has something to show before GPS warms up.
     */
    fun positions(): Flow<UserLocation>

    /** Whether location permission is currently held. */
    fun hasPermission(): Boolean
}