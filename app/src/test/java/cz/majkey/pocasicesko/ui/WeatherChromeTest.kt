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
    fun forecastDoesNotClaimUnshippedProviders() {
        val source = source("ForecastScreen.kt")

        assertFalse(source.contains("ALADIN CZ 1 km"))
        assertFalse(source.contains("ECMWF · ČHMÚ · Open-Meteo"))
    }

    private fun source(name: String): String = File(
        System.getProperty("user.dir"),
        "src/main/java/cz/majkey/pocasicesko/ui/$name",
    ).readText()
}
