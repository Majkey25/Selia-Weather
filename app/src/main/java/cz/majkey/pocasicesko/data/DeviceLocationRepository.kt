@file:Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")

package cz.majkey.pocasicesko.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import cz.majkey.pocasicesko.locale.AppLocale
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

class DeviceLocationRepository(context: Context) {
    private val appContext = context.applicationContext
    private val locationManager = appContext.getSystemService(LocationManager::class.java)

    suspend fun currentLocation(): CzechLocation {
        if (!hasLocationPermission()) throw LocationPermissionException()
        val location = recentLastKnownLocation() ?: requestSingleLocation()
        if (location.latitude !in CZECH_LATITUDE || location.longitude !in CZECH_LONGITUDE) {
            throw LocationOutsideCzechiaException()
        }
        return withContext(Dispatchers.IO) { resolveName(location) }
    }

    private fun hasLocationPermission(): Boolean = ContextCompat.checkSelfPermission(
        appContext,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(
        appContext,
        Manifest.permission.ACCESS_FINE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun recentLastKnownLocation(): Location? = enabledProviders()
        .mapNotNull { provider -> runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull() }
        .filter { System.currentTimeMillis() - it.time <= MAX_LAST_KNOWN_AGE_MILLIS }
        .maxByOrNull { it.time }

    private suspend fun requestSingleLocation(): Location = withTimeout(LOCATION_TIMEOUT_MILLIS) {
        suspendCancellableCoroutine { continuation ->
            val provider = enabledProviders().firstOrNull()
            if (provider == null) {
                continuation.resumeWithException(SystemLocationDisabledException())
                return@suspendCancellableCoroutine
            }
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    locationManager.removeUpdates(this)
                    if (continuation.isActive) continuation.resume(location)
                }

                override fun onProviderDisabled(provider: String) = Unit
                override fun onProviderEnabled(provider: String) = Unit
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
            }
            try {
                // Android 10 fallback; getCurrentLocation starts at API 30.
                locationManager.requestSingleUpdate(provider, listener, Looper.getMainLooper())
                continuation.invokeOnCancellation { locationManager.removeUpdates(listener) }
            } catch (error: SecurityException) {
                continuation.resumeWithException(error)
            }
        }
    }

    private fun enabledProviders(): List<String> = listOf(
        LocationManager.GPS_PROVIDER,
        LocationManager.NETWORK_PROVIDER,
    ).filter { provider -> runCatching { locationManager.isProviderEnabled(provider) }.getOrDefault(false) }

    @Suppress("DEPRECATION")
    private fun resolveName(location: Location): CzechLocation {
        val address = if (Geocoder.isPresent()) {
            runCatching {
                Geocoder(appContext, AppLocale.locale(appContext))
                    .getFromLocation(location.latitude, location.longitude, 1)
                    ?.firstOrNull()
            }.getOrNull()
        } else {
            null
        }
        val adminArea = address?.adminArea
        val name = address?.locality
            ?: adminArea?.takeIf { it == "Hlavní město Praha" }?.let { "Praha" }
            ?: address?.subAdminArea
            ?: adminArea
            ?: "Czechia"
        val region = adminArea ?: REGION_CZECHIA
        return CzechLocation(name, region, location.latitude, location.longitude)
    }

    companion object {
        private val CZECH_LATITUDE = 48.45..51.2
        private val CZECH_LONGITUDE = 11.9..19.0
        private const val MAX_LAST_KNOWN_AGE_MILLIS = 30 * 60 * 1000L
        private const val LOCATION_TIMEOUT_MILLIS = 15_000L
    }
}

sealed class DeviceLocationException : IOException()

class LocationPermissionException : DeviceLocationException()

class LocationOutsideCzechiaException : DeviceLocationException()

class SystemLocationDisabledException : DeviceLocationException()
