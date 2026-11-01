@file:Suppress("DEPRECATION")

package cz.majkey.pocasicesko.data

import android.Manifest
import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Process
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in native provider probe. Temporarily replaces system-wide network location. */
class FollowDeviceLocationProbe {
    @Test
    @SuppressLint("MissingPermission")
    fun nativeNetworkFixUpdatesThePersistedFollowLocation() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("followMockLocation") == "true")
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        check(base.packageName.endsWith(".debug"))
        check(Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
            "This opt-in probe requires the verified Android 10 provider restoration path"
        }
        for (permission in listOf(Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_BACKGROUND_LOCATION)) {
            check(ContextCompat.checkSelfPermission(base, permission) == PackageManager.PERMISSION_GRANTED) {
                "Grant $permission to the debug package before this opt-in probe"
            }
        }
        val appOps = base.getSystemService(AppOpsManager::class.java)
        check(appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_MOCK_LOCATION, Process.myUid(), base.opPackageName) ==
            AppOpsManager.MODE_ALLOWED) { "Allow mock_location for the debug package before this opt-in probe" }
        val manager = base.getSystemService(LocationManager::class.java)
        val provider = LocationManager.NETWORK_PROVIDER
        check(manager.isLocationEnabled && manager.isProviderEnabled(provider)) {
            "System network location must already be enabled before this opt-in probe"
        }
        val original = checkNotNull(manager.getProvider(provider))
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int) =
                base.getSharedPreferences("follow_native_probe_$name", mode)

            // Keep repository selection on the mock network provider; never query real GPS.
            override fun checkPermission(permission: String, pid: Int, uid: Int): Int =
                if (permission == Manifest.permission.ACCESS_FINE_LOCATION) PackageManager.PERMISSION_DENIED
                else super.checkPermission(permission, pid, uid)
        }
        val preferences = context.getSharedPreferences(WeatherRepository.PREFERENCES_NAME, Context.MODE_PRIVATE)
        var added = false
        try {
            assertTrue(preferences.edit().clear().commit())
            manager.addTestProvider(provider, false, false, false, false, false, false, false,
                Criteria.POWER_LOW, Criteria.ACCURACY_FINE)
            added = true
            manager.setTestProviderEnabled(provider, true)
            withTimeout(5_000) {
                while (!manager.isProviderEnabled(provider)) delay(25)
            }
            val prague = Location(provider).apply {
                latitude = 50.0755
                longitude = 14.4378
                accuracy = 10f
                time = System.currentTimeMillis()
                elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            }
            manager.setTestProviderLocation(provider, prague)
            withTimeout(5_000) {
                while (manager.getLastKnownLocation(provider)?.let {
                        it.isFromMockProvider && it.elapsedRealtimeNanos == prague.elapsedRealtimeNanos
                    } != true) delay(25)
            }
            val native = DeviceLocationRepository(context).currentFix(requireBackgroundPermission = true)
            assertTrue("Expected the public Prague fixture", native.location.latitude == prague.latitude &&
                native.location.longitude == prague.longitude)
            assertTrue(System.currentTimeMillis() - native.updatedAtEpochMillis in 0..MAX_FOLLOW_LOCATION_AGE_MILLIS)

            val repository = WeatherRepository(context)
            val previous = CzechLocation("Brno", REGION_SOUTH_MORAVIAN, 49.1951, 16.6068, "CZ")
            repository.selectCurrentFix(DeviceLocationFix(previous, System.currentTimeMillis(),
                SystemClock.elapsedRealtimeNanos()), followDevice = true)
            val followed = repository.refreshFollowedLocation(requireBackgroundPermission = true)
            assertTrue("Follow refresh must use the public Prague fixture", followed.latitude == prague.latitude &&
                followed.longitude == prague.longitude)
            val restored = WeatherRepository(context)
            assertEquals(followed, restored.lastLocation())
            assertEquals(LocationMode.FOLLOW_DEVICE, restored.locationMode())
            assertTrue(System.currentTimeMillis() - requireNotNull(restored.lastDeviceLocationTimeMillis()) in
                0..MAX_FOLLOW_LOCATION_AGE_MILLIS)
        } finally {
            try {
                if (added) {
                    manager.removeTestProvider(provider)
                    withTimeout(5_000) {
                        while (!manager.isProviderEnabled(provider)) delay(25)
                    }
                    val restored = checkNotNull(manager.getProvider(provider))
                    assertEquals(original.requiresNetwork(), restored.requiresNetwork())
                    assertEquals(original.requiresSatellite(), restored.requiresSatellite())
                    assertEquals(original.accuracy, restored.accuracy)
                }
            } finally {
                assertTrue(preferences.edit().clear().commit())
            }
        }
    }
}
