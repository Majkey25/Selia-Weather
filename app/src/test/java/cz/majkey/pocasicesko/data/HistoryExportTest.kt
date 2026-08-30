package cz.majkey.pocasicesko.data

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryExportTest {
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
    }
}
