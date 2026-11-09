package com.intellitrip.domain

data class GeoPoint(val lat: Double, val lon: Double) {
    init {
        require(lat in -90.0..90.0)
        require(lon in -180.0..180.0)
    }
}

data class LatLngBounds(val southWest: GeoPoint, val northEast: GeoPoint) {
    init {
        require(southWest.lat <= northEast.lat)
    }

    fun contains(point: GeoPoint): Boolean =
        point.lat in southWest.lat..northEast.lat && point.lon in southWest.lon..northEast.lon
}
