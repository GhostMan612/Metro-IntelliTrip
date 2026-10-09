package com.intellitrip.engine

import com.intellitrip.contracts.ProviderResult
import com.intellitrip.contracts.RadarProvider
import com.intellitrip.contracts.RoutingProvider
import com.intellitrip.contracts.ServiceAlertProvider
import com.intellitrip.contracts.TransitRealtimeProvider
import com.intellitrip.contracts.TransitStaticProvider
import com.intellitrip.contracts.TripUpdateProvider
import com.intellitrip.contracts.WeatherAlertProvider
import com.intellitrip.domain.FeedId
import com.intellitrip.domain.Focus
import com.intellitrip.domain.JourneyOption
import com.intellitrip.domain.ScopeState
import com.intellitrip.domain.TripPlanRequest
import com.intellitrip.domain.Vehicle
import com.intellitrip.map.RenderInput
import com.intellitrip.map.RenderSnapshot
import com.intellitrip.map.TransitRenderPipeline
import com.intellitrip.offline.BasemapAvailability
import com.intellitrip.offline.CacheEntry
import com.intellitrip.offline.OfflineRegistry
import com.intellitrip.routing.TransferIntelligenceService
import com.intellitrip.routing.TransferIntelligenceService.JourneyReport
import com.intellitrip.scope.ScopeEngine
import java.time.Instant

/**
 * Providers supplied by the host application. Every field is optional so a host
 * can adopt Atlas incrementally and replace any adapter without touching the
 * engine.
 */
data class AtlasProviders(
    val transitStatic: TransitStaticProvider? = null,
    val transitRealtime: TransitRealtimeProvider? = null,
    val tripUpdates: TripUpdateProvider? = null,
    val transitAlerts: ServiceAlertProvider? = null,
    val routing: RoutingProvider? = null,
    val radar: RadarProvider? = null,
    val weatherAlerts: WeatherAlertProvider? = null,
)

data class AtlasCapabilities(
    val staticTransit: Boolean,
    val realtimeTransit: Boolean,
    val routing: Boolean,
    val radar: Boolean,
    val weatherAlerts: Boolean,
    val offline: Boolean,
)

/**
 * The public entry point of the Atlas engine.
 *
 * It is a plain Kotlin/JVM class: an Android app, a CLI, a server-side component
 * or another product (Sovereign Atlas, Recovery for All) can host it. All
 * platform concerns live in the host.
 */
class AtlasEngine(
    val feedId: FeedId,
    providers: AtlasProviders = AtlasProviders(),
    private val offlineRegistry: OfflineRegistry? = null,
    private val scopeEngine: ScopeEngine = ScopeEngine(),
    private val renderPipeline: TransitRenderPipeline = TransitRenderPipeline(),
    private val transferIntelligence: TransferIntelligenceService = TransferIntelligenceService(),
) {

    val providers: AtlasProviders = providers

    fun capabilities(): AtlasCapabilities = AtlasCapabilities(
        staticTransit = providers.transitStatic != null,
        realtimeTransit = providers.transitRealtime != null,
        routing = providers.routing != null,
        radar = providers.radar != null,
        weatherAlerts = providers.weatherAlerts != null,
        offline = offlineRegistry != null,
    )

    /** Resolves continuous scope for a focus. */
    fun scope(focus: Focus? = null): ScopeState = scopeEngine.currentScope(focus)

    /** Renders a transit snapshot. Renderer-agnostic; the host supplies the renderer. */
    fun render(input: RenderInput): RenderSnapshot = renderPipeline.render(input)

    /** Plans a journey and scores its transfers when planning succeeds. */
    suspend fun planJourney(
        request: TripPlanRequest,
        realtimeUpdates: List<com.intellitrip.domain.TripUpdate> = emptyList(),
    ): JourneyOutcome {
        val provider = providers.routing
            ?: return JourneyOutcome.Unavailable("No routing provider configured")
        return when (val result = provider.plan(request)) {
            is ProviderResult.Success -> JourneyOutcome.Planned(
                transferIntelligence.evaluate(result.data, realtimeUpdates, emptyList()).map { it.journey }
            )

            else -> JourneyOutcome.Unavailable(describe(result))
        }
    }

    /** Transfers scored for a set of journeys, with warnings. */
    fun evaluateTransfers(
        journeys: List<JourneyOption>,
        realtimeUpdates: List<com.intellitrip.domain.TripUpdate> = emptyList(),
    ): List<JourneyReport> = transferIntelligence.evaluate(journeys, realtimeUpdates)

    /** Offline capability for the configured feed. */
    fun offlineCapabilities(
        realtimeEntry: CacheEntry? = null,
        basemap: BasemapAvailability = BasemapAvailability.OnlineOnly,
        now: Instant = Instant.now(),
    ) = offlineRegistry?.capabilities(feedId, realtimeEntry, basemap, now)

    private fun describe(result: ProviderResult<*>): String = when (result) {
        is ProviderResult.NetworkFailure -> result.error.message ?: "network failure"
        is ProviderResult.MalformedResponse -> result.error.message ?: "malformed response"
        is ProviderResult.Unavailable -> result.error.message ?: "provider unavailable"
        is ProviderResult.RateLimited -> "rate limited"
        is ProviderResult.AuthFailure -> result.error.message ?: "authentication failure"
        is ProviderResult.Timeout -> result.error.message ?: "timeout"
        else -> "no result"
    }
}

sealed interface JourneyOutcome {
    data class Planned(val journeys: List<JourneyOption>) : JourneyOutcome

    data class Unavailable(val reason: String) : JourneyOutcome
}

/** Convenience accessor for realtime vehicle snapshots from the configured feed. */
fun TransitRealtimeProvider.vehicles(): kotlinx.coroutines.flow.Flow<ProviderResult<List<Vehicle>>> =
    vehiclePositions(com.intellitrip.domain.DataScope.DEFAULT_FEED)