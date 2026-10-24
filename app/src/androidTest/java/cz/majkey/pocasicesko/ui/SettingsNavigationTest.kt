package cz.majkey.pocasicesko.ui

import android.view.KeyEvent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.platform.app.InstrumentationRegistry
import cz.majkey.pocasicesko.R
import cz.majkey.pocasicesko.BuildConfig
import cz.majkey.pocasicesko.monetization.BillingMessage
import cz.majkey.pocasicesko.monetization.EntitlementState
import cz.majkey.pocasicesko.notification.WeatherAlertSettings
import cz.majkey.pocasicesko.units.MeasurementSystem
import cz.majkey.pocasicesko.units.WeatherUnitFormatter
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SettingsNavigationTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test
    fun aboutMenuKeepsExternalDonationsOnlyInGithubBuilds() {
        var supportClicks = 0
        compose.setContent {
            WeatherTheme {
                SettingsSheet(
                    selectedTag = "", selectedMeasurementSystem = MeasurementSystem.METRIC,
                    entitlement = EntitlementState.FREE, premiumOffers = emptyList(),
                    billingMessage = BillingMessage.NONE, paymentsEnabled = false,
                    privacyOptionsRequired = false, widgetIds = emptyList(),
                    onLanguage = {}, onMeasurementSystem = {}, onNotifications = {},
                    onAddWidget = {}, onEditWidget = {}, onWeatherDataAttribution = {},
                    onLegalPage = {}, onSupport = { supportClicks++ }, supportError = null,
                    onPurchase = {}, onRestorePurchases = {}, onPrivacyOptions = {},
                    onClearBillingMessage = {}, onDismiss = {},
                )
            }
        }
        compose.onNodeWithText(context.getString(R.string.settings_about_support)).performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.legal_cookies)).performScrollTo().assertIsDisplayed()
        if (BuildConfig.DISTRIBUTION_CHANNEL == "github") {
            compose.onNodeWithText(context.getString(R.string.support_this_app))
                .performScrollTo().assertIsDisplayed().performClick()
            assertEquals(1, supportClicks)
            assertEquals("https://www.buymeacoffee.com/majkey", supportIntent()?.dataString)
        } else {
            compose.onNode(hasScrollAction()).performTouchInput { swipeUp() }
            compose.onNodeWithText(context.getString(R.string.support_this_app)).assertDoesNotExist()
            compose.onNodeWithText(context.getString(R.string.support_note)).assertDoesNotExist()
            assertEquals(0, supportClicks)
            assertNull(supportIntent())
        }
    }

    @Test
    fun rainLeadAndProbabilitySurviveBackWithoutChangingTemperatureAlerts() {
        val settings = mutableStateOf(WeatherAlertSettings())
        compose.setContent {
            WeatherTheme {
                NotificationSettingsSheet(settings.value, MeasurementSystem.METRIC,
                    dailyBriefingEnabled = false, notificationsAllowed = true,
                    onSettingsChange = { settings.value = it }, onDailyBriefingChange = {},
                    onRequestPermission = {}, onChannelSettings = {}, onBackgroundSettings = {}, onDismiss = {})
            }
        }
        compose.onNodeWithText(context.getString(R.string.notification_rain)).performScrollTo().performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.notification_rain_lead, 1))
            .performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(2f) }
        compose.onNodeWithContentDescription(context.getString(R.string.notification_rain_probability, 40))
            .performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(60f) }
        assertEquals(2, settings.value.rainLeadHours)
        assertEquals(60, settings.value.rainProbabilityPercent)
        assertEquals(6, settings.value.lookAheadHours)
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.onNodeWithText(context.getString(R.string.notification_rain_lead, 2)).assertIsDisplayed()
    }

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
                    onRequestPermission = {}, onChannelSettings = {}, onBackgroundSettings = {}, onDismiss = { dismissals++ },
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

    @Test
    fun backgroundDeliveryHelpOpensAppSettingsOnlyOnRequest() {
        var opened = 0
        compose.setContent {
            WeatherTheme {
                NotificationSettingsSheet(WeatherAlertSettings(), MeasurementSystem.METRIC,
                    dailyBriefingEnabled = false, notificationsAllowed = true,
                    onSettingsChange = {}, onDailyBriefingChange = {}, onRequestPermission = {},
                    onChannelSettings = {}, onBackgroundSettings = { opened++ }, onDismiss = {})
            }
        }
        assertEquals(0, opened)
        compose.onNodeWithText(context.getString(R.string.notification_background_delivery)).performClick()
        compose.onNodeWithText(context.getString(R.string.notification_background_help)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.open_app_settings)).performScrollTo().performClick()
        assertEquals(1, opened)
    }
}
