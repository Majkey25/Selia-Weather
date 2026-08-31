package cz.majkey.pocasicesko.ui

import cz.majkey.pocasicesko.data.HourlyWeather
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherDetailScreenTest {
    @Test
    fun findsNextWetHourAndMaximumProbabilityInNextDay() {
        val hourly = listOf(
            hour("2026-08-29T19:00", probability = 0, precipitation = 0.0),
            hour("2026-08-29T20:00", probability = 30, precipitation = 0.2),
            hour("2026-08-29T21:00", probability = 80, precipitation = 0.5),
        )

        assertEquals("2026-08-29T20:00", nextWetHour(hourly, "2026-08-29T19:30")?.time)
        assertEquals(80, maximumPrecipitationProbability(hourly, "2026-08-29T19:30"))
        assertNull(nextWetHour(hourly.map { it.copy(precipitation = 0.0, precipitationProbability = 0) }, "2026-08-29T19:30"))
        assertNull(maximumPrecipitationProbability(emptyList(), "2026-08-29T19:30"))
    }

    @Test
    fun keepsTheApprovedDetailHierarchy() {
        val source = File(
            System.getProperty("user.dir"),
            "src/main/java/cz/majkey/pocasicesko/ui/WeatherDetailScreen.kt",
        ).readText()
        val markers = listOf(
            "AtAGlanceSection(",
            "LocalRainFieldSection(",
            "DetailSection(stringResource(R.string.current_details))",
            "DetailSection(stringResource(R.string.precipitation_and_clouds))",
            "DetailSection(stringResource(R.string.wind))",
            "DetailSection(stringResource(R.string.atmosphere))",
            "DetailSection(stringResource(R.string.ground))",
            "DetailSection(stringResource(R.string.sun))",
            "MoonSection(",
        )

        assertTrue(markers.zipWithNext().all { (first, second) -> source.indexOf(first) < source.indexOf(second) })
    }

    @Test
    fun calculationDetailsExposeCalibrationEvidence() {
        val source = File(
            System.getProperty("user.dir"),
            "src/main/java/cz/majkey/pocasicesko/ui/WeatherDetailScreen.kt",
        ).readText()

        assertTrue(source.contains("R.string.forecast_calculation_truth"))
        assertTrue(source.contains("R.string.forecast_calculation_artifact"))
        assertTrue(source.contains("R.string.forecast_calculation_weights"))
        assertTrue(source.contains("calculation.truthClass"))
        assertTrue(source.contains("calculation.artifactGeneratedAtEpochSeconds"))
        assertTrue(source.contains("calculation.weights"))
    }

    private fun hour(time: String, probability: Int, precipitation: Double) = HourlyWeather(
        time = time,
        temperature = 20.0,
        humidity = 50,
        precipitationProbability = probability,
        precipitation = precipitation,
        weatherCode = 0,
        pressure = 1_015.0,
        windSpeed = 5.0,
        windDirection = 180,
        isDay = true,
    )
}
