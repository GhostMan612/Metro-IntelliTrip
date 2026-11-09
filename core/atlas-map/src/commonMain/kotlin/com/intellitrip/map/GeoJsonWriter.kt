package com.intellitrip.map

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

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
            // Emitted as 0/1 numbers: MapLibre `match` only branches on
            // numbers/strings, so booleans are rejected at runtime.
            append(",\"stale\":").append(if (vehicle.stale) 1 else 0)
            append(",\"interpolated\":").append(if (vehicle.interpolated) 1 else 0)
            append(",\"bearing\":").append(vehicle.bearing ?: "null")
            // GTFS route_color is RRGGBB without '#'; emit #RRGGBB or omit so the
            // renderer falls back to its default blue.
            val c = vehicle.routeColor?.trim()?.removePrefix("#")
            if (c != null && c.length == 6) append(",\"routeColor\":").append(quote("#$c"))
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
            // lat/lon are duplicated into properties as well as the geometry. Hit
            // testing reads properties, and "zoom in to separate these vehicles" needs
            // a centre to go to; reading it back out of the geometry blob would mean
            // re-parsing coordinates in the selection layer.
            append(",\"count\":").append(cluster.count)
            append(",\"lat\":").append(cluster.lat)
            append(",\"lon\":").append(cluster.lon)
            append("}}")
        }
        append("]}")
    }

    fun stops(snapshot: RenderSnapshot): String = buildString {
        append("{\"type\":\"FeatureCollection\",\"features\":[")
        snapshot.stops.forEachIndexed { index, stop ->
            if (index > 0) append(',')
            append("{\"type\":\"Feature\",\"geometry\":{\"type\":\"Point\",\"coordinates\":[")
            append(stop.lon).append(',').append(stop.lat)
            append("]},\"properties\":{\"id\":").append(quote(stop.key))
            // The feed id travels with the stop because stop ids are only unique
            // within a feed, and selection looks the tapped stop back up by this key.
            append(",\"feed\":").append(quote(stop.feedId))
            append(",\"name\":").append(quote(stop.name)).append("}}")
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
            append("]},\"properties\":{\"id\":").append(quote(line.id))
            val c = line.routeColor?.trim()?.removePrefix("#")
            if (c != null && c.length == 6) append(",\"routeColor\":").append(quote("#$c"))
            append("}}")
        }
        append("]}")
    }

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
            // 0/1 numbers: MapLibre match branches only on numbers/strings.
            append(""","fresh":""").append(if (location.isFresh()) 1 else 0)
            // Emitted separately from "fresh": the renderer uses high accuracy to size
            // the halo and soften the "you are here" claim, not to decide whether the
            // point is shown at all.
            append(""","precise":""").append(location.isHighAccuracy())
            append("}}}]}")
        }
    }

    fun routes(lines: List<RouteLine>): String = buildString {
        append("{\"type\":\"FeatureCollection\",\"features\":[")
        lines.forEachIndexed { index, line ->
            if (index > 0) append(',')
            append("{\"type\":\"Feature\",\"geometry\":{\"type\":\"LineString\",\"coordinates\":[")
            line.coordinates.forEachIndexed { pointIndex, point ->
                if (pointIndex > 0) append(',')
                append('[').append(point[0]).append(',').append(point[1]).append(']')
            }
            append("]},\"properties\":{\"id\":").append(quote(line.id))
            append(",\"kind\":").append(quote(line.kind)).append("}}")
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
    private val minimumInterval: Duration = 250.milliseconds,
    private val clock: Clock = Clock.System,
) {

    private var lastUpdate: Instant? = null

    fun shouldEmit(now: Instant = clock.now()): Boolean {
        val last = lastUpdate
        if (last == null || now - last >= minimumInterval) {
            lastUpdate = now
            return true
        }
        return false
    }

    fun reset() {
        lastUpdate = null
    }
}
