package cz.majkey.pocasicesko.notification

import cz.majkey.pocasicesko.data.CurrentWeather
import cz.majkey.pocasicesko.data.HourlyWeather
import cz.majkey.pocasicesko.data.PrecipitationModelSpread
import cz.majkey.pocasicesko.data.WeatherSnapshot
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherAlertsTest {
    private val now = Instant.parse("2026-09-22T08:30:00Z").toEpochMilli()
    private val allEnabled = WeatherAlertSettings(
        coldEnabled = true,
        dropEnabled = true,
        heatEnabled = true,
        windEnabled = true,
        uvEnabled = true,
    )

    @Test
    fun defaultsEnableOnlyRainAndOfficialWarningsAndBoundThresholds() {
        val settings = WeatherAlertSettings()
        assertEquals(setOf(WeatherAlertCategory.RAIN, WeatherAlertCategory.OFFICIAL),
            WeatherAlertCategory.entries.filter(settings::isEnabled).toSet())
        assertTrue(settings.anyEnabled)
        assertFalse(settings.copy(rainEnabled = false, officialWarningsEnabled = false).anyEnabled)
        val normalized = settings.copy(coldCelsius = Double.NaN, heatCelsius = 500.0,
            dropCelsius = -1.0, windKmh = Double.POSITIVE_INFINITY, uvIndex = -5.0,
            lookAheadHours = 99).normalized()
        assertEquals(settings.coldCelsius, normalized.coldCelsius, 0.0)
        assertEquals(45.0, normalized.heatCelsius, 0.0)
        assertEquals(3.0, normalized.dropCelsius, 0.0)
        assertEquals(settings.windKmh, normalized.windKmh, 0.0)
        assertEquals(3.0, normalized.uvIndex, 0.0)
        assertEquals(12, normalized.lookAheadHours)
    }

    @Test
    fun rainUsesFutureAccumulationAndIncludesSparseWetModelsWithoutInventedProbability() {
        val wet = hour("2026-09-22T12:00").copy(
            precipitationSpread = PrecipitationModelSpread(5, 1, 0.0, 0.2),
        )
        val decision = forecastAlerts(WeatherAlertSettings(), snapshot(listOf(wet)), now).single()
        assertEquals(WeatherAlertCategory.RAIN, decision.category)
        assertEquals(Instant.parse("2026-09-22T09:00:00Z").toEpochMilli(), decision.validAtEpochMillis)
        assertEquals(null, decision.value)
        assertTrue(forecastAlerts(WeatherAlertSettings(), snapshot(listOf(
            wet.copy(time = "2026-09-22T11:00"),
            wet.copy(time = "2026-09-22T10:00"),
        )), now).isEmpty())
    }

    @Test
    fun traceRainAndDrizzleNeedUmbrellaButDryAndMissingDataDoNot() {
        listOf(hour().copy(precipitation = 0.03), hour().copy(weatherCode = 51),
            hour().copy(precipitationProbability = 40)).forEach { wet ->
            assertEquals(WeatherAlertCategory.RAIN,
                forecastAlerts(WeatherAlertSettings(), snapshot(listOf(wet)), now).single().category)
        }
        assertTrue(forecastAlerts(allEnabled, snapshot(listOf(hour().copy(
            temperature = Double.NaN, windSpeed = Double.NaN, precipitation = Double.NaN,
            precipitationProbability = -1, weatherCode = -1,
        ))), now).isEmpty())
    }

    @Test
    fun rejectsStaleFutureFetchedAndInvalidClockSnapshots() {
        val wet = listOf(hour().copy(precipitation = 1.0))
        assertTrue(forecastAlerts(allEnabled, snapshot(wet).copy(
            updatedAtEpochMillis = now - 7 * 60 * 60 * 1_000L), now).isEmpty())
        assertTrue(forecastAlerts(allEnabled, snapshot(wet).copy(updatedAtEpochMillis = now + 1), now).isEmpty())
        assertTrue(forecastAlerts(allEnabled, snapshot(wet).copy(timezone = "missing", utcOffsetSeconds = null), now).isEmpty())
        assertTrue(forecastAlerts(allEnabled, snapshot(listOf(hour("bad"))), now).isEmpty())
    }

    @Test
    fun usesOffsetFallbackWhenTimezoneIsUnavailableAndOnlyConfiguredFutureHorizon() {
        val settings = WeatherAlertSettings(heatEnabled = true, lookAheadHours = 2)
        val hot = hour("2026-09-22T12:00").copy(temperature = 34.0)
        val fallback = snapshot(listOf(hot)).copy(timezone = "missing")
        assertEquals(WeatherAlertCategory.HEAT,
            forecastAlerts(settings, fallback, now).single().category)
        assertTrue(forecastAlerts(settings, fallback.copy(utcOffsetSeconds = 0), now).isEmpty())
        assertTrue(forecastAlerts(settings, snapshot(listOf(hot.copy(time = "2026-09-22T10:00"))), now).isEmpty())
    }

    @Test
    fun namedTimezoneOverridesCurrentOffsetAcrossFallDstAndSkipsAmbiguousHours() {
        val beforeTransition = Instant.parse("2026-10-24T23:30:00Z").toEpochMilli()
        val settings = WeatherAlertSettings(heatEnabled = true)
        val forecast = snapshot(listOf(
            hour("2026-10-25T02:00").copy(temperature = 33.0),
            hour("2026-10-25T03:00").copy(temperature = 34.0),
        )).copy(updatedAtEpochMillis = beforeTransition)

        val alert = forecastAlerts(settings, forecast, beforeTransition).single()
        assertEquals(WeatherAlertCategory.HEAT, alert.category)
        assertEquals(Instant.parse("2026-10-25T02:00:00Z").toEpochMilli(), alert.validAtEpochMillis)
        assertEquals(34.0, alert.value!!, 0.0)
        assertTrue(forecastAlerts(settings, forecast.copy(hourly = forecast.hourly.take(1)), beforeTransition).isEmpty())
    }

    @Test
    fun independentCategoriesUseRealValuesAndThresholds() {
        val hours = listOf(
            hour("2026-09-22T11:00").copy(temperature = 32.0, windGusts = 65.0, uvIndex = 8.0),
            hour("2026-09-22T12:00").copy(temperature = 3.0),
        )
        val decisions = forecastAlerts(allEnabled, snapshot(hours), now)
        assertEquals(setOf(WeatherAlertCategory.COLD, WeatherAlertCategory.DROP, WeatherAlertCategory.HEAT,
            WeatherAlertCategory.WIND, WeatherAlertCategory.UV), decisions.map { it.category }.toSet())
        assertEquals(29.0, decisions.single { it.category == WeatherAlertCategory.DROP }.value!!, 0.0)
        assertEquals(65.0, decisions.single { it.category == WeatherAlertCategory.WIND }.value!!, 0.0)
        assertTrue(forecastAlerts(WeatherAlertSettings(), snapshot(hours), now).isEmpty())
    }

    @Test
    fun temperatureDropNeedsTwoFutureTemperaturesWithinSixHours() {
        val settings = WeatherAlertSettings(dropEnabled = true, lookAheadHours = 12)
        val cold = hour("2026-09-22T12:00").copy(temperature = 5.0)
        val hot = hour("2026-09-22T11:00").copy(temperature = 25.0)
        assertTrue(forecastAlerts(settings, snapshot(listOf(cold)), now).isEmpty())
        assertTrue(forecastAlerts(settings, snapshot(listOf(hot, cold.copy(time = "2026-09-22T18:00"))), now).isEmpty())
        assertEquals(WeatherAlertCategory.DROP,
            forecastAlerts(settings, snapshot(listOf(cold, hot)), now).single().category)
    }

    @Test
    fun dedupSuppressesSameEventAndCooldownEvenWhenLocationOrForecastChanges() {
        assertTrue(shouldPostWeatherAlert(null, 0, "50.1,14.4|123", now))
        assertFalse(shouldPostWeatherAlert("50.1,14.4|123", now - 9 * 60 * 60 * 1_000L,
            "50.1,14.4|123", now))
        assertFalse(shouldPostWeatherAlert("50.1,14.4|123", now - 60 * 60 * 1_000L,
            "50.2,14.5|456", now))
        assertTrue(shouldPostWeatherAlert("50.1,14.4|123", now - 7 * 60 * 60 * 1_000L,
            "50.2,14.5|456", now))
        assertTrue(shouldPostWeatherAlert("50.1,14.4|123", now + 1, "50.2,14.5|456", now))
    }

    @Test
    fun temperatureDropUnitConversionDoesNotAddFreezingOffset() {
        assertEquals("8°C", temperatureDropText(8.0, cz.majkey.pocasicesko.units.MeasurementSystem.METRIC))
        assertEquals("14°F", temperatureDropText(8.0, cz.majkey.pocasicesko.units.MeasurementSystem.IMPERIAL))
    }

    @Test
    fun rainWindowUsesIntervalStartAtShortLookAheadWithoutExtendingTemperatureHorizon() {
        val settings = WeatherAlertSettings(lookAheadHours = 1, heatEnabled = true)
        val wet = hour("2026-09-22T12:00").copy(precipitation = 1.0, temperature = 34.0)
        assertEquals(listOf(WeatherAlertCategory.RAIN),
            forecastAlerts(settings, snapshot(listOf(wet)), now).map { it.category })
    }

    private fun hour(time: String = "2026-09-22T12:00") = HourlyWeather(
        time = time, temperature = 20.0, humidity = 50, precipitationProbability = 0,
        precipitation = 0.0, weatherCode = 0, pressure = 1013.0, windSpeed = 10.0,
        windDirection = 180, isDay = true,
    )

    private fun snapshot(hourly: List<HourlyWeather>) = WeatherSnapshot(
        timezone = "Europe/Prague", utcOffsetSeconds = 7200,
        current = CurrentWeather("2026-09-22T10:30", 20.0, 20.0, 50, 0.0, 0, 0,
            1013.0, 10.0, 180, 20.0, true),
        hourly = hourly, daily = emptyList(), updatedAtEpochMillis = now,
    )
}
