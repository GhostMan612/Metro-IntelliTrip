package com.intellitrip

import com.intellitrip.domain.GeoPoint
import com.intellitrip.domain.Vehicle
import com.intellitrip.domain.VehicleKey
import kotlinx.datetime.Instant

/**
 * The camera target for a followed vehicle, with an honest status line.
 *
 * Kept as a pure function so "what happens when the bus stops reporting?" is a
 * test, not a behaviour discovered on a phone. A missing or stale report clears
 * [target]: the map stays where it is instead of pretending the last position is
 * current or jumping to the vehicle's scheduled route.
 */
data class VehicleFollowUpdate(
    val target: GeoPoint?,
    val status: String,
    val live: Boolean,
)

object VehicleFollow {

    /**
     * Matches the departure board's live-report window.
     *
     * A vehicle older than this is no longer "live", so moving the camera to it
     * would assert a position the data does not support.
     */
    const val MAX_AGE_SECONDS: Long = 120L

    fun update(
        key: VehicleKey,
        vehicles: List<Vehicle>,
        now: Instant,
    ): VehicleFollowUpdate {
        val vehicle = vehicles.firstOrNull { it.key == key }
            ?: return VehicleFollowUpdate(
                target = null,
                status = "Waiting for vehicle ${key.vehicleId} to report",
                live = false,
            )

        val age = (now - vehicle.updatedAt).inWholeSeconds
        if (age > MAX_AGE_SECONDS) {
            val minutes = (age / 60).coerceAtLeast(1)
            return VehicleFollowUpdate(
                target = null,
                status = "Vehicle ${key.vehicleId} last reported ${minutes} min ago; follow paused",
                live = false,
            )
        }

        val suffix = if (age <= 0) "live" else "${age}s old"
        return VehicleFollowUpdate(
            target = vehicle.position,
            status = "Following ${key.vehicleId} ($suffix)",
            live = true,
        )
    }
}
