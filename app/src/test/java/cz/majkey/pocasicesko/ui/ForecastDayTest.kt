package cz.majkey.pocasicesko.ui

import cz.majkey.pocasicesko.data.HourlyWeather
import org.junit.Assert.assertEquals
import org.junit.Test

class ForecastDayTest {
    @Test
    fun selectsOnlyHoursFromRequestedDay() {
        val hours = listOf(
            hour("2026-08-24T23:00"),
            hour("2026-08-25T00:00"),
            hour("2026-08-25T23:00"),
            hour("2026-08-26T00:00"),
        )

        val selected = hourlyForDay(hours, "2026-08-25")

        assertEquals(listOf("2026-08-25T00:00", "2026-08-25T23:00"), selected.map { it.time })
    }

    private fun hour(time: String) = HourlyWeather(
        time = time,
        temperature = 20.0,
        humidity = 50,
        precipitationProbability = 0,
        precipitation = 0.0,
        weatherCode = 0,
        pressure = 1_015.0,
        windSpeed = 5.0,
        windDirection = 0,
        isDay = true,
    )
}
