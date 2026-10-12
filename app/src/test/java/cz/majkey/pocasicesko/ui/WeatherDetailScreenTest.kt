package cz.majkey.pocasicesko.ui

import cz.majkey.pocasicesko.data.HourlyWeather
import java.io.File
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherDetailScreenTest {
    @Test
    fun distinguishesAccumulationIntervalFromInstantConditionEvidence() {
        val instant = hour("2026-09-09T08:00", 0, 0.0).copy(weatherCode = 51)
        assertEquals("9 Sep 2026, 08:00", nextPrecipitationLabel(instant, Locale.ENGLISH))
        assertEquals("07:00–08:00", nextPrecipitationLabel(instant.copy(precipitation = 0.03), Locale.ENGLISH))
        assertEquals("07:00–08:00", nextPrecipitationLabel(instant.copy(precipitationProbability = 10), Locale.ENGLISH))
        assertEquals("07:00–08:00", nextPrecipitationLabel(instant.copy(rain = 0.03), Locale.ENGLISH))
        assertNull(nextPrecipitationLabel(instant.copy(time = "invalid"), Locale.ENGLISH))
    }

    @Test
    fun nextPrecipitationIncludesDrizzleTraceAndMeaningfulProbability() {
        val time = "2026-08-29T19:00"
        val dry = hour(time, probability = 0, precipitation = 0.0)
        listOf(
            dry.copy(weatherCode = 51),
            dry.copy(precipitation = 0.03),
            dry.copy(rain = 0.03),
            dry.copy(weatherCode = 71),
            dry.copy(precipitationProbability = 16),
        ).forEach { assertEquals(it, nextWetHour(listOf(it), "2026-08-29T18:30")) }
        assertNull(nextWetHour(listOf(dry), time))
    }

    @Test
    fun nextPrecipitationSkipsNegligibleChanceWithoutDiscardingSourceEvidence() {
        val minimal = hour("2026-08-29T19:00", probability = 1, precipitation = 0.0)
        val low = minimal.copy(time = "2026-08-29T20:00", precipitationProbability = 15)
        val meaningful = minimal.copy(time = "2026-08-29T21:00", precipitationProbability = 16)
        val reference = "2026-08-29T18:30"

        assertNull(nextWetHour(listOf(minimal, low), reference))
        assertEquals(15, maximumPrecipitationProbability(listOf(minimal, low), reference))
        assertEquals(meaningful, nextWetHour(listOf(meaningful, low, minimal), reference))
        val trace = minimal.copy(precipitation = 0.03)
        assertEquals(trace, nextWetHour(listOf(meaningful, trace), reference))
        val drizzle = minimal.copy(weatherCode = 51)
        assertEquals(drizzle, nextWetHour(listOf(meaningful, drizzle), reference))
    }

    @Test
    fun confidenceEvidenceNamesTheSelectedHourAndItsFollowingPrecipitationInterval() {
        val context = requireNotNull(confidenceEvidenceContext("2026-12-31T23:00", Locale.ENGLISH))
        assertEquals("31 Dec 2026, 23:00", context.validTime)
        assertEquals("31 Dec 2026, 23:00 – 1 Jan 2027, 00:00", context.precipitationInterval)
        assertNull(confidenceEvidenceContext(null, Locale.ENGLISH))
        assertNull(confidenceEvidenceContext("invalid", Locale.ENGLISH))
        assertNull(confidenceEvidenceContext("+999999999-12-31T23:00", Locale.ENGLISH))
    }

    @Test
    fun precipitationSummaryUsesFutureClockHoursNotTheNext24Rows() {
        val oldRain = hour("2026-08-29T19:00", 100, 1.0)
        val nextRain = hour("2026-08-29T20:00", 10, 0.03)
        val boundary = hour("2026-08-30T19:30", 20, 0.1)
        val tooLate = hour("2026-08-30T20:30", 90, 2.0)
        val malformed = hour("not-a-time", 100, 3.0)
        val hours = listOf(tooLate, boundary, malformed, oldRain, nextRain)

        assertEquals(nextRain, nextWetHour(hours, "2026-08-29T19:30"))
        assertEquals(20, maximumPrecipitationProbability(hours, "2026-08-29T19:30"))
        assertEquals(boundary, nextWetHour(listOf(boundary), "2026-08-29T19:30"))
        assertNull(nextWetHour(listOf(oldRain), oldRain.time))
        assertNull(nextWetHour(listOf(oldRain, tooLate, malformed), "2026-08-29T19:30"))
        assertNull(maximumPrecipitationProbability(hours, "invalid"))
        assertNull(nextWetHour(hours, "invalid"))
    }

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
    fun missingRainChanceCannotBecomeAnAllClearButRainEvidenceSurvives() {
        val dry = hour("2026-08-29T20:00", 0, 0.0)
        val unknown = dry.copy(time = "2026-08-29T21:00", precipitationProbability = null)
        assertNull(maximumPrecipitationProbability(listOf(dry, unknown), "2026-08-29T19:30"))
        val wet = unknown.copy(precipitation = 0.2)
        assertEquals(wet, nextWetHour(listOf(dry, wet), "2026-08-29T19:30"))
        assertEquals("20:00–21:00", nextPrecipitationLabel(wet, Locale.ENGLISH))
        assertEquals(0, maximumPrecipitationProbability(listOf(dry), "2026-08-29T19:30"))
    }

    @Test
    fun keepsTheApprovedDetailHierarchy() {
        val source = File(
            System.getProperty("user.dir"),
            "src/main/java/cz/majkey/pocasicesko/ui/WeatherDetailScreen.kt",
        ).readText()
        val markers = listOf(
            "AtAGlanceSection(",
            "DetailSection(stringResource(R.string.temperature), collapsible = true,",
            "DetailSection(stringResource(R.string.precipitation_and_clouds), collapsible = true,",
            "DetailSection(stringResource(R.string.wind), collapsible = true,",
            "DetailSection(stringResource(R.string.detail_group_sun_moon), collapsible = true,",
            "DetailSection(stringResource(R.string.sun))",
            "MoonSection(",
            "DetailSection(stringResource(R.string.detail_group_other), collapsible = true,",
            "DetailSection(stringResource(R.string.atmosphere))",
            "DetailSection(stringResource(R.string.ground))",
            "DetailSection(stringResource(R.string.detail_group_sources), collapsible = true,",
        )

        assertTrue(markers.zipWithNext().all { (first, second) -> source.indexOf(first) < source.indexOf(second) })
        assertFalse(source.contains("LocalRainFieldSection("))
        assertFalse(source.contains("PrecipitationFieldUiState"))
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
