package cz.majkey.pocasicesko.ui

import cz.majkey.pocasicesko.R
import cz.majkey.pocasicesko.data.HistoricalDay
import cz.majkey.pocasicesko.units.MeasurementSystem
import cz.majkey.pocasicesko.units.WeatherUnitFormatter
import java.time.LocalDate
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryDayDetailTest {
    private val day = HistoricalDay(LocalDate.of(2026, 9, 1), 20.0, 25.0, 15.0, 25.4, 65.0, 5.0, 10.0,
        dewPointC = 12.0, wetBulbTemperatureC = 16.0, surfacePressureHpa = 1013.25,
        windSpeedMaximumMetersPerSecond = 10.0, windSpeedMinimumMetersPerSecond = 0.0,
        windDirectionDegrees = 270.0, clearSkySolarEnergyMegajoulesPerSquareMeter = 15.0, cloudCoverPercent = 45.0)
    private val metric = WeatherUnitFormatter(MeasurementSystem.METRIC, Locale.US)

    @Test
    fun expandedDayContainsAllFifteenDistinctMetricsWithCorrectUnits() {
        val details = historicalDayMetrics(day, metric, Locale.US)
        assertEquals(15, details.size)
        assertEquals(15, details.map { it.label }.distinct().size)
        assertTrue(details.all { it.value != null })
        val values = details.associate { it.label to it.value }
        assertEquals("36 km/h", values[R.string.history_wind_maximum])
        assertEquals("0 km/h", values[R.string.history_wind_minimum])
        assertEquals("1013 hPa", values[R.string.surface_pressure])
        assertEquals("270°", values[R.string.history_wind_direction])
        assertEquals("15.0 MJ/m²", values[R.string.history_clear_sky_solar_energy])
        val imperial = historicalDayMetrics(day, WeatherUnitFormatter(MeasurementSystem.IMPERIAL, Locale.US), Locale.US)
            .associate { it.label to it.value }
        assertEquals("68°", imperial[R.string.history_average_temperature])
        assertEquals("1.00 in", imperial[R.string.precipitation])
        assertEquals("22 mph", imperial[R.string.history_wind_maximum])
        assertEquals("29.92 inHg", imperial[R.string.surface_pressure])
    }

    @Test
    fun unavailableAndMeasuredZeroRemainDifferent() {
        val missing = HistoricalDay(day.date, null, null, null, null, null, null, null)
        assertTrue(historicalDayMetrics(missing, metric, Locale.US).all { it.value == null })
        val zero = historicalDayMetrics(missing.copy(temperatureMeanC = 0.0, precipitationMm = 0.0), metric, Locale.US)
            .associate { it.label to it.value }
        assertEquals("0°", zero[R.string.history_average_temperature])
        assertEquals("0.0 mm", zero[R.string.precipitation])
        assertEquals("Unavailable", historicalTemperatureRange(null, null, metric, "Unavailable"))
        assertEquals("Unavailable – 0°", historicalTemperatureRange(null, 0.0, metric, "Unavailable"))
    }

    @Test
    fun archiveMetricsUseMeaningfulWeatherCategories() {
        val icons = historicalDayMetrics(day, metric, Locale.US).map { historicalMetricIcon(it.label) }
        assertTrue(icons.all { it != null })
        assertEquals(7, icons.map { it?.name }.distinct().size)
        assertNull(historicalMetricIcon(0))
    }

    @Test
    fun questionInputIsBoundedWithoutCuttingAnEmojiInHalf() {
        assertEquals("", historyQuestionInput(""))
        assertEquals("Kolik pršelo?", historyQuestionInput("Kolik pršelo?"))
        assertEquals(MAX_HISTORY_QUESTION_CHARS, historyQuestionInput("a".repeat(MAX_HISTORY_QUESTION_CHARS + 50)).length)
        val prefix = "a".repeat(MAX_HISTORY_QUESTION_CHARS - 1)
        assertEquals(prefix, historyQuestionInput(prefix + "🌧"))
    }
}
