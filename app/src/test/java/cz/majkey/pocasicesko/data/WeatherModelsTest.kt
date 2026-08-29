package cz.majkey.pocasicesko.data

import org.junit.Assert.assertEquals
import org.junit.Test

class WeatherModelsTest {
    @Test
    fun selectsCurrentDayInsidePastAndFutureDays() {
        val past = day("2026-08-27")
        val today = day("2026-08-28")
        val future = day("2026-08-29")
        val snapshot = snapshot(listOf(past, today, future), "2026-08-28T12:00")

        assertEquals(today, snapshot.currentDay())
    }

    @Test
    fun fallsBackToFirstDayWhenProviderOmitsCurrentDate() {
        val first = day("2026-08-29")
        val snapshot = snapshot(listOf(first), "2026-08-28T12:00")

        assertEquals(first, snapshot.currentDay())
    }

    private fun snapshot(days: List<DailyWeather>, currentTime: String) = WeatherSnapshot(
        timezone = "Europe/Prague",
        current = CurrentWeather(
            time = currentTime,
            temperature = 20.0,
            feelsLike = 20.0,
            humidity = 50,
            precipitation = 0.0,
            weatherCode = 0,
            cloudCover = 0,
            pressure = 1_015.0,
            windSpeed = 5.0,
            windDirection = 0,
            windGusts = 8.0,
            isDay = true,
        ),
        hourly = emptyList(),
        daily = days,
        updatedAtEpochMillis = 0L,
    )

    private fun day(date: String) = DailyWeather(
        date = date,
        weatherCode = 0,
        temperatureMax = 22.0,
        temperatureMin = 12.0,
        sunrise = "${date}T06:00",
        sunset = "${date}T20:00",
        precipitationSum = 0.0,
        precipitationProbability = 0,
        windSpeedMax = 10.0,
    )
}
