package cz.majkey.pocasicesko.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherChromeTest {
    @Test
    fun navigationHasNoOuterOpaqueSurface() {
        val source = source("WeatherApp.kt")
        val navigation = source.substringAfter("private fun FloatingNavigation")
            .substringBefore("private fun NavigationItem")

        assertFalse(navigation.contains("Surface("))
        assertFalse(navigation.contains("0xED101B23"))
        assertFalse(source.contains("bottomBar ="))
    }

    @Test
    fun eachNavigationButtonHasItsOwnVisibleBackground() {
        val source = source("WeatherApp.kt")
        val item = source.substringAfter("private fun NavigationItem")
            .substringBefore("private fun WeatherBackdrop")

        assertFalse(item.contains("else Color.Transparent"))
        assertTrue(item.contains("contentColor ="))
        assertTrue(item.contains("Color(0xFF2E6474)"))
        assertTrue(item.contains("Color(0xFF142731)"))
    }

    @Test
    fun navigationRippleIsClippedByItsOvalSurface() {
        val source = source("WeatherApp.kt")
        val item = source.substringAfter("private fun NavigationItem")
            .substringBefore("private fun WeatherBackdrop")

        assertFalse(item.contains(".clickable(onClick = onClick)"))
        assertTrue(item.contains("Surface(\n        onClick = onClick,"))
    }

    @Test
    fun everyNavigationButtonExposesItsLabelToAccessibility() {
        val source = source("WeatherApp.kt")
        val item = source.substringAfter("private fun NavigationItem")
            .substringBefore("private fun WeatherBackdrop")

        assertTrue(item.contains(".semantics { contentDescription = label }"))
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
    fun settingsExposeSelectedOptionsAndOneBriefingToggle() {
        val settings = source("SettingsSheet.kt")
        assertTrue(settings.contains("selected = system == selectedMeasurementSystem"))
        assertTrue(settings.contains("selected = language.tag == selectedTag"))
        assertTrue(settings.contains("role = Role.RadioButton"))
        assertTrue(settings.contains(".toggleable(value = dailyBriefingEnabled, role = Role.Switch"))
        val briefingSwitch = settings.substringAfter("checked = dailyBriefingEnabled").substringBefore("modifier = Modifier")
        assertTrue(briefingSwitch.contains("onCheckedChange = null"))
    }

    private fun source(name: String): String = File(
        System.getProperty("user.dir"),
        "src/main/java/cz/majkey/pocasicesko/ui/$name",
    ).readText()
}
