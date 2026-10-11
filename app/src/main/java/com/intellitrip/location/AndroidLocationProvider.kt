package com.intellitrip.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import androidx.core.content.ContextCompat
import com.intellitrip.contracts.LocationProvider
import com.intellitrip.domain.GeoPoint
import com.intellitrip.domain.UserLocation
import java.time.Instant
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Device location via the platform [LocationManager].
 *
 * Uses the framework API rather than Play Services' fused provider: fused requires
 * the Google Play Services dependency and a device that ships it, while
 * LocationManager works on any Android device including de-Googled ones. For a
 * transit app, a coarse GPS fix is entirely sufficient and this keeps the SDK
 * free of Google dependencies.
 */
class AndroidLocationProvider(context: Context) : LocationProvider {

    private val appContext = context.applicationContext
    private val manager =
        appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    /**
     * Fine is required for GPS. Coarse is a genuinely different capability: it
     * permits the network provider, which is enough to place a rider on the map
     * but not to give street-level precision. Reporting coarse as "no permission"
     * made a rider who declined precise location see an authorized UI and then
     * never get a position.
     */
    override fun hasPermission(): Boolean = hasFinePermission() || hasCoarsePermission()

    private fun hasFinePermission(): Boolean =
        manager != null && ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED

    private fun hasCoarsePermission(): Boolean =
        manager != null && ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED



    @SuppressLint("MissingPermission")
    override fun positions(): Flow<UserLocation> = callbackFlow {
        val locationManager = manager
        if (locationManager == null || !hasPermission()) {
            close()
            return@callbackFlow
        }

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                trySend(location.toUserLocation())
            }

            // Required on API < 30; deprecated overrides kept so older devices still
            // receive updates rather than silently producing an empty flow.
            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) = Unit
            override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit
        }

        // Deliberately NOT seeding from getLastKnownLocation. Indoors that returns a
        // fix from days ago, and a user is better served by nothing than by a stale
        // dot that looks identical to a live one. Live updates arrive once GPS warms.
        runCatching { bestLastKnown() }
            .getOrNull()
            ?.takeIf { System.currentTimeMillis() - it.time <= LAST_KNOWN_MAX_AGE_MS }
            ?.let { trySend(it.toUserLocation()) }

        // Provider choice respects what was actually granted. GPS needs fine; the network
        // provider works on coarse alone. Offering GPS to a coarse-only rider would
        // throw a SecurityException at request time and kill the flow.
        val gpsAllowed = hasFinePermission() &&
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        val networkAllowed = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        val provider = when {
            gpsAllowed -> LocationManager.GPS_PROVIDER
            networkAllowed -> LocationManager.NETWORK_PROVIDER
            else -> null
        }
        if (provider == null) {
            close()
            return@callbackFlow
        }

        val intervalMs = MIN_INTERVAL_MS
        val minDistanceM = MIN_DISTANCE_M
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            locationManager.requestLocationUpdates(
                provider,
                intervalMs,
                minDistanceM,
                listener,
                Looper.getMainLooper(),
            )
        } else {
            @Suppress("DEPRECATION")
            locationManager.requestLocationUpdates(provider, intervalMs, minDistanceM, listener, Looper.getMainLooper())
        }

        awaitClose { locationManager.removeUpdates(listener) }
    }

    @SuppressLint("MissingPermission")
    private fun bestLastKnown(): Location? =
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .mapNotNull { provider -> runCatching { manager?.getLastKnownLocation(provider) }.getOrNull() }
            .maxByOrNull { it.time }

    private fun Location.toUserLocation() = UserLocation(
        position = GeoPoint(latitude, longitude),
        // Below ~5 m the reported accuracy is noise rather than measurement.
        accuracyMeters = if (hasAccuracy()) {
            accuracy.toDouble().coerceAtLeast(MIN_REPORTED_ACCURACY_M)
        } else {
            DEFAULT_ACCURACY_M
        },
        timestamp = if (time > 0) Instant.ofEpochMilli(time) else Instant.now(),
    )

    private companion object {
        /** 5 s: fast enough to feel live, slow enough not to spin the GPS radio. */
        const val MIN_INTERVAL_MS = 5_000L
        const val MIN_DISTANCE_M = 5f
        const val MIN_REPORTED_ACCURACY_M = 5.0
        const val DEFAULT_ACCURACY_M = 50.0

        /** Older than this and the "last known" fix is not worth showing. */
        const val LAST_KNOWN_MAX_AGE_MS = 2 * 60 * 1000L
    }
}