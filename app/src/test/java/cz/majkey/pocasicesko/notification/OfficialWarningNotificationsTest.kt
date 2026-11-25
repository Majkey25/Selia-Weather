package cz.majkey.pocasicesko.notification

import cz.majkey.pocasicesko.data.CzechLocation
import cz.majkey.pocasicesko.data.LocationMode
import cz.majkey.pocasicesko.data.WeatherWarning
import cz.majkey.pocasicesko.data.WeatherWarningSeverity
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfficialWarningNotificationsTest {
    @Test
    fun oldChecksCannotClearNewerWarningsAndOrderingResetsForAnotherPlace() {
        assertFalse(acceptWarningCheck("Prague", 200, "Prague", 100, 300))
        assertFalse(acceptWarningCheck("Prague", 200, "Prague", 200, 300))
        assertTrue(acceptWarningCheck("Prague", 200, "Prague", 201, 300))
        assertTrue(acceptWarningCheck("Prague", 200, "Brno", 100, 300))
    }

    @Test
    fun correctedDeviceClockDoesNotBlockNewWarningChecks() {
        assertTrue(acceptWarningCheck("Prague", 900, "Prague", 200, 300))
        assertFalse(acceptWarningCheck("Prague", 200, "Prague", 900, 300))
    }

    @Test
    fun unchangedLocalAdviceDoesNotRepeatWhenNationalBulletinIdsChange() {
        val now = Instant.parse("2026-09-22T12:00:00Z")
        val place = CzechLocation("Prague", "", 50.0755, 14.4378, "CZ")
        val warning = WeatherWarning("bulletin1", "ČHMÚ", "Heavy rain", "", "Avoid flood water",
            WeatherWarningSeverity.SEVERE, now, now.plusSeconds(3600), "https://vystrahy-cr.chmi.cz/")
        val first = officialWarningKey(warning)
        assertEquals(first, officialWarningKey(warning.copy(id = "bulletin2")))
        assertEquals(first, officialWarningKey(warning.copy(
            description = "Updated background details", instruction = "Avoid\n flood water",
        )))
        assertNotEquals(first, officialWarningKey(warning.copy(severity = WeatherWarningSeverity.EXTREME)))
        assertNotEquals(first, officialWarningKey(warning.copy(instruction = "Avoid all travel")))
        assertNotEquals(first, officialWarningKey(warning.copy(expires = now.plusSeconds(7200))))
        assertNotEquals(officialWarningScope(place, LocationMode.FIXED),
            officialWarningScope(place.copy(latitude = 49.2), LocationMode.FIXED))
    }

    @Test
    fun followedWarningsKeepTheirIdentityAcrossGpsFixes() {
        val place = CzechLocation("Prague", "", 50.0755, 14.4378, "CZ")
        val nextFix = place.copy(latitude = 50.0756, longitude = 14.4380)
        assertEquals(officialWarningScope(place, LocationMode.FOLLOW_DEVICE),
            officialWarningScope(nextFix, LocationMode.FOLLOW_DEVICE))
    }
}
