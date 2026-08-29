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
import cz.majkey.pocasicesko.R
import cz.majkey.pocasicesko.locale.AppLocale
import java.io.IOException
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

internal enum class DeviceLocationProvider {
    NETWORK,
    GPS,
}

internal fun locationProviderOrder(
    coarseGranted: Boolean,
    fineGranted: Boolean,
    networkEnabled: Boolean,
    gpsEnabled: Boolean,
): List<DeviceLocationProvider> = buildList {
    if (!coarseGranted && !fineGranted) return@buildList
    if (networkEnabled) add(DeviceLocationProvider.NETWORK)
    if (fineGranted && gpsEnabled) add(DeviceLocationProvider.GPS)
}

class DeviceLocationRepository(context: Context) {
    private val appContext = context.applicationContext
    private val locationManager = appContext.getSystemService(LocationManager::class.java)

    suspend fun currentLocation(): CzechLocation {
        if (!hasLocationPermission()) throw LocationPermissionException()
        val location = recentLastKnownLocation() ?: requestSingleLocation()
        return withContext(Dispatchers.IO) { resolveName(location) }
    }

    private fun hasLocationPermission(): Boolean = coarsePermissionGranted() || finePermissionGranted()

    private fun coarsePermissionGranted(): Boolean = ContextCompat.checkSelfPermission(
        appContext,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED

    private fun finePermissionGranted(): Boolean = ContextCompat.checkSelfPermission(
        appContext,
        Manifest.permission.ACCESS_FINE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun recentLastKnownLocation(): Location? = enabledProviders()
        .mapNotNull { provider ->
            runCatching { locationManager.getLastKnownLocation(provider.systemName) }.getOrNull()
        }
        .filter { System.currentTimeMillis() - it.time <= MAX_LAST_KNOWN_AGE_MILLIS }
        .maxByOrNull { it.time }

    private suspend fun requestSingleLocation(): Location = withTimeout(LOCATION_TIMEOUT_MILLIS) {
        suspendCancellableCoroutine { continuation ->
            val providers = enabledProviders()
            if (providers.isEmpty()) {
                continuation.resumeWithException(SystemLocationDisabledException())
                return@suspendCancellableCoroutine
            }
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    runCatching { locationManager.removeUpdates(this) }
                    if (continuation.isActive) continuation.resume(location)
                }

                override fun onProviderDisabled(provider: String) = Unit
                override fun onProviderEnabled(provider: String) = Unit
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
            }
            var registered = false
            providers.forEach { provider ->
                try {
                    // Android 10 fallback; getCurrentLocation starts at API 30.
                    locationManager.requestSingleUpdate(
                        provider.systemName,
                        listener,
                        Looper.getMainLooper(),
                    )
                    registered = true
                } catch (_: SecurityException) {
                    Unit
                }
            }
            if (!registered) {
                continuation.resumeWithException(LocationPermissionException())
                return@suspendCancellableCoroutine
            }
            continuation.invokeOnCancellation {
                runCatching { locationManager.removeUpdates(listener) }
            }
        }
    }

    private fun enabledProviders(): List<DeviceLocationProvider> = locationProviderOrder(
        coarseGranted = coarsePermissionGranted(),
        fineGranted = finePermissionGranted(),
        networkEnabled = providerEnabled(LocationManager.NETWORK_PROVIDER),
        gpsEnabled = providerEnabled(LocationManager.GPS_PROVIDER),
    )

    private fun providerEnabled(provider: String): Boolean =
        runCatching { locationManager.isProviderEnabled(provider) }.getOrDefault(false)

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
            ?: address?.countryName
            ?: "${location.latitude.formatCoordinate()}, ${location.longitude.formatCoordinate()}"
        val countryCode = address?.countryCode?.uppercase(Locale.ROOT)
        val region = if (countryCode == "CZ") {
            regionKeyForAdminArea(adminArea)
        } else {
            adminArea ?: address?.subAdminArea ?: address?.countryName ?: REGION_WORLD
        }
        return CzechLocation(name, region, location.latitude, location.longitude, countryCode)
    }

    private fun regionKeyForAdminArea(adminArea: String?): String {
        val localized = AppLocale.localized(appContext)
        val labels = mapOf(
            localized.getString(R.string.location_region_prague) to REGION_PRAGUE,
            localized.getString(R.string.location_region_central_bohemia) to REGION_CENTRAL_BOHEMIA,
            localized.getString(R.string.location_region_south_bohemian) to REGION_SOUTH_BOHEMIAN,
            localized.getString(R.string.location_region_plzen) to REGION_PLZEN,
            localized.getString(R.string.location_region_karlovy_vary) to REGION_KARLOVY_VARY,
            localized.getString(R.string.location_region_usti_nad_labem) to REGION_USTI_NAD_LABEM,
            localized.getString(R.string.location_region_liberec) to REGION_LIBEREC,
            localized.getString(R.string.location_region_hradec_kralove) to REGION_HRADEC_KRALOVE,
            localized.getString(R.string.location_region_pardubice) to REGION_PARDUBICE,
            localized.getString(R.string.location_region_vysocina) to REGION_VYSOCINA,
            localized.getString(R.string.location_region_south_moravian) to REGION_SOUTH_MORAVIAN,
            localized.getString(R.string.location_region_olomouc) to REGION_OLOMOUC,
            localized.getString(R.string.location_region_zlin) to REGION_ZLIN,
            localized.getString(R.string.location_region_moravian_silesian) to REGION_MORAVIAN_SILESIAN,
        )
        return labels.entries.firstOrNull { (label, _) -> label.equals(adminArea, ignoreCase = true) }?.value
            ?: normalizeRegionKey(adminArea.orEmpty())
    }

    companion object {
        private const val MAX_LAST_KNOWN_AGE_MILLIS = 30 * 60 * 1000L
        private const val LOCATION_TIMEOUT_MILLIS = 15_000L
    }
}

private val DeviceLocationProvider.systemName: String
    get() = when (this) {
        DeviceLocationProvider.NETWORK -> LocationManager.NETWORK_PROVIDER
        DeviceLocationProvider.GPS -> LocationManager.GPS_PROVIDER
    }

sealed class DeviceLocationException : IOException()

class LocationPermissionException : DeviceLocationException()

class SystemLocationDisabledException : DeviceLocationException()

private fun Double.formatCoordinate(): String = String.format(Locale.ROOT, "%.4f", this)
