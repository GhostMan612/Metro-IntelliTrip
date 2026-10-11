package com.intellitrip.routing

import com.intellitrip.domain.GeoPoint
import com.intellitrip.domain.JourneyOption
import com.intellitrip.domain.Leg
import com.intellitrip.domain.TripPlanRequest
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Covers the hybrid policy: local answers first, remote only fills genuine gaps, and
 * a failure is reported rather than silently absorbed.
 */
class HybridRoutingProviderTest {

    private val request = TripPlanRequest(
        origin = GeoPoint(44.9765, -93.2650),
        destination = GeoPoint(44.9580, -93.1560),
        departAt = null,
        arriveBy = null,
        maxTransfers = 1,
    )

    private fun journey(id: String) = JourneyOption(
        id = id,
        legs = emptyList(),
        transfers = emptyList(),
        departure = java.time.Instant.parse("2026-10-05T13:00:00Z"),
        arrival = java.time.Instant.parse("2026-10-05T13:30:00Z"),
    )

    private class Stub(private val result: com.intellitrip.contracts.ProviderResult<List<JourneyOption>>) :
        com.intellitrip.contracts.RoutingProvider {
        var calls = 0
        override val providerId: String = "stub"
        override suspend fun plan(request: TripPlanRequest) = result.also { calls++ }
    }

    @Test
    fun localResultsShortCircuitTheRemote() = runBlocking {
        val local = Stub(
            com.intellitrip.contracts.ProviderResult.Success(
                listOf(journey("local-1")),
                com.intellitrip.contracts.FreshnessMetadata(
                    java.time.Instant.now(), java.time.Instant.now(),
                    java.time.Duration.ZERO, "stub",
                ),
            )
        )
        val remote = Stub(
            com.intellitrip.contracts.ProviderResult.Success(
                listOf(journey("remote-1")),
                com.intellitrip.contracts.FreshnessMetadata(
                    java.time.Instant.now(), java.time.Instant.now(),
                    java.time.Duration.ZERO, "stub",
                ),
            )
        )
        val result = HybridRoutingProvider(local, remote).plan(request)
        assertEquals(1, local.calls)
        // The whole point: no user coordinates leave the device when local can answer.
        assertEquals(0, remote.calls)
        assertEquals(listOf("local-1"), result.dataOrEmpty().map { it.id })
    }

    @Test
    fun remoteCoversWhatLocalCannot() = runBlocking {
        val local = Stub(
            com.intellitrip.contracts.ProviderResult.PartialResult(
                data = emptyList(),
                missing = listOf("cross-agency-unavailable"),
                freshness = com.intellitrip.contracts.FreshnessMetadata(
                    java.time.Instant.now(), java.time.Instant.now(),
                    java.time.Duration.ZERO, "stub",
                ),
            )
        )
        val remote = Stub(
            com.intellitrip.contracts.ProviderResult.Success(
                listOf(journey("remote-1")),
                com.intellitrip.contracts.FreshnessMetadata(
                    java.time.Instant.now(), java.time.Instant.now(),
                    java.time.Duration.ZERO, "otp",
                ),
            )
        )
        val result = HybridRoutingProvider(local, remote).plan(request)
        assertEquals(1, remote.calls)
        assertEquals(listOf("remote-1"), result.dataOrEmpty().map { it.id })
    }

    @Test
    fun bothEmptyReportsBothGaps() = runBlocking {
        val fresh = com.intellitrip.contracts.FreshnessMetadata(
            java.time.Instant.now(), java.time.Instant.now(),
            java.time.Duration.ZERO, "stub",
        )
        val local = Stub(
            com.intellitrip.contracts.ProviderResult.PartialResult(
                emptyList(), listOf("cross-agency-unavailable"), fresh,
            )
        )
        val remote = Stub(com.intellitrip.contracts.ProviderResult.Success(emptyList(), fresh))
        val result = HybridRoutingProvider(local, remote).plan(request)
        assertTrue(
            result.missingOrNull().contains("remote-routing-no-result"),
            "expected the remote gap recorded, got ${result.missingOrNull()}",
        )
    }

    @Test
    fun remoteFailureStillReturnsLocalData() = runBlocking {
        val local = Stub(
            com.intellitrip.contracts.ProviderResult.Success(
                listOf(journey("local-1")),
                com.intellitrip.contracts.FreshnessMetadata(
                    java.time.Instant.now(), java.time.Instant.now(),
                    java.time.Duration.ZERO, "stub",
                ),
            )
        )
        // A remote that throws must not take the whole plan down.
        val remote = object : com.intellitrip.contracts.RoutingProvider {
            override val providerId: String = "broken"
            override suspend fun plan(request: TripPlanRequest): com.intellitrip.contracts.ProviderResult<List<JourneyOption>> =
                throw IllegalStateException("remote down")
        }
        val result = HybridRoutingProvider(local, remote).plan(request)
        assertEquals(listOf("local-1"), result.dataOrEmpty().map { it.id })
    }

    @Test
    fun remoteFailureWithNoLocalDataDegradesToPartial() = runBlocking {
        val local = Stub(
            com.intellitrip.contracts.ProviderResult.PartialResult(
                emptyList(), listOf("no-local-result"),
                com.intellitrip.contracts.FreshnessMetadata(
                    java.time.Instant.now(), java.time.Instant.now(),
                    java.time.Duration.ZERO, "stub",
                ),
            )
        )
        val remote = object : com.intellitrip.contracts.RoutingProvider {
            override val providerId: String = "broken"
            override suspend fun plan(request: TripPlanRequest): com.intellitrip.contracts.ProviderResult<List<JourneyOption>> =
                throw IllegalStateException("remote down")
        }
        val result = HybridRoutingProvider(local, remote).plan(request)
        // Not a Success: an empty success would read as "no trips exist".
        assertTrue(
            result is com.intellitrip.contracts.ProviderResult.PartialResult,
            "expected PartialResult, got ${result::class.simpleName}",
        )
        assertTrue(result.dataOrEmpty().isEmpty())
    }

    @Test
    fun remoteFallbackCanBeDisabledForStrictOfflineMode() = runBlocking {
        val fresh = com.intellitrip.contracts.FreshnessMetadata(
            java.time.Instant.now(), java.time.Instant.now(),
            java.time.Duration.ZERO, "stub",
        )
        val local = Stub(
            com.intellitrip.contracts.ProviderResult.PartialResult(
                emptyList(), listOf("no-local-result"), fresh,
            )
        )
        val remote = Stub(com.intellitrip.contracts.ProviderResult.Success(listOf(journey("r")), fresh))
        val result = HybridRoutingProvider(local, remote, remoteFallback = false).plan(request)
        assertEquals(0, remote.calls)
        assertTrue(result.dataOrEmpty().isEmpty())
    }
}