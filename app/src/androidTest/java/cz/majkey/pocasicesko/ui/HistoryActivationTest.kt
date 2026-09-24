package cz.majkey.pocasicesko.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import cz.majkey.pocasicesko.R
import cz.majkey.pocasicesko.data.CurrentWeather
import cz.majkey.pocasicesko.data.CzechLocation
import cz.majkey.pocasicesko.data.HistoricalDay
import cz.majkey.pocasicesko.data.HistoryArchive
import cz.majkey.pocasicesko.data.WeatherSnapshot
import cz.majkey.pocasicesko.units.MeasurementSystem
import cz.majkey.pocasicesko.units.WeatherUnitFormatter
import java.time.LocalDate
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class HistoryActivationTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun returningToAiRevalidatesArchiveAndKeepsTheQuestion() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val active = mutableStateOf(true)
        val location = CzechLocation("Prague", "", 50.0755, 14.4378, "CZ")
        val current = CurrentWeather("2026-09-24T12:00", 20.0, 20.0, 60, 0.0, 0, 0, 1010.0, 8.0, 90, 12.0, true)
        val snapshot = WeatherSnapshot("Europe/Prague", current, emptyList(), emptyList(), 0)
        var loads = 0
        compose.setContent {
            WeatherTheme {
                WeatherDetailSheet(snapshot, location, WeatherUnitFormatter(MeasurementSystem.METRIC, Locale.ENGLISH),
                    loadHistory = {
                        loads++
                        HistoryArchive(location, listOf(HistoricalDay(LocalDate.of(2026, 9, 22),
                            18.0, 21.0, 10.0, loads.toDouble(), 60.0, 2.0, 14.0)), "test", loads.toLong())
                    }, initialHistory = true, embedded = true, active = active.value, onDismiss = {})
            }
        }
        compose.onNodeWithText("1.0 mm").assertExists()
        val question = compose.onNodeWithText(context.getString(R.string.history_question))
        question.performScrollTo().performTextInput("Rain last month?")
        compose.runOnIdle { active.value = false }
        compose.runOnIdle { assertEquals(1, loads); active.value = true }
        compose.onNodeWithText("2.0 mm").assertExists()
        question.assertTextContains("Rain last month?")
        compose.runOnIdle { assertEquals(2, loads) }
    }
}
