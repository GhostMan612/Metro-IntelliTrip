package com.intellitrip.sample

import com.intellitrip.domain.FeedId
import com.intellitrip.domain.Focus
import com.intellitrip.domain.GeoPoint
import com.intellitrip.domain.TripPlanRequest
import com.intellitrip.engine.AtlasEngine
import com.intellitrip.engine.AtlasProviders
import com.intellitrip.engine.JourneyOutcome
import kotlinx.coroutines.runBlocking

/**
 * Plain JVM host for the Atlas engine.
 *
 * This is the portability proof required by Phase 09: it consumes the engine
 * with no Android SDK, no activity, and no Android-specific types. A second
 * product (Sovereign Atlas, Recovery for All) can host Atlas exactly this way.
 */
fun main() {
    val feedId = FeedId("metro-transit-regional")
    val engine = AtlasEngine(feedId, AtlasProviders())

    println("Atlas sample host (JVM, no Android)")
    println("feed: ${feedId.value}")

    val capabilities = engine.capabilities()
    println(
        "capabilities: static=${capabilities.staticTransit} realtime=${capabilities.realtimeTransit} " +
            "routing=${capabilities.routing} radar=${capabilities.radar} offline=${capabilities.offline}"
    )

    val scope = engine.scope(Focus.Radius(GeoPoint(44.9778, -93.2650), 805.0))
    println("scope near downtown: zoom=${scope.cameraScope.zoom} bucket=${scope.renderScope.zoomBucket}")

    val outcome = runBlocking {
        engine.planJourney(
            TripPlanRequest(
                origin = GeoPoint(44.9778, -93.2650),
                destination = GeoPoint(44.9900, -93.2650),
                departAt = null,
                arriveBy = null,
                maxTransfers = 1,
            )
        )
    }
    when (outcome) {
        is JourneyOutcome.Planned -> println("planned ${outcome.journeys.size} journeys")
        is JourneyOutcome.Unavailable -> println("routing unavailable: ${outcome.reason}")
    }
}