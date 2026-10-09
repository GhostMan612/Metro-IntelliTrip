package com.intellitrip.offline

/**
 * Basemap availability.
 *
 * The renderer and the basemap resource are separate concerns: MapLibre renders,
 * CARTO or an offline package supplies tiles. When the offline package is not
 * installed the app must say so instead of silently showing a blank canvas.
 */
enum class BasemapAvailability {
    /** Online style reachable (CARTO or equivalent). */
    OnlineOnly,

    /** Offline vector tile package installed and usable. */
    OfflineAvailable,

    /** Offline package expected but not installed. */
    OfflineOnlyMissing,
}

/**
 * Chooses which basemap resource to request. Policy is explicit so a missing
 * offline package degrades loudly instead of silently.
 */
class BasemapPolicy(
    private val onlineEnabled: Boolean = true,
    private val offlineInstalled: Boolean = false,
) {

    fun availability(): BasemapAvailability = when {
        offlineInstalled -> BasemapAvailability.OfflineAvailable
        onlineEnabled -> BasemapAvailability.OnlineOnly
        else -> BasemapAvailability.OfflineOnlyMissing
    }

    fun preference(): BasemapPreference = when (availability()) {
        BasemapAvailability.OfflineAvailable -> BasemapPreference.OfflinePackage
        BasemapAvailability.OnlineOnly -> BasemapPreference.Online
        BasemapAvailability.OfflineOnlyMissing -> BasemapPreference.None
    }
}

enum class BasemapPreference { OfflinePackage, Online, None }