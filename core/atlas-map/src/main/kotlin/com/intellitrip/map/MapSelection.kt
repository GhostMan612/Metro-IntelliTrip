package com.intellitrip.map

import com.intellitrip.domain.GeoPoint

/**
 * A feature under a tap on the map.
 *
 * The map is the primary surface in this app, so tapping what a rider can see is not
 * optional. Selection is modeled as a closed set for three reasons: the UI must render
 * a different sheet per kind, the plan's selected journey competes with network
 * features for the same tap, and an exhaustive type is what lets an unexpected feature
 * be a compile error rather than a blank sheet at runtime.
 */
sealed interface MapFeature {

    /** Stable identifier, used to tell a fresh selection from the current one. */
    val id: String

    /**
     * A scheduled stop.
     *
     * [distanceMeters] is computed when the tap is resolved so a list of nearby stops
     * can be offered; it is not part of identity.
     */
    data class Stop(
        val feedId: String,
        val stopId: String,
        val name: String,
    ) : MapFeature {
        /** Feed-qualified key, so stop "1" on two agencies never collide. */
        val key: String get() = "$feedId/$stopId"

        override val id: String get() = "stop:$key"
    }

    /** A live vehicle position. */
    data class Vehicle(
        val vehicleId: String,
        val routeShortName: String?,
        val headsign: String?,
        val position: GeoPoint,
        val updatedAt: java.time.Instant,
    ) : MapFeature {
        override val id: String get() = "vehicle:$vehicleId"
    }

    /**
     * A planned journey drawn on the map.
     *
     * [journeyId] matches the planner's selection id, so tapping the drawn route and
     * tapping its result card are the same selection.
     */
    data class Journey(
        val journeyId: String,
    ) : MapFeature {
        override val id: String get() = "journey:$journeyId"
    }

/**
 * A group of vehicles merged into one marker at this zoom.
 *
 * These are *vehicle* clusters: the render pipeline buckets vehicles into grid cells
 * before drawing them. An earlier revision modelled this as a stop cluster, which
 * offered a rider "N stops here" over what was actually N buses, and then looked for a
 * property name the writer never emitted, so the count was always zero.
 *
 * There is no automatic expansion: expanding on tap fights the rider's own pinch
 * gesture and cascades on an accidental double tap. The action offered is to zoom in,
 * which is what actually separates the vehicles.
 */
data class VehicleCluster(
        val clusterId: String,
        val vehicleCount: Int,
        /** Cluster centre, so "zoom in" can actually go somewhere. */
        val centroidLat: Double,
        val centroidLon: Double,
    ) : MapFeature {
        override val id: String get() = "cluster:$clusterId"
    }
}

/**
 * What a tap resolved to, including the case where it resolved to nothing.
 *
 * [Ambiguous] is separate from [None] on purpose. Several features under one point is
 * a normal state for a dense downtown, and silently choosing one of them is how a
 * rider ends up looking at the wrong bus. Offering the candidates is the honest answer
 * and gives the accessibility path something to list.
 */
sealed interface MapSelection {
    data object None : MapSelection

    data class Single(val feature: MapFeature) : MapSelection

    data class Ambiguous(val features: List<MapFeature>) : MapSelection
}

/**
 * Converts map-rendered properties into typed features.
 *
 * Kept separate from MapLibre so the selection rules are testable without a map, a
 * GL context or a device. The renderer supplies raw properties; everything that decides
 * what those properties *mean* lives here.
 */
object MapSelectionResolver {

    /** Layer draw order, smallest first, so the topmost feature wins a tie. */
    private val PRIORITY: Map<MapFeatureKind, Int> = mapOf(
        MapFeatureKind.JOURNEY to 0,
        MapFeatureKind.VEHICLE to 1,
        MapFeatureKind.STOP to 2,
        MapFeatureKind.CLUSTER to 3,
    )

    enum class MapFeatureKind { JOURNEY, VEHICLE, STOP, CLUSTER }

    /**
     * Picks the best feature, or reports the tie.
     *
     * [properties] are the merged properties of one queried feature, keyed by the
     * property names the render pipeline writes.
     */
    fun resolve(
        properties: Map<String, Any?>,
        layerKind: MapFeatureKind,
    ): MapFeature? {
        val id = properties["id"]?.toString()?.takeIf { it.isNotBlank() } ?: return null
        return when (layerKind) {
            MapFeatureKind.JOURNEY -> MapFeature.Journey(id)

            MapFeatureKind.CLUSTER -> MapFeature.VehicleCluster(
                clusterId = id,
                // The writer emits `count`. Reading `point_count` here always yielded
                // null, so every cluster was reported as containing zero vehicles.
                vehicleCount = (properties["count"] as? Number)?.toInt() ?: 0,
                // GeoJSON coordinates are [lon, lat]; these properties carry the
                // cluster centre in the same order.
                centroidLon = (properties["lon"] as? Number)?.toDouble() ?: 0.0,
                centroidLat = (properties["lat"] as? Number)?.toDouble() ?: 0.0,
            )

            MapFeatureKind.STOP -> MapFeature.Stop(
                feedId = properties["feed"]?.toString().orEmpty(),
                stopId = id,
                name = properties["name"]?.toString() ?: "Stop",
            )

            MapFeatureKind.VEHICLE -> MapFeature.Vehicle(
                vehicleId = id,
                routeShortName = properties["route"]?.toString(),
                headsign = properties["headsign"]?.toString(),
                position = GeoPoint(
                    lat = (properties["lat"] as? Number)?.toDouble() ?: 0.0,
                    lon = (properties["lon"] as? Number)?.toDouble() ?: 0.0,
                ),
                updatedAt = parseTimestamp(properties["updated"]),
            )
        }
    }

    /**
     * Collapses several queried features into one selection.
     *
     * Features of the same kind collapse to the topmost one, because two stops under
     * one finger is not a choice a rider can act on. Different kinds are genuinely
     * ambiguous and are surfaced as candidates.
     */
    fun collapse(features: List<MapFeature>): MapSelection {
        if (features.isEmpty()) return MapSelection.None
        if (features.size == 1) return MapSelection.Single(features.first())
        val byKind = features.groupBy { kindOf(it) }
        if (byKind.size > 1) {
            return MapSelection.Ambiguous(features.sortedBy { PRIORITY[kindOf(it)] ?: Int.MAX_VALUE })
        }
        return MapSelection.Single(features.minByOrNull { PRIORITY[kindOf(it)] ?: Int.MAX_VALUE }!!)
    }

    private fun kindOf(feature: MapFeature): MapFeatureKind = when (feature) {
        is MapFeature.Journey -> MapFeatureKind.JOURNEY
        is MapFeature.Vehicle -> MapFeatureKind.VEHICLE
        is MapFeature.Stop -> MapFeatureKind.STOP
        is MapFeature.VehicleCluster -> MapFeatureKind.CLUSTER
    }

    private fun parseTimestamp(value: Any?): java.time.Instant =
        runCatching { java.time.Instant.parse(value?.toString()) }
            .getOrElse { java.time.Instant.EPOCH }
}