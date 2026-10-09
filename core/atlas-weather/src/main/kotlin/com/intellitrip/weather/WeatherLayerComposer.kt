package com.intellitrip.weather

import com.intellitrip.domain.RadarFrame
import com.intellitrip.domain.WeatherAlert

/**
 * Decides which weather layers are visible and in what order they compose with
 * transit layers. The default UX stays simple: weather is opt-in and never
 * obscures the transit network.
 */
data class WeatherLayerState(
    val radarEnabled: Boolean = false,
    val alertsEnabled: Boolean = false,
    val radarOpacity: Double = 0.6,
)

sealed interface WeatherLayer {
    data class RadarTiles(val frame: RadarFrame, val opacity: Double) : WeatherLayer

    data class AlertMarkers(val alerts: List<WeatherAlert>) : WeatherLayer
}

/** Layer order mirrors the documented composition: basemap -> weather -> transit. */
enum class LayerSlot(val order: Int) {
    BASEMAP(0),
    RADAR(1),
    ALERTS(2),
    TRANSIT_SHAPES(3),
    TRANSIT_STOPS(4),
    TRANSIT_VEHICLES(5),
}

data class ComposedLayers(val layers: List<Pair<LayerSlot, WeatherLayer>>) {
    fun slotOf(layer: WeatherLayer): LayerSlot? = layers.firstOrNull { it.second === layer }?.first
}

object WeatherLayerComposer {

    fun compose(state: WeatherLayerState, radarFrame: RadarFrame?, alerts: List<WeatherAlert>): ComposedLayers {
        val layers = mutableListOf<Pair<LayerSlot, WeatherLayer>>()
        if (state.radarEnabled && radarFrame != null) {
            layers += LayerSlot.RADAR to WeatherLayer.RadarTiles(radarFrame, state.radarOpacity)
        }
        if (state.alertsEnabled && alerts.isNotEmpty()) {
            layers += LayerSlot.ALERTS to WeatherLayer.AlertMarkers(alerts)
        }
        return ComposedLayers(layers.sortedBy { it.first.order })
    }

    /**
     * Degradation contract: when a weather provider is unavailable the weather
     * layers are dropped and transit layers remain fully usable.
     */
    fun composeDegraded(reason: String): ComposedLayers = ComposedLayers(emptyList())
}