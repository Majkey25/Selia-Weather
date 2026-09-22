package cz.majkey.pocasicesko.notification

import cz.majkey.pocasicesko.data.CzechLocation
import cz.majkey.pocasicesko.data.WeatherWarning
import cz.majkey.pocasicesko.data.WeatherWarningSeverity
import cz.majkey.pocasicesko.data.WeatherWarningsResult
import cz.majkey.pocasicesko.data.WeatherWarningsStatus
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
        val result = WeatherWarningsResult(WeatherWarningsStatus.AVAILABLE, listOf(warning), now, "ČHMÚ", warning.webUrl)
        val first = officialWarningFingerprint(place, result)
        assertEquals(first, officialWarningFingerprint(place, result.copy(warnings = listOf(warning.copy(id = "bulletin2")))))
        assertNotEquals(first, officialWarningFingerprint(place, result.copy(warnings = listOf(warning.copy(severity = WeatherWarningSeverity.EXTREME)))))
        assertNotEquals(first, officialWarningFingerprint(place.copy(latitude = 49.2), result))
    }
}
