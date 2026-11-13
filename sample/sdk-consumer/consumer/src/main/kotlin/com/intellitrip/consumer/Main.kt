package com.intellitrip.consumer

import com.intellitrip.domain.FeedId
import com.intellitrip.domain.Focus
import com.intellitrip.domain.GeoPoint
import com.intellitrip.engine.AtlasEngine
import com.intellitrip.engine.AtlasProviders

/**
 * A deliberately small consumer that depends only on the published coordinates.
 *
 * This is not a demo of every capability. It is a boundary check: if the engine's
 * POM is incomplete, if a portable module leaks an Android or build-time
 * dependency, or if the public API requires a class that is not published, this
 * program fails to compile or run. It never touches the source projects.
 */
fun main() {
    val feedId = FeedId("consumer-feed")
    val engine = AtlasEngine(feedId, AtlasProviders())

    val capabilities = engine.capabilities()
    check(!capabilities.staticTransit) { "No providers were configured" }
    check(!capabilities.routing) { "No routing provider was configured" }

    val scope = engine.scope(Focus.Radius(GeoPoint(44.9778, -93.2650), 805.0))
    val zoom = scope.cameraScope.zoom ?: error("Scope did not resolve a camera zoom")
    check(zoom > 0.0) { "Scope zoom must be positive" }
    val bucket = scope.renderScope.zoomBucket

    println(
        "Atlas SDK consumer OK: feed=${engine.feedId.value} " +
            "zoom=$zoom bucket=$bucket",
    )
}
