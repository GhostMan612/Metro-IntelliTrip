package com.intellitrip.map

/**
 * Basemap resources are separate from the renderer. A basemap provider supplies
 * a style URL (or a local style asset); the renderer decides how to draw it.
 */
data class BasemapStyle(
    val styleUrl: String?,
    val styleAssetPath: String?,
    val attribution: String,
    val minZoom: Double = 0.0,
    val maxZoom: Double = 22.0,
)

interface BasemapProvider {
    val providerId: String
    fun style(): BasemapStyle
}

/** Bundled CARTO style reference used as the default development basemap. */
object CartoBasemapProvider : BasemapProvider {
    override val providerId: String = "carto"
    override fun style(): BasemapStyle = BasemapStyle(
        styleUrl = "https://basemaps.cartocdn.com/gl/positron-gl-style/style.json",
        styleAssetPath = null,
        attribution = "© OpenStreetMap contributors © CARTO",
    )
}

/** Placeholder for an offline vector tile package selected in a later phase. */
object OfflineBasemapProvider : BasemapProvider {
    override val providerId: String = "offline"
    override fun style(): BasemapStyle = BasemapStyle(
        styleUrl = null,
        styleAssetPath = "basemap/offline-style.json",
        attribution = "© OpenStreetMap contributors",
    )
}

/**
 * Renderer abstraction. The Android adapter implements this with MapLibre; the
 * core pipeline stays renderer-agnostic.
 */
interface MapRenderer {
    fun applyBasemap(style: BasemapStyle)
    fun render(snapshot: RenderSnapshot)
    fun destroy()
}
