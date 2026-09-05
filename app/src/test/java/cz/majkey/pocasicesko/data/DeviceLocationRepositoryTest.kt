package cz.majkey.pocasicesko.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceLocationRepositoryTest {
    @Test
    fun locationFreshnessUsesElapsedTimeAndIncludesThirtyMinuteBoundary() {
        val fixNanos = 60_000_000_000L
        val thirtyMinutesNanos = 1_800_000_000_000L
        assertTrue(isUsableDeviceLocation(50.0755, 14.4378, fixNanos, fixNanos))
        assertTrue(isUsableDeviceLocation(50.0755, 14.4378, fixNanos, fixNanos + thirtyMinutesNanos))
        assertFalse(isUsableDeviceLocation(50.0755, 14.4378, fixNanos, fixNanos + thirtyMinutesNanos + 1))
    }

    @Test
    fun rejectsFutureMissingAndNegativeElapsedTimestamps() {
        assertFalse(isUsableDeviceLocation(50.0, 14.0, 2_000L, 1_000L))
        assertFalse(isUsableDeviceLocation(50.0, 14.0, 0L, 1_000L))
        assertFalse(isUsableDeviceLocation(50.0, 14.0, -1L, 1_000L))
        assertFalse(isUsableDeviceLocation(50.0, 14.0, 1L, Long.MIN_VALUE))
    }

    @Test
    fun acceptsWorldwideBoundariesButRejectsInvalidProviderCoordinates() {
        listOf(0.0 to 0.0, 90.0 to 180.0, -90.0 to -180.0).forEach { (latitude, longitude) ->
            assertTrue(isUsableDeviceLocation(latitude, longitude, 1_000L, 2_000L))
        }
        listOf(
            Double.NaN to 14.0,
            50.0 to Double.NaN,
            Double.POSITIVE_INFINITY to 14.0,
            50.0 to Double.NEGATIVE_INFINITY,
            90.0001 to 14.0,
            -90.0001 to 14.0,
            50.0 to 180.0001,
            50.0 to -180.0001,
        ).forEach { (latitude, longitude) ->
            assertFalse(isUsableDeviceLocation(latitude, longitude, 1_000L, 2_000L))
        }
    }

    @Test
    fun approximatePermissionNeverRequestsGpsProvider() {
        assertEquals(
            listOf(DeviceLocationProvider.NETWORK),
            locationProviderOrder(
                coarseGranted = true,
                fineGranted = false,
                networkEnabled = true,
                gpsEnabled = true,
            ),
        )
    }

    @Test
    fun precisePermissionUsesEveryEnabledProviderWithNetworkFirst() {
        assertEquals(
            listOf(DeviceLocationProvider.NETWORK, DeviceLocationProvider.GPS),
            locationProviderOrder(
                coarseGranted = true,
                fineGranted = true,
                networkEnabled = true,
                gpsEnabled = true,
            ),
        )
        assertEquals(
            listOf(DeviceLocationProvider.GPS),
            locationProviderOrder(
                coarseGranted = false,
                fineGranted = true,
                networkEnabled = false,
                gpsEnabled = true,
            ),
        )
    }

    @Test
    fun missingPermissionOrDisabledProvidersReturnsNoProvider() {
        assertEquals(
            emptyList<DeviceLocationProvider>(),
            locationProviderOrder(false, false, true, true),
        )
        assertEquals(
            emptyList<DeviceLocationProvider>(),
            locationProviderOrder(true, true, false, false),
        )
    }
}
