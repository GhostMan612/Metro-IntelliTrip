package com.intellitrip.map.web

import com.intellitrip.map.BasemapStyle
import com.intellitrip.map.GeoJsonWriter
import com.intellitrip.map.RenderSnapshot

/**
 * Pure bridge between the render pipeline and any map widget.
 *
 * Renderers consume [RenderSnapshot] and push bulk source updates the same
 * way Android pushes to MapLibre (`atlas-map-android`). The widget itself is a
 * thin `wasmJsMain` actual; everything here is portable and JVM-testable.
 */

/** One GeoJSON source payload for [WebLayer]. */
data class SourcePayload(
    val layer: WebLayer,
    val geoJson: String,
)

enum class WebLayer {
    VEHICLES,
    CLUSTERS,
    STOPS,
    SHAPES,
}

/**
 * Converts a snapshot to source payloads. Empty layers are omitted so the
 * widget never sets empty data (MapLibre would otherwise flash).
 */
fun snapshotToSources(snapshot: RenderSnapshot): List<SourcePayload> {
    val out = mutableListOf<SourcePayload>()
    if (snapshot.vehicles.isNotEmpty()) out += SourcePayload(WebLayer.VEHICLES, GeoJsonWriter.vehicles(snapshot))
    if (snapshot.clusters.isNotEmpty()) out += SourcePayload(WebLayer.CLUSTERS, GeoJsonWriter.clusters(snapshot))
    if (snapshot.stops.isNotEmpty()) out += SourcePayload(WebLayer.STOPS, GeoJsonWriter.stops(snapshot))
    if (snapshot.shapeLines.isNotEmpty()) out += SourcePayload(WebLayer.SHAPES, GeoJsonWriter.shapes(snapshot))
    return out
}

/** Style URL to hand the widget (null = bundled asset, unsupported on web). */
fun webStyleUrl(style: BasemapStyle): String? = style.styleUrl

/**
 * Renderer contract for the browser. Mirrors `com.intellitrip.map.MapRenderer`
 * but is widget-oriented (the wasmJs actual mounts a div). Android implements
 * its own actual against the same pipeline output instead.
 */
interface WebRenderer {
    fun mount(containerId: String)
    fun setStyle(style: BasemapStyle)
    fun setSources(payloads: List<SourcePayload>, centerLon: Double, centerLat: Double, zoom: Double)
    fun destroy()
}
