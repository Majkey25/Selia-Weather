package cz.majkey.pocasicesko.data

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StaticForecastInterpolationTest {
    @Test
    fun bilinearlyInterpolatesOneModelAtExactCoordinate() {
        val values = corners(10.0, 20.0, 30.0, 40.0)

        val result = interpolateStaticValues(values, GRID, 49.025, 14.025)

        assertEquals(1, result.size)
        assertEquals(25.0, requireNotNull(result.single().value), 1e-9)
        assertEquals(49.025, result.single().latitude, 1e-9)
        assertEquals(14.025, result.single().longitude, 1e-9)
    }

    @Test
    fun exactGridPointReturnsExactValue() {
        val result = interpolateStaticValues(corners(10.0, 20.0, 30.0, 40.0), GRID, 49.0, 14.0)

        assertEquals(10.0, requireNotNull(result.single().value), 0.0)
    }

    @Test
    fun missingCornerExcludesModelAndDirectionIsRejected() {
        assertTrue(interpolateStaticValues(corners(10.0, 20.0, 30.0, null), GRID, 49.025, 14.025).isEmpty())
        val direction = corners(10.0, 20.0, 30.0, 40.0).map {
            it.copy(variable = "wind_direction_10m", unit = "°")
        }

        val error = runCatching {
            interpolateStaticValues(direction, GRID, 49.025, 14.025)
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun skipsDirectionWithoutDroppingContinuousSeries() {
        val temperature = corners(10.0, 20.0, 30.0, 40.0)
        val direction = temperature.map { it.copy(variable = "wind_direction_10m", unit = "°") }

        val result = interpolateStaticValues(temperature + direction, GRID, 49.025, 14.025)

        assertEquals(listOf("temperature_2m"), result.map(StaticModelValue::variable))
    }

    private fun corners(
        southWest: Double?,
        southEast: Double?,
        northWest: Double?,
        northEast: Double?,
    ): List<StaticModelValue> = listOf(
        modelValue(49.0, 14.0, southWest),
        modelValue(49.0, 14.05, southEast),
        modelValue(49.05, 14.0, northWest),
        modelValue(49.05, 14.05, northEast),
    )

    private fun modelValue(latitude: Double, longitude: Double, value: Double?) = StaticModelValue(
        sourceId = "dwd-icon-eu",
        modelId = "dwd_icon_eu",
        runTime = Instant.parse("2026-08-29T12:00:00Z"),
        validTime = Instant.parse("2026-08-29T13:00:00Z"),
        latitude = latitude,
        longitude = longitude,
        elevationMeters = 250.0,
        variable = "temperature_2m",
        value = value,
        unit = "°C",
    )

    companion object {
        private val GRID = StaticFeedGrid(48.45, 51.2, 11.9, 19.0, 0.05, 0.5)
    }
}
