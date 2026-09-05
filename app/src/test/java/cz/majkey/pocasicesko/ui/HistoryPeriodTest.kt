package cz.majkey.pocasicesko.ui

import cz.majkey.pocasicesko.data.CzechLocation
import cz.majkey.pocasicesko.data.HistoricalDay
import cz.majkey.pocasicesko.data.HistoryArchive
import cz.majkey.pocasicesko.data.historyCsv
import cz.majkey.pocasicesko.data.inDateRange
import java.io.File
import java.time.LocalDate
import java.time.Instant
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryPeriodTest {
    private val end = LocalDate.of(2024, 3, 15)
    private val archive = HistoryArchive(
        CzechLocation("Praha", "CZ", 50.0755, 14.4378, "CZ"),
        (399 downTo 0).map { offset ->
            HistoricalDay(end.minusDays(offset.toLong()), 10.0, 15.0, 5.0, 1.5, 60.0, 3.0, 8.0)
        },
        "test-source",
        123L,
    )

    @Test
    fun presetsUseExactCalendarDaysIncludingLeapDay() {
        assertEquals(LocalDate.of(2024, 2, 15)..end, archive.periodRange(HistoryPeriod.LAST_30))
        assertEquals(30, archive.periodDays(HistoryPeriod.LAST_30).size)
        assertEquals(45.0, requireNotNull(archive.rangeSummary(archive.periodRange(HistoryPeriod.LAST_30))).totalPrecipitationMm, 0.0)
        assertEquals(365, archive.periodDays(HistoryPeriod.LAST_365).size)
        assertEquals(400, archive.periodDays(HistoryPeriod.ALL).size)
        assertEquals(400L, requireNotNull(archive.rangeSummary(archive.periodRange(HistoryPeriod.ALL))).calendarDayCount)
    }

    @Test
    fun missingDaysRemainMissingInSelectedPeriodCoverage() {
        val partial = archive.copy(days = listOf(archive.days[379], archive.days.last()))
        val summary = requireNotNull(partial.rangeSummary(partial.periodRange(HistoryPeriod.LAST_30)))
        assertEquals(2, summary.dayCount)
        assertEquals(30L, summary.calendarDayCount)
        assertEquals(3.0, summary.totalPrecipitationMm, 0.0)
    }

    @Test
    fun filteringPreservesTheFullArchiveForAiSharingAndSourceMetadata() {
        val csv = historyCsv(archive)
        val selected = requireNotNull(archive.inDateRange(end.minusDays(29), end))
        assertEquals(30, selected.days.size)
        assertEquals(archive.location, selected.location)
        assertEquals(archive.sourceVersion, selected.sourceVersion)
        assertEquals(archive.accessedAtEpochMillis, selected.accessedAtEpochMillis)
        assertEquals(400, archive.days.size)
        assertEquals(csv, historyCsv(archive))
        assertNull(archive.inDateRange(end.plusDays(1), end.plusDays(10)))
        assertThrows(IllegalArgumentException::class.java) { archive.inDateRange(end, end.minusDays(1)) }
    }

    @Test
    fun mainHistoryActionLoadsTheArchiveAndSharesTheUnfilteredSource() {
        val root = File(System.getProperty("user.dir"), "src/main/java/cz/majkey/pocasicesko/ui")
        val forecast = File(root, "ForecastScreen.kt").readText()
        val details = File(root, "WeatherDetailScreen.kt").readText()
        assertTrue(forecast.contains("WeatherDetailAction(label = R.string.history_title)"))
        assertTrue(forecast.contains("initialHistory = openHistory"))
        assertTrue(details.contains("if (initialHistory) loadArchive()"))
        assertTrue(details.contains("onClick = { onShare(archive) }"))
    }

    @Test
    fun customRangesIncludeBothEndpointsAndDoNotTurnMissingRecordsIntoZeroRain() {
        val oneDay = end..end
        assertEquals(1.5, requireNotNull(archive.rangeSummary(oneDay)).totalPrecipitationMm, 0.0)
        assertEquals(1L, requireNotNull(archive.rangeSummary(oneDay)).calendarDayCount)
        val gap = archive.copy(days = listOf(archive.days.first(), archive.days.last()))
        val missingRange = end.minusDays(20)..end.minusDays(10)
        assertEquals(emptyList<HistoricalDay>(), gap.periodDays(HistoryPeriod.CUSTOM, missingRange))
        assertNull(gap.rangeSummary(missingRange))
        val partialRange = end.minusDays(20)..end
        val summary = requireNotNull(gap.rangeSummary(partialRange))
        assertEquals(1, summary.dayCount)
        assertEquals(21L, summary.calendarDayCount)
        assertEquals(1.5, summary.totalPrecipitationMm, 0.0)
    }

    @Test
    fun pickerRejectsIncompleteReversedAndOutOfArchiveRanges() {
        val first = Instant.parse("2024-02-29T00:00:00Z").toEpochMilli()
        val last = Instant.parse("2024-03-15T00:00:00Z").toEpochMilli()
        val bounds = LocalDate.of(2024, 2, 29)..end
        assertEquals(bounds, historyPickerRange(first, last, bounds))
        assertEquals(end..end, historyPickerRange(last, last, bounds))
        assertNull(historyPickerRange(null, last, bounds))
        assertNull(historyPickerRange(first, null, bounds))
        assertNull(historyPickerRange(last, first, bounds))
        assertNull(historyPickerRange(first - 86_400_000, last, bounds))
        assertNull(historyPickerRange(first, last + 86_400_000, bounds))
    }

    @Test
    fun calendarSelectionUsesUtcDatesRegardlessOfDeviceTimezone() {
        val original = TimeZone.getDefault()
        val millis = Instant.parse("2024-03-31T00:00:00Z").toEpochMilli()
        try {
            for (zone in listOf("Pacific/Honolulu", "Europe/Prague", "Asia/Tokyo")) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone))
                assertEquals(LocalDate.of(2024, 3, 31), historyDateFromUtcMillis(millis))
            }
        } finally {
            TimeZone.setDefault(original)
        }
    }
}
