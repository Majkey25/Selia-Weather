package cz.majkey.pocasicesko.data

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryExportTest {
    @Test
    fun promptDisclosesMissingDaysAndMetricCoverage() {
        val first = HistoricalDay(LocalDate.of(2026, 1, 1), 2.0, 3.0, 1.0, 0.5, 70.0, 2.0, 4.0)
        val archive = HistoryArchive(
            CzechLocation("Praha", REGION_PRAGUE, 50.0755, 14.4378, "CZ"),
            listOf(first, first.copy(date = first.date.plusDays(2), solarEnergyMegajoulesPerSquareMeter = null)),
            "v1", 123L,
        )

        val prompt = historyChatPrompt(archive)

        assertTrue(prompt.contains("2 of 3 calendar days"))
        assertTrue(prompt.contains("Solar energy: 1 days"))
        assertTrue(prompt.contains("Missing days and blank values are not zero"))
    }

    @Test
    fun exportsDailyRowsWithStableUnitsAndSourceMetadata() {
        val archive = HistoryArchive(
            location = CzechLocation("Praha, pole", REGION_PRAGUE, 50.0755, 14.4378, "CZ"),
            days = listOf(
                HistoricalDay(LocalDate.of(2026, 1, 1), -0.77, 0.57, -2.76, 0.0, 85.81, 9.29, 1.0),
                HistoricalDay(LocalDate.of(2026, 1, 2), -0.96, 1.34, -3.66, 0.66, null, 9.78, 2.03),
            ),
            sourceVersion = "v2.9.7",
            accessedAtEpochMillis = 123L,
        )

        val lines = historyCsv(archive).lines()

        assertEquals(3, lines.size)
        assertTrue(lines[0].contains("precipitation_mm"))
        assertTrue(
            lines[1].startsWith(
                "\"Praha, pole\",50.075500,14.437800,NASA POWER,v2.9.7," +
                    "1970-01-01T00:00:00.123Z,2026-01-01",
            ),
        )
        assertTrue(lines[2].contains(",2026-01-02,-0.96,1.34,-3.66,0.66,,9.78,2.03"))
        assertFalse(lines.any { it.contains("-999") })
        assertTrue(historyChatPrompt(archive).contains("2026-01-01 to 2026-01-02"))
        assertTrue(historyChatPrompt(archive).contains("precipitation for any requested date range"))
    }

    @Test
    fun exportsEveryExpandedMetricAndLeavesMissingValuesBlank() {
        val day = HistoricalDay(
            LocalDate.of(2026, 1, 1), null, 3.0, null, 0.5, 70.0, 2.0, null,
            dewPointC = -2.3, wetBulbTemperatureC = -1.01, surfacePressureHpa = 977.0,
            windSpeedMaximumMetersPerSecond = 10.72, windSpeedMinimumMetersPerSecond = 6.02,
            windDirectionDegrees = 224.4, clearSkySolarEnergyMegajoulesPerSquareMeter = 4.53,
            cloudCoverPercent = 93.79,
        )
        val archive = HistoryArchive(CzechLocation("Praha", REGION_PRAGUE, 50.0, 14.0), listOf(day), "v1", 123L)
        val lines = historyCsv(archive).lines()
        assertEquals(22, lines[0].split(',').size)
        assertEquals(lines[0].split(',').size, lines[1].split(',').size)
        assertTrue(lines[1].contains(",2026-01-01,,3.00,,0.50,70.00,2.00,,"))
        assertTrue(lines[1].endsWith(",-2.30,-1.01,977.00,10.72,6.02,224.40,4.53,93.79"))
        assertTrue(historyChatPrompt(archive).contains("Wind maxima are not gust measurements"))
    }

    @Test
    fun exportsLocationNamesAndSourceVersionsAsTextNotSpreadsheetFormulas() {
        val day = HistoricalDay(LocalDate.of(2026, 1, 1), 1.0, 2.0, 0.0, 0.0, null, null, null)
        for (name in listOf("=1+1", "+123", "-123", "@SUM(A1)", "\t=1+1")) {
            val archive = HistoryArchive(CzechLocation(name, REGION_PRAGUE, 50.0, 14.0), listOf(day), "=version", 123L)
            val row = historyCsv(archive).lines()[1]
            assertTrue(row.startsWith("'$name,"))
            assertTrue(row.contains(",NASA POWER,'=version,"))
        }
    }
}
