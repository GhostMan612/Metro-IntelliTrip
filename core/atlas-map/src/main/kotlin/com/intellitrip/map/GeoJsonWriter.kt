package com.intellitrip.map

import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Minimal GeoJSON writer used to push bulk source updates to the map renderer.
 * One FeatureCollection per layer keeps the renderer GPU-side: no per-vehicle
 * view or Compose object is created.
 */
object GeoJsonWriter {

    fun vehicles(snapshot: RenderSnapshot): String = buildString {
        append("{\"type\":\"FeatureCollection\",\"features\":[")
        snapshot.vehicles.forEachIndexed { index, vehicle ->
            if (index > 0) append(',')
            append("{\"type\":\"Feature\",\"geometry\":{\"type\":\"Point\",\"coordinates\":[")
            append(vehicle.lon).append(',').append(vehicle.lat)
            append("]},\"properties\":{\"id\":").append(quote(vehicle.id))
            append(",\"type\":").append(quote(vehicle.vehicleType.name))
            append(",\"stale\":").append(vehicle.stale)
            append(",\"interpolated\":").append(vehicle.interpolated)
            append(",\"bearing\":").append(vehicle.bearing ?: "null")
            append("}}")
        }
        append("]}")
    }

    fun clusters(snapshot: RenderSnapshot): String = buildString {
        append("{\"type\":\"FeatureCollection\",\"features\":[")
        snapshot.clusters.forEachIndexed { index, cluster ->
            if (index > 0) append(',')
            append("{\"type\":\"Feature\",\"geometry\":{\"type\":\"Point\",\"coordinates\":[")
            append(cluster.lon).append(',').append(cluster.lat)
            append("]},\"properties\":{\"id\":").append(quote(cluster.id))
            append(",\"count\":").append(cluster.count).append("}}")
        }
        append("]}")
    }

    fun stops(snapshot: RenderSnapshot): String = buildString {
        append("{\"type\":\"FeatureCollection\",\"features\":[")
        snapshot.stops.forEachIndexed { index, stop ->
            if (index > 0) append(',')
            append("{\"type\":\"Feature\",\"geometry\":{\"type\":\"Point\",\"coordinates\":[")
            append(stop.lon).append(',').append(stop.lat)
            append("]},\"properties\":{\"id\":").append(quote(stop.id)).append("}}")
        }
        append("]}")
    }

    fun shapes(snapshot: RenderSnapshot): String = lineStrings(snapshot.shapeLines.map { it.id to it.coordinates })

    /**
     * Planned journeys, drawn above the network. [RouteLine.id] is carried as a
     * property so a leg can be styled or highlighted independently.
     */
    /**
     * The user's position as a point carrying its accuracy radius. A null location
     * renders as an empty collection rather than being skipped, so clearing the
     * layer does not require the caller to track that separately.
     */
    fun userLocation(location: com.intellitrip.domain.UserLocation?): String {
        if (location == null) return """{"type":"FeatureCollection","features":[]}"""
        return buildString {
            append("""{"type":"FeatureCollection","features":[{"type":"Feature",""")
            append(""""geometry":{"type":"Point","coordinates":[""")
            append(location.position.lon).append(',').append(location.position.lat)
            append("""],"properties":{"accuracy":""").append(location.accuracyMeters)
            append(""","fresh":""").append(location.isPrecise()).append("}}}]}")
        }
    }

    fun routes(lines: List<RouteLine>): String =
        lineStrings(lines.map { line -> line.id to line.coordinates.map { listOf(it[0], it[1]) } })

    // ShapeLineRender is already `[lon, lat]` pairs, so it maps straight across.

    private fun lineStrings(lines: List<Pair<String, List<List<Double>>>>): String = buildString {
        append("{\"type\":\"FeatureCollection\",\"features\":[")
        lines.forEachIndexed { index, (id, coordinates) ->
            if (index > 0) append(',')
            append("{\"type\":\"Feature\",\"geometry\":{\"type\":\"LineString\",\"coordinates\":[")
            coordinates.forEachIndexed { pointIndex, point ->
                if (pointIndex > 0) append(',')
                append('[').append(point[0]).append(',').append(point[1]).append(']')
            }
            append("]},\"properties\":{\"id\":").append(quote(id)).append("}}")
        }
        append("]}")
    }

    private fun quote(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}

/**
 * Rate-limits source updates so realtime refreshes never redraw the map more
 * often than the renderer can present.
 */
class RenderUpdateThrottle(
    private val minimumInterval: Duration = Duration.ofMillis(250),
    private val clock: Clock = Clock.systemUTC(),
) {

    private var lastUpdate: Instant? = null

    fun shouldEmit(now: Instant = clock.instant()): Boolean {
        val last = lastUpdate
        if (last == null || Duration.between(last, now) >= minimumInterval) {
            lastUpdate = now
            return true
        }
        return false
    }

    fun reset() {
        lastUpdate = null
    }
}
