package com.intellitrip

import com.intellitrip.domain.Focus
import com.intellitrip.domain.GeoPoint
import kotlinx.datetime.Instant

/**
 * The two radius presets offered in the scope sheet.
 *
 * The numbers live here rather than in the composable so the anchor rule and the
 * radii can be tested without a device.
 */
enum class ScopePreset(val label: String, val radiusLabel: String, val meters: Double) {
    NEARBY("Nearby", "1/2 mile", 805.0),
    AREA("Area", "2 miles", 3_218.0),
}

/** Where a preset decided to centre, in rider language. */
enum class ScopeAnchor(val description: String) {
    YOUR_LOCATION("your location"),
    MAP_VIEW("map view"),
    SYSTEM("metro area"),
}

data class PresetResolution(
    val focus: Focus,
    val anchor: ScopeAnchor,
)

/**
 * Resolves a preset against the most local trustworthy anchor.
 *
 * Priority is explicit and ordered:
 * 1. A fresh location fix. A stale fix is shown as "last known" elsewhere but
 *    must never move the camera.
 * 2. The centre of the current radius or viewport scope, which is where the
 *    rider is actually looking.
 * 3. The centre of the current camera bounds.
 * 4. The system extent.
 *
 * The previous implementation hard-coded downtown Minneapolis for every preset.
 * A rider in St Paul who tapped "Nearby" was moved across the river, and the
 * viewport they had just positioned was ignored. No step here reintroduces a
 * fixed city coordinate.
 */
fun AppState.presetResolution(preset: ScopePreset, now: Instant): PresetResolution {
    val fix = userLocation
    if (fix != null && fix.shouldCenterCamera(now)) {
        return PresetResolution(
            focus = Focus.Radius(fix.position, preset.meters),
            anchor = ScopeAnchor.YOUR_LOCATION,
        )
    }

    val currentFocus = focus
    val fromFocus = when (currentFocus) {
        is Focus.Radius -> currentFocus.center
        is Focus.Viewport -> currentFocus.bounds.center()
        is Focus.Vehicle -> currentFocus.anchor
        else -> null
    }
    if (fromFocus != null) {
        return PresetResolution(
            focus = Focus.Radius(fromFocus, preset.meters),
            anchor = ScopeAnchor.MAP_VIEW,
        )
    }

    val fromCamera = scope.cameraScope.bounds?.center()
    if (fromCamera != null) {
        return PresetResolution(
            focus = Focus.Radius(fromCamera, preset.meters),
            anchor = ScopeAnchor.MAP_VIEW,
        )
    }

    return PresetResolution(focus = Focus.System, anchor = ScopeAnchor.SYSTEM)
}

/** A preset label that names its anchor, e.g. "Nearby · your location". */
fun PresetResolution.label(preset: ScopePreset): String =
    "${preset.label} · ${anchor.description}"
