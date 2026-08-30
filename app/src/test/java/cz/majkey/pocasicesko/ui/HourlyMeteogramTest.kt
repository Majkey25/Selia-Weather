package cz.majkey.pocasicesko.ui

import cz.majkey.pocasicesko.data.HourlyWeather
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HourlyMeteogramTest {
    @Test
    fun mapsTwentyFourHoursToColumnCentres() {
        val geometry = calculateHourlyMeteogram(
            hours = (0 until 24).map(::hour),
            width = 1_632f,
            height = 112f,
            columnWidth = 68f,
        )

        assertEquals(24, geometry.hours.size)
        assertEquals(34f, geometry.hours.first().centerX, 0.001f)
        assertEquals(1_598f, geometry.hours.last().centerX, 0.001f)
    }

    @Test
    fun keepsConstantTemperatureFiniteAndDryBarsAtZero() {
        val geometry = calculateHourlyMeteogram(
            hours = listOf(hour(0), hour(1)),
            width = 136f,
            height = 112f,
            columnWidth = 68f,
        )

        assertTrue(geometry.hours.all { it.temperatureY.isFinite() })
        assertTrue(geometry.hours.all { it.precipitationHeight == 0f })
    }

    @Test
    fun mapsRainProbabilityAndDaylightWithoutLeavingBounds() {
        val geometry = calculateHourlyMeteogram(
            hours = listOf(
                hour(0, precipitation = 0.5, probability = 20, isDay = true),
                hour(1, precipitation = 2.0, probability = 100, isDay = false),
            ),
            width = 136f,
            height = 112f,
            columnWidth = 68f,
        )

        assertEquals(8.4f, geometry.hours.first().precipitationHeight, 0.001f)
        assertEquals(33.6f, geometry.hours.last().precipitationHeight, 0.001f)
        assertEquals(0.44f, geometry.hours.first().precipitationAlpha, 0.001f)
        assertEquals(1f, geometry.hours.last().precipitationAlpha, 0.001f)
        assertTrue(geometry.hours.first().isDay)
        assertTrue(!geometry.hours.last().isDay)
    }

    @Test
    fun rejectsInvalidDimensions() {
        assertThrows(IllegalArgumentException::class.java) {
            calculateHourlyMeteogram(listOf(hour(0)), 0f, 112f, 68f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            calculateHourlyMeteogram(listOf(hour(0)), 68f, Float.NaN, 68f)
        }
    }

    @Test
    fun forecastPanelUsesCombinedMeteogram() {
        val source = File(
            System.getProperty("user.dir"),
            "src/main/java/cz/majkey/pocasicesko/ui/ForecastScreen.kt",
        ).readText()

        assertTrue(source.contains("HourlyMeteogram("))
        assertFalse(source.contains("HourlyTemperatureLine("))
    }

    @Test
    fun formatsOneAccessibleDescriptionPerHour() {
        assertEquals(
            "14:00, Rain, 18°, precipitation 70%, 1.2 mm, wind 20 km/h, southwest",
            hourlyAccessibilityDescription(
                time = "14:00",
                condition = "Rain",
                temperature = "18°",
                precipitationLabel = "precipitation 70%, 1.2 mm",
                windLabel = "wind 20 km/h, southwest",
            ),
        )
    }

    @Test
    fun rotatesWindArrowTowardTravelDirection() {
        assertEquals(180f, windArrowRotation(0), 0.001f)
        assertEquals(270f, windArrowRotation(90), 0.001f)
        assertEquals(179f, windArrowRotation(-1), 0.001f)
    }

    private fun hour(
        index: Int,
        precipitation: Double = 0.0,
        probability: Int = 0,
        isDay: Boolean = true,
    ) = HourlyWeather(
        time = "2026-08-30T%02d:00".format(index),
        temperature = 20.0,
        humidity = 50,
        precipitationProbability = probability,
        precipitation = precipitation,
        weatherCode = 0,
        pressure = 1_015.0,
        windSpeed = 5.0,
        windDirection = 0,
        isDay = isDay,
    )
}
