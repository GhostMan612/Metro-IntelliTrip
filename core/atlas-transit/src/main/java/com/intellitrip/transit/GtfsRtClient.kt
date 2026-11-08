package com.intellitrip.transit

import com.google.transit.realtime.GtfsRealtime

class GtfsRtClient(private val fetchBytes: suspend (String) -> ByteArray) {
    suspend fun vehiclePositions(): List<VehicleLocation> {
        val bytes = fetchBytes("https://svc.metrotransit.org/mtgtfs/vehiclepositions.pb")
        val feed = GtfsRealtime.FeedMessage.parseFrom(bytes)
        return feed.entityList.mapNotNull { entity ->
            val pos = entity.vehicle?.position ?: return@mapNotNull null
            VehicleLocation(
                id = entity.vehicle.vehicle?.id ?: entity.id,
                routeId = entity.vehicle.trip?.routeId,
                lat = pos.latitude.toDouble(),
                lon = pos.longitude.toDouble(),
                bearing = if (pos.hasBearing()) pos.bearing.toDouble() else null,
                speed = if (pos.hasSpeed()) pos.speed.toDouble() else null,
            )
        }
    }
}
