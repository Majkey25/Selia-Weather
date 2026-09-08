package cz.majkey.pocasicesko.ui

import cz.majkey.pocasicesko.data.CzechLocation
import cz.majkey.pocasicesko.data.HistoricalDay
import cz.majkey.pocasicesko.data.HistoryArchive
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.LocalDate

class HistorySharePromptTest {
    private val archive = HistoryArchive(
        CzechLocation("Field", "World", 50.0, 14.0),
        listOf(
            HistoricalDay(LocalDate.of(2021, 1, 1), 1.0, 2.0, 0.0, 3.0, null, null, null),
            HistoricalDay(LocalDate.of(2026, 1, 1), 4.0, 5.0, 3.0, 6.0, null, null, null),
        ), "v1", 123L,
    )

    @Test
    fun questionFocusDoesNotHideTheFullArchiveDateRange() {
        val prompt = historySharePrompt(archive, "  Kolik napršelo?  ",
            LocalDate.of(2025, 12, 1)..LocalDate.of(2025, 12, 31))
        assertTrue(prompt.contains("2021-01-01 to 2026-01-01"))
        assertTrue(prompt.contains("2025-12-01 to 2025-12-31 (UTC, inclusive)"))
        assertTrue(prompt.contains("Question: Kolik napršelo?"))
        assertTrue(prompt.contains("Missing days and blank values are not zero"))
    }

    @Test
    fun emptyQuestionAsksForRainfallAndCustomQuestionsAreBounded() {
        assertTrue(historySharePrompt(archive).contains("total precipitation in millimetres"))
        val prompt = historySharePrompt(archive, "x".repeat(2100))
        assertTrue(prompt.contains("x".repeat(2000)))
        assertFalse(prompt.contains("x".repeat(2001)))
    }

    @Test
    fun reversedFocusRangeFailsInsteadOfRequestingTheWrongPeriod() {
        assertThrows(IllegalArgumentException::class.java) {
            historySharePrompt(archive, range = LocalDate.of(2026, 1, 1)..LocalDate.of(2025, 1, 1))
        }
    }

    @Test
    fun requestsTheResolvedApplicationLanguageForEverySupportedLocale() {
        mapOf("en" to "English", "cs-CZ" to "Czech", "de" to "German", "es" to "Spanish", "fr" to "French")
            .forEach { (tag, name) ->
                assertTrue(historySharePrompt(archive, responseLanguageTag = tag).contains("Respond in $name ("))
            }
        assertTrue(historySharePrompt(archive, responseLanguageTag = "unsupported").contains("Respond in English (en)"))
    }
}
