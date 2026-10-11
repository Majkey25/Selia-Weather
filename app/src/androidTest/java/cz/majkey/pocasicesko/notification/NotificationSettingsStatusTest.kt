package cz.majkey.pocasicesko.notification

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import cz.majkey.pocasicesko.R
import cz.majkey.pocasicesko.ui.NotificationSettingsSheet
import cz.majkey.pocasicesko.ui.WeatherTheme
import cz.majkey.pocasicesko.units.MeasurementSystem
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class NotificationSettingsStatusTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun blockedRainChannelIsVisibleWithoutClearingSavedPreference() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val rain = context.getString(R.string.notification_rain)
        val blocked = context.getString(R.string.notification_channel_blocked)
        var openedChannel: String? = null
        var preferenceChanges = 0
        compose.setContent {
            WeatherTheme {
                NotificationSettingsSheet(WeatherAlertSettings(), MeasurementSystem.METRIC,
                    dailyBriefingEnabled = false, notificationsAllowed = true,
                    blockedChannels = setOf(WeatherAlertCategory.RAIN.channelId),
                    onSettingsChange = { preferenceChanges++ }, onDailyBriefingChange = {}, onRequestPermission = {},
                    onChannelSettings = { openedChannel = it }, onBackgroundSettings = {}, onDismiss = {})
            }
        }
        compose.onNode(hasText(rain) and hasText(blocked)).performScrollTo().assertIsDisplayed().performClick()
        compose.onNode(hasText(rain) and SemanticsMatcher.expectValue(
            SemanticsProperties.ToggleableState, ToggleableState.On)).assertIsOn()
        compose.onNodeWithContentDescription(context.getString(R.string.notification_channel_settings, rain))
            .performClick()
        assertEquals(WeatherAlertCategory.RAIN.channelId, openedChannel)
        assertEquals(0, preferenceChanges)
    }
}
