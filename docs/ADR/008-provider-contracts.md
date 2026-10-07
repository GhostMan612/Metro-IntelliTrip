# ADR-008: Provider-neutral contracts
Status: Accepted
Context: Multiple transit/weather/map/traffic providers will plug into Atlas.
Decision: Provider-specific DTOs stay at adapters; core consumes provider-neutral contracts.
Result semantics: providers return a coherent `ProviderResult<T>` (success, stale success, unavailable, rate limited, auth failure, malformed response, partial result, timeout/network failure) with common `FreshnessMetadata` (sourceTimestamp, fetchedAt, age/freshness, provider identity). Streaming uses `Flow<ProviderResult<T>>`.
