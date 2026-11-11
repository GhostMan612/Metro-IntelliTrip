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

/**
 * Radar tile overlay (e.g. RainViewer frames converted from `RadarFrame`).
 * Raster, not GeoJSON — the renderer adds it as a raster-tile source.
 */
data class RadarOverlay(
    /** XYZ tile URL template, e.g. `https://tilecache.../{z}/{x}/{y}.png`. */
    val tileUrlTemplate: String,
    val opacity: Float = 0.7f,
)

enum class WebLayer {
    VEHICLES,
    CLUSTERS,
    STOPS,
    SHAPES,
}

/**
 * Converts a snapshot to source payloads. Every layer is emitted, including an
 * empty FeatureCollection, so MapLibre clears features when a feed becomes empty
 * or the rider changes the scope. Omitting a layer leaves stale source data.
 */
fun snapshotToSources(snapshot: RenderSnapshot): List<SourcePayload> = listOf(
    SourcePayload(WebLayer.VEHICLES, GeoJsonWriter.vehicles(snapshot)),
    SourcePayload(WebLayer.CLUSTERS, GeoJsonWriter.clusters(snapshot)),
    SourcePayload(WebLayer.STOPS, GeoJsonWriter.stops(snapshot)),
    SourcePayload(WebLayer.SHAPES, GeoJsonWriter.shapes(snapshot)),
)

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
    /** Adds/removes the radar tile overlay (null removes it). */
    fun setRadarOverlay(overlay: RadarOverlay?)
    fun destroy()
}
