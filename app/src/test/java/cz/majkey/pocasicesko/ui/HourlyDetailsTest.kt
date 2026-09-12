package cz.majkey.pocasicesko.ui

import cz.majkey.pocasicesko.data.HourlyWeather
import cz.majkey.pocasicesko.data.PrecipitationModelSpread
import java.io.File
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HourlyDetailsTest {
    @Test
    fun startingHourUsesFollowingSourceIntervalWithoutMovingInstantValues() {
        val instant = hour(18.0, 7.0).copy(time = "2026-09-09T11:00", precipitation = 0.1)
        val following = instant.copy(time = "2026-09-09T12:00", temperature = 28.0,
            precipitation = 1.7, precipitationProbability = 80, rain = 1.5, showers = 0.2,
            precipitationSpread = PrecipitationModelSpread(3, 2, 0.0, 2.1))
        val selected = requireNotNull(hourlyPrecipitationByStart(listOf(instant, following))[instant.time])
        assertEquals("11:00–12:00", hourlyPrecipitationInterval(selected.time, Locale.ENGLISH))
        assertEquals(1.7, selected.precipitation, 0.0)
        assertEquals(80, selected.precipitationProbability)
        assertEquals(1.5, requireNotNull(selected.rain), 0.0)
        assertEquals(0.2, requireNotNull(selected.showers), 0.0)
        assertEquals(2, requireNotNull(selected.precipitationSpread).wetModelCount)
        assertEquals(20.0, instant.temperature, 0.0)
        assertEquals(0.1, instant.precipitation, 0.0)
    }

    @Test
    fun startingIntervalsCrossMidnightAndDoNotFillGapsOrDuplicateEndpoints() {
        val midnight = hour(null, null).copy(time = "2027-01-01T00:00")
        val previous = midnight.copy(time = "2026-12-31T23:00")
        val later = midnight.copy(time = "2027-01-01T03:00")
        val sources = hourlyPrecipitationByStart(listOf(later, midnight, previous))
        assertEquals(midnight, sources["2026-12-31T23:00"])
        assertNull(sources["2027-01-01T00:00"])
        assertNull(sources["2027-01-01T03:00"])
        assertNull(hourlyPrecipitationByStart(listOf(previous, midnight, midnight.copy(time = "2027-01-01T00:00:00")))
            ["2026-12-31T23:00"])
        assertTrue(hourlyPrecipitationByStart(listOf(midnight.copy(time = "invalid"),
            midnight.copy(time = "2027-01-01T00:30"))).isEmpty())
    }

    @Test
    fun secondsPrecisionDisplayTimesFindTheSameEndingInterval() {
        val instant = hour(null, null).copy(time = "2026-09-09T11:00:00")
        val next = instant.copy(time = "2026-09-09T12:00:00", precipitation = 1.7)
        assertEquals(next, hourlyPrecipitationByStart(listOf(instant, next))[instant.time])
    }

    @Test
    fun shiftedPrecipitationDoesNotShiftInstantaneousWeatherCodes() {
        val clear = hour(null, 7.0).copy(weatherCode = 0, precipitation = 0.0, precipitationProbability = 0)
        assertEquals(HourlyHighlight.UV, hourlyHighlight(clear, clear.copy(weatherCode = 66)))
        assertEquals(HourlyHighlight.RAIN, hourlyHighlight(clear.copy(weatherCode = 51), clear))
    }

    @Test
    fun upcomingRainAndMissingComponentsDoNotBorrowPreviousHourData() {
        val instant = hour(18.0, 7.0).copy(precipitation = 0.0, rain = 1.0, snowfall = 0.2)
        val next = instant.copy(precipitation = 1.0, rain = null, snowfall = null, showers = 1.0)
        assertFalse(HourMetricKind.RAIN in availableHourMetricKinds(instant, next))
        assertFalse(HourMetricKind.SNOWFALL in availableHourMetricKinds(instant, next))
        assertTrue(HourMetricKind.SHOWERS in availableHourMetricKinds(instant, next))
        assertFalse(HourMetricKind.RAIN in availableHourMetricKinds(instant, null))
        assertEquals(HourlyHighlight.RAIN, hourlyHighlight(instant, next))
        val dryNext = next.copy(precipitation = 0.0, showers = 0.0, precipitationProbability = 0)
        assertEquals(HourlyHighlight.UV, hourlyHighlight(instant, dryNext))
    }

    @Test
    fun modelSpreadDoesNotBecomeAProbabilityOrOverrideSourceConditions() {
        val dry = hour(null, 7.0).copy(precipitation = 0.0, precipitationProbability = 0)
        val withSpread = dry.copy(precipitationSpread = PrecipitationModelSpread(3, 1, 0.0, 0.3))
        assertEquals(0, withSpread.precipitationProbability)
        assertEquals(dry.weatherCode, withSpread.weatherCode)
        assertEquals(HourlyRainLevel.NONE, hourlyRainLevel(withSpread))
        assertEquals(HourlyHighlight.UV, hourlyHighlight(withSpread))
    }

    @Test
    fun precipitationIntervalEndsAtTheSourceTimestamp() {
        assertEquals("07:00–08:00", hourlyPrecipitationInterval("2026-09-09T08:00", Locale.ENGLISH))
        assertEquals("8 Sep 2026, 23:00 – 9 Sep 2026, 00:00", hourlyPrecipitationInterval("2026-09-09T00:00", Locale.ENGLISH))
        assertEquals("31 Dec 2026, 23:00 – 1 Jan 2027, 00:00", hourlyPrecipitationInterval("2027-01-01T00:00", Locale.ENGLISH))
        assertNull(hourlyPrecipitationInterval("invalid", Locale.ENGLISH))
        assertEquals("11:00–12:00", hourlyStartingPrecipitationInterval("2026-09-09T11:00", Locale.ENGLISH))
        assertEquals("31 Dec 2026, 23:00 – 1 Jan 2027, 00:00", hourlyStartingPrecipitationInterval("2026-12-31T23:00", Locale.ENGLISH))
        assertNull(hourlyStartingPrecipitationInterval("invalid", Locale.ENGLISH))
    }

    @Test
    fun sourceDrizzleAndTraceAmountsNeverBecomeDryOrInventHigherProbability() {
        val dry = hour(null, 7.0).copy(precipitation = 0.0, precipitationProbability = 0)
        val drizzle = dry.copy(weatherCode = 51)
        assertEquals(HourlyHighlight.RAIN, hourlyHighlight(drizzle))
        assertEquals(HourlyRainLevel.FORECAST, hourlyRainLevel(drizzle))
        assertEquals(0, drizzle.precipitationProbability)
        assertEquals(HourlyRainLevel.FORECAST, hourlyRainLevel(dry.copy(precipitation = 0.03)))
        assertEquals(HourlyHighlight.RAIN, hourlyHighlight(dry.copy(rain = 0.03)))
        assertEquals(HourlyHighlight.SNOW, hourlyHighlight(dry.copy(weatherCode = 71)))
        assertEquals(HourlyHighlight.FREEZING, hourlyHighlight(dry.copy(weatherCode = 56)))
        assertEquals(HourlyHighlight.UV, hourlyHighlight(dry))
        assertEquals(HourlyHighlight.UV, hourlyHighlight(dry.copy(precipitation = Double.NaN, snowfall = Double.POSITIVE_INFINITY)))
    }

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
    fun distinguishesSnowMixedAndFreezingPrecipitationFromRain() {
        val wet = hour(null, null).copy(precipitationProbability = 80, precipitation = 1.0)
        assertEquals(HourlyHighlight.SNOW, hourlyHighlight(wet.copy(weatherCode = 73, snowfall = 0.7, rain = 0.0)))
        assertEquals(HourlyHighlight.MIXED, hourlyHighlight(wet.copy(snowfall = 0.4, rain = 0.5)))
        assertEquals(HourlyHighlight.FREEZING, hourlyHighlight(wet.copy(weatherCode = 66, rain = 1.0)))
        assertEquals(HourlyHighlight.RAIN, hourlyHighlight(wet.copy(weatherCode = 61)))
        assertEquals(HourlyHighlight.PRECIPITATION, hourlyHighlight(wet.copy(weatherCode = 1)))
        assertEquals(HourlyHighlight.SNOW, hourlyHighlight(wet.copy(precipitation = 0.0, precipitationProbability = 0, snowfall = 0.1)))
    }

    @Test
    fun prioritizesUsefulDryHighlightsAndKeepsRainFirst() {
        val dry = hour(null, null).copy(precipitationProbability = 0, precipitation = 0.0)
        assertEquals(HourlyHighlight.WIND, hourlyHighlight(dry.copy(windGusts = 45.0, uvIndex = 7.0)))
        assertEquals(HourlyHighlight.VISIBILITY, hourlyHighlight(dry.copy(visibilityMeters = 500.0)))
        assertEquals(HourlyHighlight.FEELS_LIKE, hourlyHighlight(dry.copy(apparentTemperature = 32.0, uvIndex = 7.0)))
        assertEquals(HourlyHighlight.UV, hourlyHighlight(dry.copy(uvIndex = 5.0)))
        assertEquals(HourlyHighlight.CONDITIONS, hourlyHighlight(dry.copy(isDay = false, uvIndex = 5.0)))
        assertEquals(HourlyHighlight.CONDITIONS, hourlyHighlight(dry))
        assertEquals(HourlyHighlight.RAIN, hourlyHighlight(dry.copy(rain = 2.0, precipitation = 2.0, windGusts = 45.0)))
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
        assertTrue(source.contains("modifier = Modifier.padding(top = 12.dp, bottom = 8.dp)"))
        assertFalse(source.contains("Modifier.padding(start = 81.dp, top = 12.dp"))
        val summary = source.substringAfter("itemsIndexed(hours")
            .substringAfter("\"${'$'}{stringResource(R.string.feels_like)} \" +", "")
            .substringBefore("if (expanded)")
        assertFalse(summary.contains("Modifier.padding(start = 81.dp"))
        assertTrue(summary.contains("Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp)"))
    }

    @Test
    fun expandedHourHighlightsAReadableWeatherSummary() {
        val source = File(
            System.getProperty("user.dir"),
            "src/main/java/cz/majkey/pocasicesko/ui/HourlyDetails.kt",
        ).readText()

        assertTrue(source.contains("hourlyWeatherSummary(hour, units, precipitationHour)"))
        assertTrue(source.contains("R.string.hourly_dry_summary"))
        assertTrue(source.contains("conditionFor(hour.weatherCode, hour.isDay)"))
        assertTrue(source.contains("shape = RoundedCornerShape(16.dp)"))
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
