package cz.majkey.pocasicesko.ui

import cz.majkey.pocasicesko.data.HourlyWeather
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HourlyDetailsTest {
    @Test
    fun opensClosesAndSwitchesHours() {
        assertEquals("2026-08-30T12:00", toggleExpandedHour(null, "2026-08-30T12:00"))
        assertNull(toggleExpandedHour("2026-08-30T12:00", "2026-08-30T12:00"))
        assertEquals(
            "2026-08-30T13:00",
            toggleExpandedHour("2026-08-30T12:00", "2026-08-30T13:00"),
        )
    }

    @Test
    fun rejectsBlankHourKey() {
        assertThrows(IllegalArgumentException::class.java) {
            toggleExpandedHour(null, " ")
        }
    }

    @Test
    fun optionalMetricsAppearOnlyWhenPresent() {
        val kinds = availableHourMetricKinds(
            hour(apparentTemperature = 18.0, uvIndex = null),
        )

        assertTrue(HourMetricKind.FEELS_LIKE in kinds)
        assertTrue(HourMetricKind.PRECIPITATION in kinds)
        assertTrue(HourMetricKind.HUMIDITY in kinds)
        assertTrue(HourMetricKind.PRESSURE in kinds)
        assertFalse(HourMetricKind.UV in kinds)
        assertFalse(HourMetricKind.VISIBILITY in kinds)
    }

    @Test
    fun forecastScreenUsesExpandableHourlyRows() {
        val source = File(
            System.getProperty("user.dir"),
            "src/main/java/cz/majkey/pocasicesko/ui/ForecastScreen.kt",
        ).readText()

        assertTrue(source.contains("ExpandedHourDetails("))
        assertTrue(source.contains("stateDescription"))
        assertTrue(source.contains(".heightIn(min = 78.dp)"))
        assertFalse(source.contains(".height(78.dp)"))
    }

    private fun hour(
        apparentTemperature: Double?,
        uvIndex: Double?,
    ) = HourlyWeather(
        time = "2026-08-30T12:00",
        temperature = 20.0,
        humidity = 50,
        precipitationProbability = 30,
        precipitation = 0.2,
        weatherCode = 1,
        pressure = 1_015.0,
        windSpeed = 10.0,
        windDirection = 225,
        isDay = true,
        apparentTemperature = apparentTemperature,
        uvIndex = uvIndex,
    )
}
