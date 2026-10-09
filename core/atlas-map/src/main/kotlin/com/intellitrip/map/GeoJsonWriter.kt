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

    fun shapes(snapshot: RenderSnapshot): String = buildString {
        append("{\"type\":\"FeatureCollection\",\"features\":[")
        snapshot.shapeLines.forEachIndexed { index, line ->
            if (index > 0) append(',')
            append("{\"type\":\"Feature\",\"geometry\":{\"type\":\"LineString\",\"coordinates\":[")
            line.coordinates.forEachIndexed { pointIndex, point ->
                if (pointIndex > 0) append(',')
                append('[').append(point[0]).append(',').append(point[1]).append(']')
            }
            append("]},\"properties\":{\"id\":").append(quote(line.id)).append("}}")
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
