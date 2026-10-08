package com.intellitrip.contracts

import java.time.Duration
import java.time.Instant

data class ProviderIdentity(val providerId: String, val feedId: String? = null)

data class FreshnessMetadata(
    val sourceTimestamp: Instant?,
    val fetchedAt: Instant,
    val age: Duration,
    val providerId: String,
)

sealed interface ProviderResult<out T> {
    data class Success<T>(val data: T, val freshness: FreshnessMetadata) : ProviderResult<T>
    data class StaleSuccess<T>(val data: T, val freshness: FreshnessMetadata) : ProviderResult<T>
    data class Unavailable(val error: ProviderError) : ProviderResult<Nothing>
    data class RateLimited(val retryAfter: Duration?) : ProviderResult<Nothing>
    data class AuthFailure(val error: ProviderError) : ProviderResult<Nothing>
    data class MalformedResponse(val error: ProviderError) : ProviderResult<Nothing>
    data class PartialResult<T>(
        val data: T,
        val missing: List<String>,
        val freshness: FreshnessMetadata,
    ) : ProviderResult<T>
    data class Timeout(val error: ProviderError) : ProviderResult<Nothing>
    data class NetworkFailure(val error: ProviderError) : ProviderResult<Nothing>
}

data class ProviderError(val message: String, val cause: Throwable? = null)
