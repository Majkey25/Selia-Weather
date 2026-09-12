package cz.majkey.pocasicesko.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import cz.majkey.pocasicesko.R
import cz.majkey.pocasicesko.data.DailyWeather
import cz.majkey.pocasicesko.data.HourlyWeather
import cz.majkey.pocasicesko.units.MeasurementSystem
import cz.majkey.pocasicesko.units.WeatherUnitFormatter
import java.time.LocalDateTime
import java.util.Locale
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ForecastLayoutTest {
    @get:Rule val compose = createComposeRule()
    private val units = WeatherUnitFormatter(MeasurementSystem.METRIC, Locale.ENGLISH)
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val day = DailyWeather("2026-09-12", 95, 22.0, 12.0, "06:00", "18:00", 123.4, 85, 123.0,
        apparentTemperatureMin = 9.0, apparentTemperatureMax = 23.0)

    @Test
    fun dailyValuesFitAtLargeFontInNarrowWidth() {
        compose.setContent { TestSurface { DailyRow(day, true, units, {}) } }
        assertTextFits("123.4 mm", substring = true)
        assertTextFits(context.getString(R.string.feels_like), substring = true)
    }

    @Test
    fun chartPrecipitationIsNotClippedAtLargeFont() {
        compose.setContent { TestSurface { HourPrecipitationColumn(hour(11).copy(precipitation = 12.3), units, Modifier.width(68.dp)) } }
        assertTextFits("12.3 mm")
    }

    @Test
    fun currentHourLabelsFit() {
        showDay()
        compose.onNodeWithText("10:00", useUnmergedTree = true).performScrollTo()
        assertTextFits("10:00")
        assertTextFits(context.getString(R.string.now))
    }

    @Test
    fun tappingExpandedMetricDoesNotCollapseTheHour() {
        showDay()
        compose.onNodeWithText("10:00", useUnmergedTree = true).performTouchInput { click() }
        val label = context.getString(R.string.pressure)
        compose.onNodeWithText(label, useUnmergedTree = true).performScrollTo().performTouchInput { click() }
        compose.onNodeWithText(label, useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun precipitationExplanationIsAnIndependentDisclosure() {
        showDay()
        compose.onNodeWithText("10:00", useUnmergedTree = true).performTouchInput { click() }
        val note = context.getString(R.string.precipitation_probability_note)
        compose.onNodeWithText(note, useUnmergedTree = true).assertDoesNotExist()
        val help = compose.onNodeWithText(context.getString(R.string.precipitation_help))
        help.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, context.getString(R.string.hour_collapsed)))
        help.performScrollTo().performTouchInput { click() }
        help.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, context.getString(R.string.hour_expanded)))
        compose.onNodeWithText(note, useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        help.performScrollTo().performTouchInput { click() }
        compose.onNodeWithText(note, useUnmergedTree = true).assertDoesNotExist()
    }

    private fun showDay() {
        compose.setContent {
            WeatherTheme {
                DayDetailSheet(listOf(day), listOf(hour(10), hour(11)), 0,
                    LocalDateTime.parse("2026-09-12T10:45"), units, {})
            }
        }
    }

    @Composable
    private fun TestSurface(content: @Composable () -> Unit) {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
            WeatherTheme { Box(Modifier.width(320.dp)) { content() } }
        }
    }

    private fun assertTextFits(text: String, substring: Boolean = false) {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text, substring = substring, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue("No text layout for $text", layouts.isNotEmpty())
        layouts.forEach { layout ->
            assertFalse("Clipped height: $text", layout.didOverflowHeight)
            // A cached paragraph can be wider than its text. Check rendered lines, not paragraph width.
            repeat(layout.lineCount) { line ->
                assertFalse("Ellipsized text: $text", layout.isLineEllipsized(line))
                assertTrue("Clipped left edge: $text", layout.getLineLeft(line) >= 0f)
                assertTrue("Clipped right edge: $text", layout.getLineRight(line) <= layout.size.width)
                assertTrue("Clipped bottom edge: $text", layout.getLineBottom(line) <= layout.size.height)
            }
        }
    }

    private fun hour(value: Int) = HourlyWeather("2026-09-12T${value}:00", 20.0, 50, 10, 0.0,
        0, 1015.0, 5.0, 180, true, apparentTemperature = 19.0)
}
