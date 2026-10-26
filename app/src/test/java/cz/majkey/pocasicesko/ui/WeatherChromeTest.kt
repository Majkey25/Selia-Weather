package cz.majkey.pocasicesko.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherChromeTest {
    @Test
    fun warningsFollowWeatherDetailsAndPrecedeHistory() {
        val source = source("ForecastScreen.kt").substringAfter("internal fun ForecastScreen(")
            .substringBefore("private fun WeatherDetailAction(")
        val daily = source.indexOf("DailyForecastPanel(")
        val details = source.indexOf("WeatherDetailAction {")
        val warnings = source.indexOf("WarningsAction(warnings, onWarnings)")
        val history = source.indexOf("WeatherDetailAction(label = R.string.history_title)")
        assertTrue("Daily forecast must precede details", daily >= 0 && details > daily)
        assertTrue("Warnings must follow details and precede history", warnings > details && history > warnings)
    }

    @Test
    fun navigationUsesOneClippedCapsuleWithoutPageFooter() {
        val source = source("WeatherApp.kt")
        val navigation = source.substringAfter("internal fun FloatingNavigation")
            .substringBefore("private fun NavigationItem")

        assertTrue(navigation.contains("Surface("))
        assertTrue(navigation.contains(".clip(RoundedCornerShape(50))"))
        assertTrue(navigation.contains(".testTag(\"main-navigation\")"))
        assertFalse(navigation.contains("0xED101B23"))
        assertFalse(source.contains("bottomBar ="))
    }

    @Test
    fun onlySelectedNavigationItemHasVisibleBackgroundAndLabel() {
        val source = source("WeatherApp.kt")
        val item = source.substringAfter("private fun NavigationItem")
            .substringBefore("private fun WeatherBackdrop")

        assertTrue(item.contains("else Color.Transparent"))
        assertTrue(item.contains("contentColor ="))
        assertTrue(item.contains("MaterialTheme.colorScheme.primary"))
        assertFalse(item.contains("border ="))
        assertTrue(item.contains("if (selected)"))
        assertTrue(item.contains("Text(displayLabel"))
    }

    @Test
    fun navigationRippleIsClippedByItsOvalSurface() {
        val source = source("WeatherApp.kt")
        val item = source.substringAfter("private fun NavigationItem")
            .substringBefore("private fun WeatherBackdrop")

        assertFalse(item.contains(".clickable(onClick = onClick)"))
        assertTrue(item.contains("Surface(\n        onClick = onClick,"))
        assertTrue(item.contains(".clip(RoundedCornerShape(50))"))
    }

    @Test
    fun everyNavigationButtonExposesItsLabelToAccessibility() {
        val source = source("WeatherApp.kt")
        val item = source.substringAfter("private fun NavigationItem")
            .substringBefore("private fun WeatherBackdrop")

        assertTrue(item.contains("contentDescription = label"))
        assertTrue(item.contains("this.selected = selected"))
        assertTrue(item.contains("role = Role.Tab"))
    }

    @Test
    fun forecastDoesNotClaimUnshippedProviders() {
        val source = source("ForecastScreen.kt")

        assertFalse(source.contains("ALADIN CZ 1 km"))
        assertFalse(source.contains("ECMWF · ČHMÚ · Open-Meteo"))
    }

    @Test
    fun resumesStaleWeatherWithoutDuplicateOrRepeatedFailedRequests() {
        val now = 2_000_000L
        val stale = now - 15 * 60 * 1_000L
        assertTrue(shouldRefreshWeatherOnResume(stale, stale, false, now))
        assertFalse(shouldRefreshWeatherOnResume(now - 1_000L, stale, false, now))
        assertFalse(shouldRefreshWeatherOnResume(stale, stale, true, now))
        assertFalse(shouldRefreshWeatherOnResume(stale, now - 1_000L, false, now))
        assertTrue(shouldRefreshWeatherOnResume(now + 1_000L, now + 1_000L, false, now))
    }

    @Test
    fun recreatingTheActivityReusesFreshWeatherButManualRefreshAndExpiredCacheStillFetch() {
        val now = 2_000_000L
        assertFalse(shouldRefreshWeatherOnLoad(now - 1_000L, false, now))
        assertFalse(shouldRefreshWeatherOnLoad(now, false, now))
        assertTrue(shouldRefreshWeatherOnLoad(now - 1_000L, true, now))
        assertTrue(shouldRefreshWeatherOnLoad(now - 15 * 60 * 1_000L, false, now))
        assertTrue(shouldRefreshWeatherOnLoad(null, false, now))
        assertTrue(shouldRefreshWeatherOnLoad(now + 1_000L, false, now))
    }

    @Test
    fun settingsExposeSelectedOptionsAndOneBriefingToggle() {
        val settings = source("SettingsSheet.kt")
        assertTrue(settings.contains("selected = system == selectedMeasurementSystem"))
        assertTrue(settings.contains("selected = language.tag == selectedTag"))
        assertTrue(settings.contains("role = Role.RadioButton"))
        assertTrue(settings.contains("onNotifications"))
        val notifications = source("NotificationSettingsSheet.kt")
        assertTrue(notifications.contains("enabled = dailyBriefingEnabled"))
        assertTrue(notifications.contains(".toggleable(value = enabled, role = Role.Switch"))
        assertTrue(notifications.contains("Switch(checked = enabled, onCheckedChange = null)"))
    }

    private fun source(name: String): String = File(
        System.getProperty("user.dir"),
        "src/main/java/cz/majkey/pocasicesko/ui/$name",
    ).readText().replace("\r\n", "\n")
}
