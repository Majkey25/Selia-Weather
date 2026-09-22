package cz.majkey.pocasicesko.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.click
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
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
import cz.majkey.pocasicesko.data.PrecipitationModelSpread
import cz.majkey.pocasicesko.notification.WeatherAlertCategory
import cz.majkey.pocasicesko.notification.WeatherAlertSettings
import cz.majkey.pocasicesko.units.MeasurementSystem
import cz.majkey.pocasicesko.units.WeatherUnitFormatter
import java.time.LocalDateTime
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
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
    fun notificationCategoriesToggleIndependentlyAndShowSystemBlocking() {
        val settings = mutableStateOf(WeatherAlertSettings())
        var openedChannel: String? = null
        var permissionRequests = 0
        compose.setContent {
            WeatherTheme {
                NotificationSettingsSheet(settings.value, MeasurementSystem.METRIC,
                    dailyBriefingEnabled = false, notificationsAllowed = false,
                    onSettingsChange = { settings.value = it }, onDailyBriefingChange = {},
                    onRequestPermission = { permissionRequests++ },
                    onChannelSettings = { openedChannel = it }, onDismiss = {},
                    blockedChannels = setOf(WeatherAlertCategory.RAIN.channelId))
            }
        }
        compose.onNodeWithText(context.getString(R.string.notification_enable)).performTouchInput { click() }
        assertEquals(1, permissionRequests)
        compose.onNodeWithText(context.getString(R.string.notification_channel_blocked), substring = true)
            .performScrollTo().assertIsDisplayed()
        val rainLabel = context.getString(WeatherAlertCategory.RAIN.labelResource)
        compose.onNodeWithContentDescription(context.getString(R.string.notification_channel_settings, rainLabel))
            .performScrollTo().performTouchInput { click() }
        assertEquals(WeatherAlertCategory.RAIN.channelId, openedChannel)
        assertTrue(settings.value.rainEnabled)
        compose.onNodeWithText(rainLabel).performTouchInput { click() }
        assertFalse(settings.value.rainEnabled)
        assertTrue(settings.value.officialWarningsEnabled)
        val coldLabel = context.getString(WeatherAlertCategory.COLD.labelResource)
        compose.onNodeWithText(coldLabel).performScrollTo().performTouchInput { click() }
        assertTrue(settings.value.coldEnabled)
        compose.onNodeWithText(context.getString(R.string.notification_at_or_below, units.temperature(5.0)))
            .performScrollTo().assertIsDisplayed()
    }

    @Test
    fun dailyOverviewStartsWithFourDaysAndExpandsWithoutChangingDayIndices() {
        val days = (0..13).map { day.copy(date = LocalDate.parse(day.date).plusDays(it.toLong()).toString()) }
        var selected = -1
        compose.setContent {
            WeatherTheme {
                Box(Modifier.width(320.dp).verticalScroll(rememberScrollState())) {
                    DailyForecastPanel(days, units, day.date, onDayClick = { selected = it })
                }
            }
        }
        val format = DateTimeFormatter.ofPattern("d MMM", context.resources.configuration.locales[0])
        val fifth = LocalDate.parse(days[4].date).format(format)
        compose.onNodeWithText(fifth).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.forecast_expand, 14)).performScrollTo().performTouchInput { click() }
        compose.onNodeWithText(fifth).performScrollTo().performTouchInput { click() }
        assertEquals(4, selected)
        compose.onNodeWithText(context.getString(R.string.forecast_collapse)).performScrollTo().performTouchInput { click() }
        compose.onNodeWithText(fifth).assertDoesNotExist()
    }

    @Test
    fun leftSwipeOpensFutureAndRightSwipeOpensPast() {
        val days = listOf(day.copy(date = "2026-09-11"), day, day.copy(date = "2026-09-13"))
        compose.setContent {
            WeatherTheme { DayDetailSheet(days, listOf(hour(10)), 1, null, units, {}) }
        }
        compose.onNode(isDialog()).performTouchInput { swipeLeft() }
        val dateFormat = DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL)
            .withLocale(context.resources.configuration.locales[0])
        compose.onNodeWithText(LocalDate.parse("2026-09-13").format(dateFormat)).assertIsDisplayed()
        compose.onNode(isDialog()).performTouchInput { swipeRight() }
        compose.onNode(isDialog()).performTouchInput { swipeRight() }
        compose.onNodeWithText(LocalDate.parse("2026-09-11").format(dateFormat)).assertIsDisplayed()
    }

    @Test
    fun selectedWeatherLabelFitsWithLargeText() {
        compose.setContent {
            TestSurface { FloatingNavigation(Destination.WEATHER, onDestination = {}, onAskAi = {}) }
        }
        assertTextFits(context.getString(R.string.nav_weather))
    }

    @Test
    fun uvStaysVisibleAndAdvancedMetricsNeedAnExplicitTap() {
        compose.setContent {
            WeatherTheme {
                ExpandedHourDetails(hour(10).copy(cape = 500.0), hour(11), units, Locale.ENGLISH)
            }
        }
        compose.onNodeWithText(context.getString(R.string.uv_index)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.cape)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.advanced_details)).performTouchInput { click() }
        compose.onNodeWithText(context.getString(R.string.cape)).assertExists()
    }

    @Test
    fun minorityRainIsHighlightedInsteadOfAConfidentDrySummary() {
        compose.setContent {
            WeatherTheme {
                ExpandedHourDetails(hour(10), hour(11).copy(precipitationProbability = 0,
                    precipitationSpread = PrecipitationModelSpread(9, 1, 0.0, 0.2)), units, Locale.ENGLISH)
            }
        }
        compose.onNodeWithText(context.getString(R.string.hour_summary_uncertain)).assertIsDisplayed()
    }

    @Test
    fun bottomActionsShareOneRowAndDispatchClicks() {
        var selected: Destination? = null
        var aiClicks = 0
        compose.setContent {
            WeatherTheme {
                Box(Modifier.width(320.dp)) {
                    FloatingNavigation(Destination.WEATHER, onDestination = { selected = it }, onAskAi = { aiClicks++ })
                }
            }
        }
        val weather = compose.onNodeWithContentDescription(context.getString(R.string.nav_weather))
        val radar = compose.onNodeWithContentDescription(context.getString(R.string.nav_maps))
        val ai = compose.onNodeWithContentDescription(context.getString(R.string.home_ask_ai))
        val weatherBounds = weather.fetchSemanticsNode().boundsInRoot
        val radarBounds = radar.fetchSemanticsNode().boundsInRoot
        val aiBounds = ai.fetchSemanticsNode().boundsInRoot
        assertEquals(weatherBounds.center.y, radarBounds.center.y, 1f)
        assertEquals(radarBounds.center.y, aiBounds.center.y, 1f)
        assertTrue(weatherBounds.right <= radarBounds.left)
        assertTrue(radarBounds.right <= aiBounds.left)
        radar.performTouchInput { click() }
        assertEquals(Destination.MAPS, selected)
        ai.performTouchInput { click() }
        assertEquals(1, aiClicks)
    }

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
