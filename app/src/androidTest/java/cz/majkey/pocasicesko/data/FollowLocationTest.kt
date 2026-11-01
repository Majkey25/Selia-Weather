package cz.majkey.pocasicesko.data

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CancellationException

class FollowLocationTest {
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private val context = object : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int) =
            base.getSharedPreferences("follow_location_test_$name", mode)
    }
    private val preferences = context.getSharedPreferences(WeatherRepository.PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val prague = CzechLocation("Praha", REGION_PRAGUE, 50.0755, 14.4378, "CZ")
    private val brno = CzechLocation("Brno", REGION_SOUTH_MORAVIAN, 49.1951, 16.6068, "CZ")

    @Before
    @After
    fun clearTestData() {
        check(base.packageName.endsWith(".debug"))
        assertTrue(preferences.edit().clear().commit())
    }

    @Test
    fun fixedIsDefaultAndReadingLegacyRegionDoesNotTurnFollowingOff() {
        val repository = WeatherRepository(context)
        assertEquals(LocationMode.FIXED, repository.locationMode())
        repository.selectCurrentFix(fix(prague), followDevice = true)
        preferences.edit().putString("location_region", "Hlavní město Praha").commit()
        val restored = WeatherRepository(context)
        assertEquals(prague, restored.lastLocation())
        assertEquals(LocationMode.FOLLOW_DEVICE, restored.locationMode())
        assertTrue(requireNotNull(restored.lastDeviceLocationTimeMillis()) > 0)
        restored.selectLocation(brno)
        assertEquals(LocationMode.FIXED, WeatherRepository(context).locationMode())
        assertEquals(brno, WeatherRepository(context).lastLocation())
        assertNull(restored.lastDeviceLocationTimeMillis())
    }

    @Test
    fun fixedModeDoesNotAskForDeviceLocationAndExplicitOneShotRemainsFixed() = runBlocking {
        val repository = WeatherRepository(context)
        repository.selectCurrentFix(fix(prague), followDevice = false)
        assertEquals(prague, repository.refreshFollowedLocation { error("Fixed place must not request device location") })
        assertEquals(LocationMode.FIXED, repository.locationMode())
    }

    @Test
    fun choosingFollowWithNoFixPersistsIntentAndFailedRefreshCannotPublishTheOldPlace() {
        val repository = WeatherRepository(context)
        repository.selectLocation(prague)
        val previousRevision = repository.locationSelectionRevision()
        assertTrue(preferences.edit().putLong("device_location_time", 1).putLong("device_location_elapsed_nanos", 1)
            .putInt("device_location_boot_count", 0).commit())
        val revision = repository.beginFollowingLocation()
        assertTrue(revision > previousRevision)
        assertEquals(revision, repository.locationSelectionRevision())
        assertFalse(preferences.contains("device_location_time"))
        assertFalse(preferences.contains("device_location_elapsed_nanos"))
        assertFalse(preferences.contains("device_location_boot_count"))
        val restored = WeatherRepository(context)
        assertEquals(LocationMode.FOLLOW_DEVICE, restored.locationMode())
        assertEquals(prague, restored.lastLocation())
        assertNull(restored.lastDeviceLocationTimeMillis())
        var published = false
        assertFalse(restored.publishForCurrentLocation(prague, requireBackgroundPermission = true) { published = true })
        for (failure in listOf(LocationPermissionException(), SystemLocationDisabledException(), LocationTimeoutException())) {
            assertThrows(failure.javaClass) {
                runBlocking { restored.refreshFollowedLocation { throw failure } }
            }
            val afterFailure = WeatherRepository(context)
            assertEquals(LocationMode.FOLLOW_DEVICE, afterFailure.locationMode())
            assertEquals(prague, afterFailure.lastLocation())
            assertNull(afterFailure.lastDeviceLocationTimeMillis())
            assertFalse(afterFailure.publishForCurrentLocation(prague, requireBackgroundPermission = true) { published = true })
        }
        assertFalse(published)
        restored.selectLocation(brno)
        assertEquals(LocationMode.FIXED, WeatherRepository(context).locationMode())
    }

    @Test
    fun manualSelectionRejectsAnEarlierInitialFollowCommitEvenAtTheSameCoordinates() {
        val repository = WeatherRepository(context)
        for (manualLocation in listOf(prague, brno)) {
            repository.selectLocation(prague)
            val revision = repository.locationSelectionRevision()
            WeatherRepository(context).selectLocation(manualLocation)
            val selected = preferences.all
            assertThrows(CancellationException::class.java) {
                repository.selectCurrentFix(fix(prague), followDevice = true, expectedSelectionRevision = revision)
            }
            assertEquals(selected, preferences.all)
            assertEquals(manualLocation, repository.lastLocation())
            assertEquals(LocationMode.FIXED, repository.locationMode())
        }
    }

    @Test
    fun unchangedSelectionAllowsInitialFollowCommit() {
        val repository = WeatherRepository(context)
        repository.selectLocation(prague)
        repository.selectCurrentFix(fix(brno), followDevice = true,
            expectedSelectionRevision = repository.locationSelectionRevision())
        assertEquals(brno, repository.lastLocation())
        assertEquals(LocationMode.FOLLOW_DEVICE, repository.locationMode())
    }

    @Test
    fun followRefreshPersistsOnlyTheLatestSelectedFix() = runBlocking {
        val repository = WeatherRepository(context)
        repository.selectCurrentFix(fix(prague), followDevice = true)
        assertEquals(brno, repository.refreshFollowedLocation { fix(brno) })
        val restored = WeatherRepository(context)
        assertEquals(brno, restored.lastLocation())
        assertEquals(LocationMode.FOLLOW_DEVICE, restored.locationMode())
    }

    @Test
    fun manualSelectionFromAnotherRepositoryWinsOverAnInFlightDeviceLookup() = runBlocking {
        val repository = WeatherRepository(context)
        repository.selectCurrentFix(fix(prague), followDevice = true)
        val started = CompletableDeferred<Unit>()
        val result = CompletableDeferred<DeviceLocationFix>()
        val pending = async {
            repository.refreshFollowedLocation {
                started.complete(Unit)
                result.await()
            }
        }
        started.await()
        WeatherRepository(context).selectLocation(prague)
        result.complete(fix(brno))
        pending.join()
        assertTrue(pending.isCancelled)
        assertEquals(prague, repository.lastLocation())
        assertEquals(LocationMode.FIXED, repository.locationMode())
    }

    @Test
    fun canceledLookupCannotPersistItsResultOrLeaveOldFixVerified() = runBlocking {
        val repository = WeatherRepository(context)
        repository.selectCurrentFix(fix(prague), followDevice = true)
        val started = CompletableDeferred<Unit>()
        val pending = async {
            repository.refreshFollowedLocation {
                started.complete(Unit)
                awaitCancellation()
            }
        }
        started.await()
        pending.cancelAndJoin()
        assertEquals(prague, repository.lastLocation())
        assertEquals(LocationMode.FOLLOW_DEVICE, repository.locationMode())
        assertFalse(preferences.contains("device_location_boot_count"))
    }

    @Test
    fun anEarlierLookupCannotOverwriteANewerCompletedFollowRefresh() = runBlocking {
        val repository = WeatherRepository(context)
        repository.selectCurrentFix(fix(prague), followDevice = true)
        val started = CompletableDeferred<Unit>()
        val result = CompletableDeferred<DeviceLocationFix>()
        val earlier = async {
            repository.refreshFollowedLocation {
                started.complete(Unit)
                result.await()
            }
        }
        started.await()
        assertEquals(brno, WeatherRepository(context).refreshFollowedLocation { fix(brno) })
        result.complete(fix(prague))
        earlier.join()
        assertTrue(earlier.isCancelled)
        assertEquals(brno, repository.lastLocation())
        assertEquals(LocationMode.FOLLOW_DEVICE, repository.locationMode())
    }

    @Test
    fun permissionDisabledAndTimeoutFailuresNeverChangeToFixedOrReturnThePreviousPlace() {
        val repository = WeatherRepository(context)
        for (failure in listOf(LocationPermissionException(), SystemLocationDisabledException(), LocationTimeoutException())) {
            repository.selectCurrentFix(fix(prague), followDevice = true)
            assertThrows(failure.javaClass) {
                runBlocking { repository.refreshFollowedLocation { throw failure } }
            }
            assertEquals(prague, repository.lastLocation())
            assertEquals(LocationMode.FOLLOW_DEVICE, repository.locationMode())
            assertFalse(preferences.contains("device_location_boot_count"))
        }
    }

    @Test
    fun staleFixCannotEnableFollowingOrOverwriteAFixedPlace() {
        val repository = WeatherRepository(context)
        repository.selectLocation(prague)
        assertThrows(LocationTimeoutException::class.java) {
            repository.selectCurrentFix(fix(brno).copy(updatedAtEpochMillis = System.currentTimeMillis() - 300_000), true)
        }
        assertEquals(prague, repository.lastLocation())
        assertEquals(LocationMode.FIXED, repository.locationMode())
    }

    @Test
    fun followNotificationGateRejectsPermissionLossAndFixedGateRejectsAnotherPlace() {
        val denied = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun checkPermission(permission: String, pid: Int, uid: Int): Int = PackageManager.PERMISSION_DENIED
        }
        val repository = WeatherRepository(denied)
        repository.selectCurrentFix(fix(prague), followDevice = true)
        var published = false
        assertFalse(repository.publishForCurrentLocation(prague, requireBackgroundPermission = true) { published = true })
        assertFalse(published)
        repository.selectLocation(prague)
        assertFalse(repository.publishForCurrentLocation(brno) { published = true })
        assertTrue(repository.publishForCurrentLocation(prague) { published = true })
        assertTrue(published)
    }

    private fun fix(location: CzechLocation) = DeviceLocationFix(
        location, System.currentTimeMillis(), SystemClock.elapsedRealtimeNanos(),
    )
}
