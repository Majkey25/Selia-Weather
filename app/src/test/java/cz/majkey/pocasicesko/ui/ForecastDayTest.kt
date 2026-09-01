package cz.majkey.pocasicesko.ui

import cz.majkey.pocasicesko.R
import cz.majkey.pocasicesko.data.DailyWeather
import cz.majkey.pocasicesko.data.HourlyWeather
import cz.majkey.pocasicesko.units.MeasurementSystem
import cz.majkey.pocasicesko.units.WeatherUnitFormatter
import java.io.File
import java.time.Instant
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForecastDayTest {
    @Test
    fun selectsTwentyFourHoursAcrossMidnight() {
        val hours = (0..23).map { hour("2026-08-24T%02d:00".format(it)) } +
            (0..23).map { hour("2026-08-25T%02d:00".format(it)) }

        val selected = upcomingHours(hours, "2026-08-24T03")

        assertEquals(24, selected.size)
        assertEquals("2026-08-24T03:00", selected.first().time)
        assertEquals("2026-08-25T02:00", selected.last().time)
    }

    @Test
    fun keepsShortFuturePayloadWithoutFabricatingHours() {
        val hours = listOf(hour("2026-08-24T22:00"), hour("2026-08-24T23:00"))

        val selected = upcomingHours(hours, "2026-08-24T22")

        assertEquals(listOf("2026-08-24T22:00", "2026-08-24T23:00"), selected.map { it.time })
    }

    @Test
    fun dayPagesStopAtForecastBoundaries() {
        val days = listOf(day("2026-08-24"), day("2026-08-25"))

        assertEquals(days[0], dayForPage(days, 0))
        assertEquals(days[1], dayForPage(days, 1))
        assertEquals(null, dayForPage(days, -1))
        assertEquals(null, dayForPage(days, 2))
    }

    @Test
    fun dayPagerKeepsRightSwipeForNextDay() {
        val source = File(System.getProperty("user.dir"), "src/main/java/cz/majkey/pocasicesko/ui/ForecastScreen.kt")
            .readText()

        assertTrue(source.contains("reverseLayout = true"))
    }

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
    fun ordersAllHoursForNormalDay() {
        val hours = (0..23)
            .map { hour("2026-08-25T%02d:00".format(it)) }
            .reversed()

        val selected = hourlyForDay(hours, "2026-08-25")

        assertEquals(24, selected.size)
        assertEquals("2026-08-25T00:00", selected.first().time)
        assertEquals("2026-08-25T23:00", selected.last().time)
    }

    @Test
    fun preservesTwentyThreeAndTwentyFiveHourDstDays() {
        val springHours = (0..23)
            .filter { it != 2 }
            .map { hour("2026-03-29T%02d:00".format(it)) }
        val fallHours = (0..23).map { hour("2026-10-25T%02d:00".format(it)) } +
            hour("2026-10-25T02:00")

        assertEquals(23, hourlyForDay(springHours, "2026-03-29").size)
        assertEquals(25, hourlyForDay(fallHours, "2026-10-25").size)
    }

    @Test
    fun dailyPrecipitationSummaryShowsProbabilityAndAmount() {
        val units = WeatherUnitFormatter(MeasurementSystem.METRIC, Locale.US)
        val rainyDay = day("2026-08-25").copy(
            precipitationSum = 1.2,
            precipitationProbability = 70,
        )

        assertEquals("70% · 1.2 mm", dailyPrecipitationSummary(rainyDay, units))
    }

    @Test
    fun dailyRowsUsePlainWeatherIconsWithoutTargets() {
        val source = File(
            System.getProperty("user.dir"),
            "src/main/java/cz/majkey/pocasicesko/ui/ForecastScreen.kt",
        ).readText()

        assertFalse(source.contains("CompactWeatherTarget("))
        assertFalse(source.contains("DayRainTarget("))
        assertTrue(source.contains("WeatherIcon("))
    }

    @Test
    fun mapsWindDirectionSectorBoundaries() {
        assertEquals(R.string.wind_direction_north, windDirectionResource(0))
        assertEquals(R.string.wind_direction_northeast, windDirectionResource(23))
        assertEquals(R.string.wind_direction_east, windDirectionResource(68))
        assertEquals(R.string.wind_direction_southeast, windDirectionResource(113))
        assertEquals(R.string.wind_direction_south, windDirectionResource(158))
        assertEquals(R.string.wind_direction_southwest, windDirectionResource(203))
        assertEquals(R.string.wind_direction_west, windDirectionResource(248))
        assertEquals(R.string.wind_direction_northwest, windDirectionResource(293))
        assertEquals(R.string.wind_direction_north, windDirectionResource(22))
        assertEquals(R.string.wind_direction_northeast, windDirectionResource(23))
        assertEquals(R.string.wind_direction_northwest, windDirectionResource(337))
        assertEquals(R.string.wind_direction_north, windDirectionResource(338))
        assertEquals(R.string.wind_direction_north, windDirectionResource(-1))
    }

    @Test
    fun formatsShortDayInSelectedLocale() {
        assertEquals("Tue", formatDay("2026-08-25", Locale.US))
        assertEquals("Út", formatDay("2026-08-25", Locale.forLanguageTag("cs-CZ")))
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

    private fun day(date: String) = DailyWeather(
        date = date,
        weatherCode = 0,
        temperatureMax = 22.0,
        temperatureMin = 12.0,
        sunrise = "06:00",
        sunset = "20:00",
        precipitationSum = 0.0,
        precipitationProbability = 0,
        windSpeedMax = 10.0,
    )
}
