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
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.click
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import cz.majkey.pocasicesko.R
import cz.majkey.pocasicesko.data.CurrentWeather
import cz.majkey.pocasicesko.data.DailyWeather
import cz.majkey.pocasicesko.data.HourlyWeather
import cz.majkey.pocasicesko.data.HourlyModelAgreement
import cz.majkey.pocasicesko.data.HourlyModelInput
import cz.majkey.pocasicesko.data.WeatherKind
import cz.majkey.pocasicesko.data.hourlyForecastConfidence
import cz.majkey.pocasicesko.data.PrecipitationModelSpread
import cz.majkey.pocasicesko.data.WeatherSnapshot
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
    fun missingNextIntervalDoesNotHideTheAvailableModelCount() {
        val agreement = HourlyModelAgreement(5, 1.0, 2.0, 10.0, 5, 0, 0.0, 0.0, WeatherKind.CLEAR)
        val selected = hour(10).copy(modelAgreement = agreement)
        val confidence = hourlyForecastConfidence(selected, null, LocalDateTime.parse(selected.time), 0)
        compose.setContent { TestSurface { ForecastConfidenceBadge(confidence, units) } }
        compose.onNode(hasClickAction()).performClick()
        compose.onNodeWithText(context.getString(R.string.confidence_models, 5)).assertIsDisplayed()
        assertEquals(cz.majkey.pocasicesko.data.ConfidenceLevel.LIMITED, confidence.level)
        compose.onNodeWithText(context.getString(R.string.confidence_missing))
            .performScrollTo().assertIsDisplayed()
    }

    @Test
    fun obscuredSkyDetailsDoNotPresentModelCloudAmountsAsObserved() {
        val base = snapshot()
        val state = mutableStateOf(base.copy(current = base.current.copy(
            weatherCode = cz.majkey.pocasicesko.data.SKY_OBSCURED_WEATHER_CODE,
            cloudCover = 37, cloudCoverLow = 17)))
        compose.setContent {
            WeatherTheme {
                WeatherDetailSheet(state.value,
                    cz.majkey.pocasicesko.data.CzechLocation("WIOO", "", -0.151, 109.404, "ID"),
                    units, loadHistory = { error("History is not requested") }, embedded = true,
                    currentTime = LocalDateTime.parse(base.current.time), onDismiss = {})
            }
        }
        val section = context.getString(R.string.precipitation_and_clouds)
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(section))
        compose.onNodeWithText(section).performClick()
        val obscured = context.getString(R.string.condition_sky_obscured)
        compose.onAllNodesWithText(obscured).onLast().performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("37 %").assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.low_clouds)).assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(current = state.value.current.copy(weatherCode = 1)) }
        compose.onNodeWithText("37 %").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(obscured).assertDoesNotExist()
    }

    @Test
    fun pastHourHelpStillShowsAvailableNamedModels() {
        val inputs = listOf("chmi_aladin_seamless", "icon_seamless", "gfs_seamless").map { id ->
            HourlyModelInput(id, 20.0, 10.0, 180.0, 100.0, 3, 0.0)
        }
        val agreement = HourlyModelAgreement(3, 0.0, 0.0, 0.0, 3, 0, 0.0, 0.0, WeatherKind.CLOUDY,
            allSkyConditions = true, modelInputs = inputs)
        val selected = hour(10).copy(weatherCode = 3, modelAgreement = agreement)
        val confidence = cz.majkey.pocasicesko.data.dailyForecastConfidence(selected.time.take(10),
            listOf(selected.copy(time = hour(15).time), selected),
            mapOf(selected.time to hour(11).copy(modelAgreement = agreement)),
            LocalDateTime.parse(selected.time).plusDays(1), 0)
        compose.setContent {
            TestSurface { ForecastConfidenceBadge(confidence, units) }
        }
        compose.onNode(hasClickAction()).performClick()
        compose.onNodeWithText(context.getString(R.string.confidence_historical)).assertIsDisplayed()
        val time = requireNotNull(confidenceEvidenceContext(selected.time, Locale.ENGLISH)).validTime
        compose.onNodeWithText("${context.getString(R.string.forecast)} · $time").assertIsDisplayed()
        compose.onNodeWithText("ČHMÚ ALADIN seamless", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("DWD ICON seamless", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("NOAA GFS seamless", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun forecastHelpShowsNamedModelInputsAndSpecificConfidenceReason() {
        val inputs = listOf(
            HourlyModelInput("chmi_aladin_seamless", 20.0, 10.0, 180.0, 100.0, 3, 0.0),
            HourlyModelInput("icon_seamless", 20.0, 10.0, 180.0, 0.0, 0, 0.0),
            HourlyModelInput("gfs_seamless", 20.0, 10.0, 180.0, 0.0, 0, 0.0),
        )
        val agreement = HourlyModelAgreement(3, 0.0, 0.0, 100.0, 2, 0, 0.0, 0.0, WeatherKind.CLEAR,
            allSkyConditions = true, modelInputs = inputs)
        val selected = hour(10).copy(weatherCode = 0, modelAgreement = agreement)
        val following = hour(11).copy(precipitation = 0.0, modelAgreement = agreement)
        val confidence = hourlyForecastConfidence(selected, following, LocalDateTime.parse(selected.time), 0)
        compose.setContent {
            TestSurface {
                Box(Modifier.verticalScroll(rememberScrollState())) {
                    ExpandedHourDetails(selected, following, units, Locale.ENGLISH, confidence = confidence)
                }
            }
        }
        compose.onNodeWithText(context.getString(R.string.precipitation_help)).performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.confidence_conflict_clouds), substring = true)
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("ČHMÚ ALADIN seamless", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("DWD ICON seamless", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("NOAA GFS seamless", substring = true).performScrollTo().assertIsDisplayed()
        assertTextFits(context.getString(R.string.confidence_time_support))
    }

    @Test
    fun notificationCategoriesToggleIndependentlyAndShowSystemBlocking() {
        val settings = mutableStateOf(WeatherAlertSettings(backgroundAlertsAllowed = true))
        var openedChannel: String? = null
        var permissionRequests = 0
        compose.setContent {
            WeatherTheme {
                NotificationSettingsSheet(settings.value, MeasurementSystem.METRIC,
                    dailyBriefingEnabled = false, notificationsAllowed = false,
                    onSettingsChange = { settings.value = it }, onDailyBriefingChange = {},
                    onRequestPermission = { permissionRequests++ },
                    onChannelSettings = { openedChannel = it }, onBackgroundSettings = {}, onDismiss = {},
                    blockedChannels = setOf(WeatherAlertCategory.RAIN.channelId))
            }
        }
        compose.onNodeWithText(context.getString(R.string.notification_enable)).performTouchInput { click() }
        assertEquals(1, permissionRequests)
        val rainLabel = context.getString(WeatherAlertCategory.RAIN.labelResource)
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(rainLabel))
        compose.onNode(hasText(rainLabel) and hasClickAction()).performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.notification_channel_blocked), substring = true)
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription(context.getString(R.string.notification_channel_settings, rainLabel))
            .performScrollTo().performTouchInput { click() }
        assertEquals(WeatherAlertCategory.RAIN.channelId, openedChannel)
        assertTrue(settings.value.rainEnabled)
        compose.onNode(hasText(rainLabel) and hasClickAction()).performTouchInput { click() }
        assertFalse(settings.value.rainEnabled)
        assertTrue(settings.value.officialWarningsEnabled)
        compose.onNodeWithContentDescription(context.getString(R.string.back)).performTouchInput { click() }
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(context.getString(R.string.settings_temperature_alerts)))
        compose.onNode(hasText(context.getString(R.string.settings_temperature_alerts)) and hasClickAction()).performClick()
        val coldLabel = context.getString(WeatherAlertCategory.COLD.labelResource)
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(coldLabel))
        compose.onNode(hasText(coldLabel) and hasClickAction()).performScrollTo().performClick()
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
    fun missingHourlyChanceKeepsTheRainAmountAndReadableDetails() {
        compose.setContent {
            WeatherTheme {
                Box(Modifier.width(320.dp).verticalScroll(rememberScrollState())) {
                    ExpandedHourDetails(hour(10).copy(uvIndex = 3.0), hour(11).copy(precipitationProbability = null,
                        precipitation = 0.2, rain = 0.2), units, Locale.ENGLISH)
                }
            }
        }
        compose.onNodeWithText(context.getString(R.string.hour_summary_rain) + " " +
            context.getString(R.string.hour_summary_amount, "0.2 mm")).assertIsDisplayed()
        compose.onNodeWithText("null", substring = true).assertDoesNotExist()
        compose.onNodeWithText("0 %").assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.precipitation_probability)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.unavailable)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.uv_index)).performScrollTo().assertIsDisplayed()
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
        compose.setContent { TestSurface { DailyRow(day.copy(displayWeatherCode = 2), true, units, {}) } }
        assertTextFits("123.4 mm", substring = true)
        assertTextFits(context.getString(R.string.feels_like), substring = true)
        assertTextFits(context.getString(R.string.condition_storm), substring = true)
    }

    @Test
    fun chartPrecipitationIsNotClippedAtLargeFont() {
        compose.setContent { TestSurface { HourPrecipitationColumn(hour(11).copy(precipitation = 12.3), units, Modifier.width(68.dp)) } }
        assertTextFits("12.3 mm")
    }

    @Test
    fun allCurrentMetricsFitInTwoColumnsWithLargeText() {
        compose.setContent {
            TestSurface {
                Box(Modifier.verticalScroll(rememberScrollState())) {
                    CurrentMetrics(snapshot(), units, LocalDateTime.parse("2026-09-12T10:45"))
                }
            }
        }
        listOf(R.string.precipitation, R.string.wind, R.string.humidity, R.string.pressure,
            R.string.sunrise, R.string.sunset).forEach { resource ->
            val label = context.getString(resource)
            val node = compose.onNodeWithText(label, useUnmergedTree = true)
            node.performScrollTo().assertIsDisplayed()
            val bounds = node.fetchSemanticsNode().boundsInRoot
            assertTrue("Metric off left edge: $label", bounds.left >= 0f)
            assertTrue("Metric off right edge: $label", bounds.right <= compose.onRoot().fetchSemanticsNode().boundsInRoot.right)
            assertTextFits(label)
        }
        assertTextFits(units.pressure(1015.0))
        val windValue = units.windSpeed(5.0)
        compose.onNodeWithText(windValue, useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        assertTextFits(windValue)
    }

    @Test
    fun longHeroTemperatureFitsAtLargeFont() {
        val forecast = snapshot().let { it.copy(current = it.current.copy(temperature = -100.0)) }
        compose.setContent {
            TestSurface { WeatherHero(forecast, units, LocalDateTime.parse("2026-09-12T10:45")) }
        }
        assertTextFits(units.temperature(-100.0))
    }

    @Test
    fun staleHeroShowsItsValidTimeAndFreshHeroDoesNotAddAStaleLabel() {
        val now = mutableStateOf(LocalDateTime.parse("2026-09-12T11:45"))
        val forecast = snapshot()
        compose.setContent {
            TestSurface { WeatherHero(forecast, units, now.value) }
        }
        val timestamp = forecast.current.time.replace('T', ' ')
        compose.onNodeWithText(timestamp).assertIsDisplayed()
        assertTextFits(timestamp)
        compose.runOnIdle { now.value = LocalDateTime.parse("2026-09-12T10:45") }
        compose.onNodeWithText(timestamp).assertDoesNotExist()
    }

    @Test
    fun tappingCurrentHourKeepsForwardPrecipitationIntervalAndFeelsLike() {
        val forecast = snapshot(listOf(hour(10).copy(precipitation = 99.0),
            hour(11).copy(precipitation = 2.4, precipitationProbability = 80)))
        var selected: String? = null
        compose.setContent {
            WeatherTheme {
                Box(Modifier.width(320.dp)) {
                    HourlyGraphPanel(forecast, units, LocalDateTime.parse("2026-09-12T10:45")) { selected = it }
                }
            }
        }
        val current = compose.onNodeWithContentDescription(context.getString(R.string.now), substring = true)
        val description = current.fetchSemanticsNode().config[SemanticsProperties.ContentDescription].joinToString()
        assertTrue(description.contains("2.4 mm"))
        assertFalse(description.contains("99.0 mm"))
        assertTrue(description.contains(context.getString(R.string.precipitation_interval, "10:00–11:00")))
        assertTrue(description.contains(context.getString(R.string.feels_like_temperature, units.temperature(19.0))))
        current.performTouchInput { click() }
        assertEquals(hour(10).time, selected)
    }

    @Test
    fun futureDayKeepsOptionalDailyDataInItsOwnDisclosure() {
        val future = day.copy(date = "2026-09-13", uvIndexMax = 8.5, windGustsMax = 42.0,
            sunshineDurationSeconds = 21600.0, daylightDurationSeconds = 43200.0,
            precipitationHours = 3.5, snowfallSum = 2.0, rainSum = 1.2,
            dominantWindDirection = 180, shortwaveRadiationSum = 14.5, et0 = 2.5)
        compose.setContent {
            WeatherTheme { DayDetailSheet(listOf(future), emptyList(), 0,
                LocalDateTime.parse("2026-09-12T10:45"), units, {}) }
        }
        compose.onNodeWithText(context.getString(R.string.sunrise)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.uv_index_max)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.daily_wind_gusts)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.sunshine_duration)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.advanced_details)).performScrollTo().performTouchInput { click() }
        listOf(R.string.sunshine_duration, R.string.daylight_duration, R.string.precipitation_hours,
            R.string.snowfall, R.string.rain, R.string.dominant_wind, R.string.shortwave_radiation,
            R.string.et0_evapotranspiration).forEach { resource ->
            compose.onNodeWithText(context.getString(resource)).assertExists()
        }
        compose.onNodeWithText(context.getString(R.string.duration_hours_minutes, 6, 0)).assertExists()
        compose.onNodeWithText(units.snowfall(2.0)).assertExists()
    }

    @Test
    fun unavailableDailyValuesDoNotCreateAnEmptyAdvancedDisclosure() {
        compose.setContent {
            WeatherTheme { DayAvailableDetails(day.copy(uvIndexMax = Double.NaN, windGustsMax = Double.NaN,
                sunshineDurationSeconds = Double.NaN, precipitationHours = Double.NaN), units, Locale.ENGLISH) }
        }
        compose.onNodeWithText(context.getString(R.string.sunrise)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.uv_index_max)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.advanced_details)).assertDoesNotExist()
    }

    @Test
    fun currentHourLabelsFit() {
        showDay(largeText = true)
        compose.onNodeWithText("10:00", useUnmergedTree = true).performScrollTo()
        assertTextFits("10:00")
        assertTextFits(context.getString(R.string.now))
    }

    @Test
    fun currentDayRowAndExpandedSummaryUseTheSameCurrentSkyUntilTheHourChanges() {
        val now = mutableStateOf(LocalDateTime.parse("2026-09-12T10:45"))
        val current = snapshot().current.copy(weatherCode = 3, cloudCover = 100)
        compose.setContent {
            WeatherTheme {
                DayDetailSheet(listOf(day), listOf(hour(10), hour(11)), 0, now.value, units, {}, current = current)
            }
        }
        val cloudy = context.getString(R.string.condition_cloudy)
        compose.onNodeWithText("10:00", useUnmergedTree = true).performScrollTo().performTouchInput { click() }
        compose.onNodeWithText(cloudy, useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.hour_summary_conditions, cloudy, units.temperature(19.0)))
            .performScrollTo().assertIsDisplayed()
        compose.runOnIdle { now.value = LocalDateTime.parse("2026-09-12T11:05") }
        compose.onNode(hasScrollToNodeAction() and
            SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
            .performScrollToNode(hasText("10:00"))
        val previousHour = compose.onNode(hasText("10:00") and hasClickAction())
        previousHour.assertIsDisplayed().assert(hasText(context.getString(R.string.condition_clear_day)))
        previousHour.assert(hasText(cloudy).not())
        compose.onNodeWithText(context.getString(R.string.hour_summary_conditions,
            context.getString(R.string.condition_clear_day), units.temperature(19.0)))
            .performScrollTo().assertIsDisplayed()
    }

    @Test
    fun currentDayUsesNewObservationWithoutChangingTheHourlyForecast() {
        val current = mutableStateOf(snapshot().current.copy(weatherCode = 3, cloudCover = 100))
        val hours = listOf(hour(10), hour(11))
        compose.setContent {
            WeatherTheme {
                DayDetailSheet(listOf(day), hours, 0, LocalDateTime.parse("2026-09-12T10:45"), units, {},
                    current = current.value)
            }
        }
        val cloudy = context.getString(R.string.condition_cloudy)
        compose.onNodeWithText("10:00", useUnmergedTree = true).performScrollTo().performTouchInput { click() }
        compose.onNode(hasText("10:00") and hasClickAction()).assert(hasText(cloudy))
        compose.runOnIdle { current.value = current.value.copy(weatherCode = 0, cloudCover = 0) }
        compose.onNode(hasText("10:00") and hasClickAction())
            .assert(hasText(context.getString(R.string.condition_clear_day))).assert(hasText(cloudy).not())
        compose.onNodeWithText(context.getString(R.string.hour_summary_conditions,
            context.getString(R.string.condition_clear_day), units.temperature(19.0)))
            .performScrollTo().assertIsDisplayed()
    }

    @Test
    fun currentHourHighlightMovesWhenLocalTimeChanges() {
        val now = mutableStateOf(LocalDateTime.parse("2026-09-12T10:45"))
        compose.setContent {
            WeatherTheme {
                DayDetailSheet(listOf(day), listOf(hour(10), hour(11)), 0, now.value, units, {})
            }
        }
        fun backgroundAtHour(time: String): Int {
            compose.onNode(hasScrollToNodeAction() and
                SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
                .performScrollToNode(hasText(time))
            val row = compose.onNode(hasText(time) and hasClickAction())
            val density = context.resources.displayMetrics.density
            assertTrue("Hour click target smaller than 48dp", row.fetchSemanticsNode().boundsInRoot.height >= 48 * density)
            val image = row.captureToImage().asAndroidBitmap()
            return image.getPixel(image.width - 1, 1)
        }
        val highlighted = backgroundAtHour("10:00")
        val ordinary = backgroundAtHour("11:00")
        assertTrue("Current hour has no distinct background", highlighted != ordinary)
        compose.runOnIdle { now.value = LocalDateTime.parse("2026-09-12T11:05") }
        compose.waitForIdle()
        compose.onNode(hasText("11:00") and hasClickAction()).assert(hasText(context.getString(R.string.now)))
        assertEquals(highlighted, backgroundAtHour("11:00"))
        assertEquals(ordinary, backgroundAtHour("10:00"))
        compose.onNode(hasText("10:00") and hasClickAction()).assert(hasText(context.getString(R.string.now)).not())
    }

    @Test
    fun tappingExpandedMetricDoesNotCollapseTheHour() {
        showDay(largeText = true)
        val hour = compose.onNodeWithText("10:00", useUnmergedTree = true)
        hour.performScrollTo().performTouchInput { click() }
        val advanced = compose.onNodeWithText(context.getString(R.string.advanced_details))
        advanced.performScrollTo().performTouchInput { click() }
        val label = context.getString(R.string.pressure)
        compose.onNodeWithText(label, useUnmergedTree = true).performScrollTo().performTouchInput { click() }
        compose.onNodeWithText(label, useUnmergedTree = true).assertIsDisplayed()
        advanced.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,
            context.getString(R.string.hour_expanded)))
        hour.performScrollTo().performTouchInput { click() }
        compose.onNodeWithText(label, useUnmergedTree = true).assertDoesNotExist()
        advanced.assertDoesNotExist()
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

    private fun showDay(largeText: Boolean = false) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density,
                if (largeText) 2f else density.fontScale)) {
                WeatherTheme {
                    DayDetailSheet(listOf(day), listOf(hour(10), hour(11)), 0,
                        LocalDateTime.parse("2026-09-12T10:45"), units, {})
                }
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

    private fun snapshot(hours: List<HourlyWeather> = listOf(hour(10), hour(11))) = WeatherSnapshot(
        timezone = "Europe/Prague",
        current = CurrentWeather("2026-09-12T10:00", 20.0, 19.0, 50, 1.2, 0, 10,
            1015.0, 5.0, 180, 10.0, true),
        hourly = hours,
        daily = listOf(day),
        updatedAtEpochMillis = System.currentTimeMillis(),
    )
}
