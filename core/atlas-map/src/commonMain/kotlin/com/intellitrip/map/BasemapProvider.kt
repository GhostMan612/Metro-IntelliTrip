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
    /**
     * Inline style JSON, used by an installed offline package.
     *
     * A local style cannot be expressed as a URL without a bundled asset, and the
     * installed archive path is only known at runtime, so the host passes the
     * resolved JSON directly. When non-null this wins over [styleUrl].
     */
    val styleJson: String? = null,
)

interface BasemapProvider {
    val providerId: String
    fun style(): BasemapStyle
}

/** Bundled CARTO style reference used as the default development basemap. */
object CartoBasemapProvider : BasemapProvider {
    override val providerId: String = "carto"
    override fun style(): BasemapStyle = style(false)

    fun style(dark: Boolean): BasemapStyle = BasemapStyle(
        styleUrl = if (dark) {
            "https://basemaps.cartocdn.com/gl/dark-matter-gl-style/style.json"
        } else {
            "https://basemaps.cartocdn.com/gl/positron-gl-style/style.json"
        },
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
