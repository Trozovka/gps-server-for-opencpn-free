package com.trozovka.gpsserver.core.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.trozovka.gpsserver.core.nmea.GpsFix
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the GPS receiver reports while it searches: satellites heard and satellites used for the position. */
data class SatelliteStatus(val seen: Int, val used: Int)

/**
 * Positions from the phone's satellite receiver only. Wi-Fi and mobile network positions are never
 * used: a ship's router can be remembered in an earlier port. Location permission must already be
 * granted (MainActivity asks for it).
 */
@SuppressLint("MissingPermission")
class LocationSource(private val context: Context) {

    private val _fixes = MutableStateFlow<GpsFix?>(null)
    val fixes: StateFlow<GpsFix?> = _fixes.asStateFlow()

    private val _satellites = MutableStateFlow<SatelliteStatus?>(null)
    val satellites: StateFlow<SatelliteStatus?> = _satellites.asStateFlow()

    private val _gpsEnabled = MutableStateFlow(true)

    /** False when Location is switched off on the phone, or the phone has no GPS. */
    val gpsEnabled: StateFlow<Boolean> = _gpsEnabled.asStateFlow()

    private val locationManager by lazy {
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    }

    // All methods are written out: Android 8 to 10 calls them and has no default versions.
    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) = onNewLocation(location)
        override fun onProviderEnabled(provider: String) { _gpsEnabled.value = true }
        override fun onProviderDisabled(provider: String) { _gpsEnabled.value = false }

        @Deprecated("Only called by Android 8 and 9")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
    }

    private val gnssStatusCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            var used = 0
            for (i in 0 until status.satelliteCount) {
                if (status.usedInFix(i)) used++
            }
            _satellites.value = SatelliteStatus(status.satelliteCount, used)
        }
    }

    fun start() {
        try {
            locationManager.registerGnssStatusCallback(gnssStatusCallback, Handler(Looper.getMainLooper()))
        } catch (e: Exception) {
            Log.w(TAG, "Could not register GNSS status callback", e)
        }
        try {
            _gpsEnabled.value = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                INTERVAL_MILLIS,
                0f,
                listener,
                Looper.getMainLooper(),
            )
        } catch (e: Exception) {
            Log.e(TAG, "GPS not available", e)
            _gpsEnabled.value = false
        }
    }

    private fun onNewLocation(location: Location) {
        _fixes.value = GpsFix(
            timestampMillis = location.time,
            latitude = location.latitude,
            longitude = location.longitude,
            altitudeMeters = if (location.hasAltitude()) location.altitude else null,
            speedMetersPerSecond = if (location.hasSpeed()) location.speed else null,
            bearingDegrees = if (location.hasBearing()) location.bearing else null,
            satellitesUsed = _satellites.value?.used,
            measuredAtElapsedMillis = location.elapsedRealtimeNanos / 1_000_000,
        )
    }

    fun stop() {
        locationManager.removeUpdates(listener)
        locationManager.unregisterGnssStatusCallback(gnssStatusCallback)
    }

    companion object {
        private const val TAG = "LocationSource"
        private const val INTERVAL_MILLIS = 1000L
    }
}
