package cz.majkey.pocasicesko.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherIconTest {
    @Test
    fun mainlyClearUsesSunOrMoonWithSmallCloud() {
        val source = File(
            System.getProperty("user.dir"),
            "src/main/java/cz/majkey/pocasicesko/ui/WeatherIcons.kt",
        ).readText()

        assertTrue(source.contains("kind == WeatherKind.MAINLY_CLEAR"))
        assertTrue(source.contains("Icons.Rounded.WbSunny"))
        assertTrue(source.contains("Icons.Rounded.DarkMode"))
        assertTrue(source.contains("Icons.Rounded.Cloud"))
    }

    @Test
    fun partlyCloudyUsesSunOrMoonBehindACloud() {
        val source = File(
            System.getProperty("user.dir"),
            "src/main/java/cz/majkey/pocasicesko/ui/WeatherIcons.kt",
        ).readText()

        assertTrue(source.contains("kind == WeatherKind.PARTLY_CLOUDY"))
        assertTrue(source.contains("sunOrMoonTint"))
        assertTrue(source.contains("cloudFraction"))
        assertFalse(source.contains("Icons.Rounded.FilterDrama"))
    }
}
