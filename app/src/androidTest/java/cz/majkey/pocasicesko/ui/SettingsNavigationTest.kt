package cz.majkey.pocasicesko.ui

import android.view.KeyEvent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.platform.app.InstrumentationRegistry
import cz.majkey.pocasicesko.R
import cz.majkey.pocasicesko.monetization.BillingMessage
import cz.majkey.pocasicesko.monetization.EntitlementState
import cz.majkey.pocasicesko.notification.WeatherAlertSettings
import cz.majkey.pocasicesko.units.MeasurementSystem
import cz.majkey.pocasicesko.units.WeatherUnitFormatter
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SettingsNavigationTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test
    fun unitsOpenInSubmenuAndSystemBackReturnsBeforeDismissing() {
        val selected = mutableStateOf(MeasurementSystem.METRIC)
        val visible = mutableStateOf(true)
        var dismissals = 0
        compose.setContent {
            WeatherTheme {
                if (visible.value) {
                    SettingsSheet(
                        selectedTag = "", selectedMeasurementSystem = selected.value,
                        entitlement = EntitlementState.FREE, premiumOffers = emptyList(),
                        billingMessage = BillingMessage.NONE, paymentsEnabled = false,
                        privacyOptionsRequired = false, widgetIds = emptyList(),
                        onLanguage = {}, onMeasurementSystem = { selected.value = it },
                        onNotifications = {}, onAddWidget = {}, onEditWidget = {},
                        onWeatherDataAttribution = {}, onLegalPage = {}, onSupport = {},
                        supportError = null, onPurchase = {}, onRestorePurchases = {},
                        onPrivacyOptions = {}, onClearBillingMessage = {},
                        onDismiss = { dismissals++; visible.value = false },
                    )
                }
            }
        }
        compose.onNodeWithText(context.getString(R.string.units_imperial)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.units)).performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.units_imperial)).performClick()
        assertEquals(MeasurementSystem.IMPERIAL, selected.value)
        compose.onNodeWithText(context.getString(R.string.units_imperial)).assertIsSelected()
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.onNodeWithText(context.getString(R.string.notifications)).assertIsDisplayed()
        assertEquals(0, dismissals)
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitForIdle()
        assertEquals(1, dismissals)
    }

    @Test
    fun temperatureThresholdChangesSurviveBackNavigationWithoutChangingOtherAlerts() {
        val settings = mutableStateOf(WeatherAlertSettings())
        var dismissals = 0
        compose.setContent {
            WeatherTheme {
                NotificationSettingsSheet(
                    settings.value, MeasurementSystem.METRIC,
                    dailyBriefingEnabled = false, notificationsAllowed = true,
                    onSettingsChange = { settings.value = it }, onDailyBriefingChange = {},
                    onRequestPermission = {}, onChannelSettings = {}, onDismiss = { dismissals++ },
                )
            }
        }
        val cold = context.getString(R.string.notification_cold)
        compose.onNodeWithText(cold).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.settings_temperature_alerts))
            .performScrollTo().performClick()
        compose.onNodeWithText(cold).performClick()
        val units = WeatherUnitFormatter(MeasurementSystem.METRIC, Locale.ENGLISH)
        compose.onNodeWithContentDescription(context.getString(R.string.notification_at_or_below, units.temperature(5.0)))
            .performSemanticsAction(SemanticsActions.SetProgress) { it(-5f) }
        assertEquals(-5.0, settings.value.coldCelsius, 0.0)
        assertTrue(settings.value.coldEnabled)
        assertTrue(settings.value.rainEnabled)
        assertTrue(settings.value.officialWarningsEnabled)
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.onNodeWithText(context.getString(R.string.settings_temperature_alerts)).assertIsDisplayed()
        assertEquals(0, dismissals)
        compose.onNodeWithText(context.getString(R.string.settings_temperature_alerts)).performClick()
        compose.onNodeWithText(context.getString(R.string.notification_at_or_below, units.temperature(-5.0)))
            .assertIsDisplayed()
    }
}
