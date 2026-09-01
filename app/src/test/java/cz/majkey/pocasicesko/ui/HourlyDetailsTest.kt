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

        assertTrue(HourMetricKind.FEELS_LIKE in availableHourMetricKinds(
            hour(apparentTemperature = null, uvIndex = null),
        ))
    }

    @Test
    fun apparentTemperatureFallsBackToMeasuredTemperature() {
        assertEquals(18.0, hourlyApparentTemperature(hour(18.0, null)), 0.0)
        assertEquals(20.0, hourlyApparentTemperature(hour(null, null)), 0.0)
    }

    @Test
    fun explainsHourlyRainInHumanTerms() {
        val dry = hour(null, null).copy(precipitation = 0.0)

        assertEquals(HourlyRainLevel.NONE, hourlyRainLevel(dry.copy(precipitationProbability = 0)))
        assertEquals(HourlyRainLevel.UNLIKELY, hourlyRainLevel(dry.copy(precipitationProbability = 20)))
        assertEquals(HourlyRainLevel.POSSIBLE, hourlyRainLevel(dry.copy(precipitationProbability = 50)))
        assertEquals(HourlyRainLevel.LIKELY, hourlyRainLevel(dry.copy(precipitationProbability = 80)))
        assertEquals(HourlyRainLevel.HEAVY, hourlyRainLevel(dry.copy(precipitation = 6.0)))
    }

    @Test
    fun exposesEveryAvailableDetailedMetric() {
        val kinds = availableHourMetricKinds(
            hour(18.0, 5.0).copy(
                dewPoint = 12.0,
                wetBulbTemperature = 15.0,
                rain = 0.2,
                snowfall = 0.3,
                surfacePressure = 990.0,
                cape = 400.0,
                freezingLevelHeightMeters = 2_100.0,
                soilTemperature0Cm = 14.0,
                soilMoisture0To1Cm = 0.2,
            ),
        )

        listOf(
            HourMetricKind.TEMPERATURE,
            HourMetricKind.DEW_POINT,
            HourMetricKind.WET_BULB,
            HourMetricKind.RAIN,
            HourMetricKind.SNOWFALL,
            HourMetricKind.SURFACE_PRESSURE,
            HourMetricKind.CAPE,
            HourMetricKind.FREEZING_LEVEL,
            HourMetricKind.SOIL_TEMPERATURE,
            HourMetricKind.SOIL_MOISTURE,
        ).forEach { assertTrue(it in kinds) }
    }

    @Test
    fun forecastScreenUsesExpandableHourlyRows() {
        val source = File(
            System.getProperty("user.dir"),
            "src/main/java/cz/majkey/pocasicesko/ui/ForecastScreen.kt",
        ).readText()

        assertTrue(source.contains("ExpandedHourDetails("))
        assertTrue(source.contains("hourlyApparentTemperature(hour)"))
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
