package cz.majkey.pocasicesko.ui

import cz.majkey.pocasicesko.data.HourlyWeather
import java.time.Instant
import java.util.Locale
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

    @Test
    fun formatsFullDayInSelectedLocale() {
        assertEquals("Monday, August 24, 2026", formatFullDay("2026-08-24", Locale.US))
        assertEquals("pondělí 24. srpna 2026", formatFullDay("2026-08-24", Locale.forLanguageTag("cs-CZ")))
    }

    @Test
    fun formatsUpdatedAtInSelectedLocale() {
        val epochMillis = Instant.parse("2026-08-24T10:00:00Z").toEpochMilli()

        assertEquals("8/24/26, 12:00 PM", formatUpdatedAt(epochMillis, Locale.US))
        assertEquals("24.08.26 12:00", formatUpdatedAt(epochMillis, Locale.forLanguageTag("cs-CZ")))
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
