package cz.majkey.pocasicesko.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class LocationPermissionActionTest {
    @Test
    fun routesGrantedDeniedAndPermanentlyDeniedLocationPermission() {
        assertEquals(
            LocationPermissionAction.LOAD_LOCATION,
            locationPermissionAction(granted = true, permanentlyDenied = true),
        )
        assertEquals(
            LocationPermissionAction.REQUEST_PERMISSION,
            locationPermissionAction(granted = false, permanentlyDenied = false),
        )
        assertEquals(
            LocationPermissionAction.OPEN_SETTINGS,
            locationPermissionAction(granted = false, permanentlyDenied = true),
        )
    }
}
