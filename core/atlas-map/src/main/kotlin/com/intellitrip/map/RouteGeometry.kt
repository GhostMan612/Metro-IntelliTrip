package com.intellitrip.map

import com.intellitrip.domain.GeoPoint
import com.intellitrip.domain.JourneyOption
import com.intellitrip.domain.Leg
import com.intellitrip.domain.Stop
import com.intellitrip.domain.Trip

/**
 * Builds map geometry for a planned journey.
 *
 * Transit legs follow the trip's GTFS shape, trimmed to the portion actually ridden,
 * so the drawn line matches how the vehicle runs rather than cutting across the city.
 * Where a feed carries no shape the leg falls back to a straight connector between
 * its stops: an approximate line is useful, a missing leg is not.
 *
 * Pure and provider-neutral, so it is unit-testable without a renderer or a device.
 */
object RouteGeometry {

    fun build(
        journey: JourneyOption,
        shapes: List<com.intellitrip.domain.Shape>,
        stops: List<Stop>,
        trips: List<Trip>,
    ): List<RouteLine> {
        if (journey.legs.isEmpty()) return emptyList()
        val shapesByKey = shapes.associateBy { it.key }
        val stopsByKey = stops.associateBy { it.key }
        val tripsByKey = trips.associateBy { it.key }

        return journey.legs.mapIndexedNotNull { index, leg ->
            when (leg) {
                is Leg.WalkLeg -> line("walk-$index", listOf(leg.from, leg.to))

                is Leg.Transit -> {
                    val from = stopsByKey[leg.fromStopKey]?.location
                    val to = stopsByKey[leg.toStopKey]?.location
                    val shape = tripsByKey[leg.tripKey]?.shapeKey?.let { shapesByKey[it] }
                    line(
                        id = "${leg.tripKey.tripId}-$index",
                        points = shape?.points?.let { trimToSpan(it, from, to) } ?: listOfNotNull(from, to),
                    )
                }

                is Leg.Other -> line("other-$index", listOf(leg.from, leg.to))
            }
        }
    }

    /** GeoJSON needs two or more distinct positions; a zero-length line is invalid. */
    private fun line(id: String, points: List<GeoPoint>): RouteLine? {
        val distinct = points.distinctBy { "${it.lat},${it.lon}" }
        if (distinct.size < 2) return null
        return RouteLine(id, distinct.map { doubleArrayOf(it.lon, it.lat) })
    }

    /**
     * Keeps the part of [shape] between [from] and [to], in polyline order. Falls back
     * to the whole shape when either endpoint is unknown or ambiguous.
     */
    internal fun trimToSpan(shape: List<GeoPoint>, from: GeoPoint?, to: GeoPoint?): List<GeoPoint> {
        if (from == null || to == null || shape.size < 2) return shape
        val fromIndex = nearestIndex(shape, from)
        val toIndex = nearestIndex(shape, to)
        if (fromIndex < 0 || toIndex < 0 || fromIndex == toIndex) return shape
        return if (fromIndex <= toIndex) shape.subList(fromIndex, toIndex + 1)
        else shape.subList(toIndex, fromIndex + 1)
    }

    private fun nearestIndex(shape: List<GeoPoint>, target: GeoPoint): Int {
        var best = -1
        var bestDistance = Double.MAX_VALUE
        shape.forEachIndexed { index, point ->
            val dLat = point.lat - target.lat
            val dLon = point.lon - target.lon
            val distance = dLat * dLat + dLon * dLon
            if (distance < bestDistance) {
                bestDistance = distance
                best = index
            }
        }
        return best
    }
}
