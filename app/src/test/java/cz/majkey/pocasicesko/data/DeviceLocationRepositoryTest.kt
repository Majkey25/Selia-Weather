package cz.majkey.pocasicesko.data

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceLocationRepositoryTest {
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
