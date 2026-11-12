package com.intellitrip

import com.intellitrip.domain.Focus
import com.intellitrip.domain.GeoPoint
import com.intellitrip.domain.UserLocation
import com.intellitrip.scope.ScopeEngine
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class ScopePresetsTest {

    private val now = Instant.parse("2026-11-11T12:00:00Z")
    private val mapCenter = GeoPoint(44.9500, -93.2000)
    private val rider = GeoPoint(44.9800, -93.3000)

    private fun stateWith(
        focus: Focus? = Focus.System,
        fix: UserLocation? = null,
        scopeCenter: GeoPoint = mapCenter,
    ): AppState = AppState(
        focus = focus,
        scope = ScopeEngine().currentScope(Focus.Radius(scopeCenter, 2_000.0)),
        userLocation = fix,
    )

    @Test
    fun freshLocationWinsOverTheMapView() {
        val fix = UserLocation(rider, 12.0, now - 10.seconds)
        val resolution = stateWith(focus = Focus.Radius(mapCenter, 1_000.0), fix = fix)
            .presetResolution(ScopePreset.NEARBY, now)
        assertEquals(ScopeAnchor.YOUR_LOCATION, resolution.anchor)
        val focus = resolution.focus as Focus.Radius
        assertEquals(rider, focus.center)
        assertEquals(805.0, focus.meters, 0.001)
    }

    @Test
    fun staleLocationNeverMovesTheCamera() {
        val stale = UserLocation(rider, 12.0, now - 10.minutes)
        val resolution = stateWith(focus = Focus.Radius(mapCenter, 1_000.0), fix = stale)
            .presetResolution(ScopePreset.AREA, now)
        assertEquals(ScopeAnchor.MAP_VIEW, resolution.anchor)
        assertEquals(mapCenter, (resolution.focus as Focus.Radius).center)
    }

    @Test
    fun viewportFocusAnchorsThePresetWithoutALocationFix() {
        val resolution = stateWith(focus = Focus.Radius(mapCenter, 1_000.0))
            .presetResolution(ScopePreset.NEARBY, now)
        assertEquals(ScopeAnchor.MAP_VIEW, resolution.anchor)
        assertEquals(mapCenter, (resolution.focus as Focus.Radius).center)
    }

    @Test
    fun cameraCentreIsUsedWhenTheFocusCarriesNoPoint() {
        val resolution = stateWith(focus = Focus.System, scopeCenter = mapCenter)
            .presetResolution(ScopePreset.AREA, now)
        assertEquals(ScopeAnchor.MAP_VIEW, resolution.anchor)
        assertEquals(mapCenter, (resolution.focus as Focus.Radius).center)
    }

    @Test
    fun labelNamesTheAnchorSoTheControlIsNotMysterious() {
        val resolution = PresetResolution(
            focus = Focus.Radius(mapCenter, 805.0),
            anchor = ScopeAnchor.YOUR_LOCATION,
        )
        assertEquals("Nearby · your location", resolution.label(ScopePreset.NEARBY))
    }
}
