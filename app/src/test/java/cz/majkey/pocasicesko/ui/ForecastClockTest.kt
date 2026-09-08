package cz.majkey.pocasicesko.ui

import cz.majkey.pocasicesko.data.DailyWeather
import java.net.URI
import java.time.Instant
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ForecastClockTest {
    @Test
    fun responseOffsetWinsAndChangesTheDayAtLocationMidnight() {
        val instant = Instant.parse("2026-09-08T22:30:00Z")
        assertEquals(LocalDateTime.parse("2026-09-09T00:30"), forecastLocalTime(instant, "America/New_York", 7200))
        assertEquals(LocalDateTime.parse("2026-09-08T22:30"), forecastLocalTime(instant, "Asia/Tokyo", 0))
        assertEquals(LocalDateTime.parse("2026-09-09T04:00"), forecastLocalTime(instant, "unknown", 19_800))
    }

    @Test
    fun legacyZoneFallbackHandlesDstAndUnknownZonesDoNotGuessDeviceTime() {
        assertEquals(LocalDateTime.parse("2026-03-29T01:30"), forecastLocalTime(Instant.parse("2026-03-29T00:30:00Z"), "Europe/Prague", null))
        assertEquals(LocalDateTime.parse("2026-03-29T03:30"), forecastLocalTime(Instant.parse("2026-03-29T01:30:00Z"), "Europe/Prague", null))
        assertNull(forecastLocalTime(Instant.EPOCH, "unknown", null))
        assertEquals(LocalDateTime.parse("1970-01-01T00:00"), forecastLocalTime(Instant.EPOCH, "UTC", 100_000))
    }

    @Test
    fun nowMarkerMovesAtHourRolloverAndDoesNotMarkFutureOrMalformedRows() {
        val before = LocalDateTime.parse("2026-09-09T00:59:59")
        assertTrue(isCurrentForecastHour("2026-09-09T00:00", before))
        assertFalse(isCurrentForecastHour("2026-09-09T00:00", before.plusSeconds(1)))
        assertTrue(isCurrentForecastHour("2026-09-09T01:00", before.plusSeconds(1)))
        assertFalse(isCurrentForecastHour("2026-09-10T00:00", before))
        assertFalse(isCurrentForecastHour("2026-09-09T00:invalid", before))
        assertFalse(isCurrentForecastHour("2026-09-09T00:00", null))
    }

    @Test
    fun dailyOverviewStartsFromTheRealDayInsteadOfSnapshotFirstDay() {
        val days = listOf("2026-09-07", "2026-09-08", "2026-09-09").map {
            DailyWeather(it, 0, 20.0, 10.0, "06:00", "18:00", 0.0, 0, 5.0)
        }
        assertEquals(2, forecastStartIndex(days, "2026-09-09"))
        assertEquals(0, forecastStartIndex(days, null))
        assertEquals(0, forecastStartIndex(days, "2026-10-01"))
    }

    @Test
    fun legalLinksUseOnlyTheFourRequestedPublicPages() {
        val urls = LegalPage.entries.map { URI(it.url) }
        assertTrue(urls.all { it.scheme == "https" && it.host == "majkey25.github.io" && it.query == null })
        assertEquals(setOf("/Selia-Weather/", "/Selia-Weather/terms.html", "/Selia-Weather/refunds.html", "/Selia-Weather/cookies.html"), urls.map { it.path }.toSet())
    }
}
