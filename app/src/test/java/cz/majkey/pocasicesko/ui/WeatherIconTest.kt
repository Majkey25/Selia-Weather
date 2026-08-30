package cz.majkey.pocasicesko.ui

import androidx.compose.ui.graphics.Color
import cz.majkey.pocasicesko.data.WeatherKind
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherIconTest {
    @Test
    fun mainlyClearKeepsCloudSmallAndBlue() {
        assertEquals(0.50f, compositeCloudFraction(WeatherKind.MAINLY_CLEAR), 0.001f)
        assertEquals(Color(0xFF9FB7FF), compositeCloudTint(WeatherKind.MAINLY_CLEAR, Color.Yellow))
    }

    @Test
    fun partlyCloudyKeepsLargerRequestedCloudTint() {
        val requested = Color(0xFF9ED9EA)

        assertEquals(0.64f, compositeCloudFraction(WeatherKind.PARTLY_CLOUDY), 0.001f)
        assertEquals(requested, compositeCloudTint(WeatherKind.PARTLY_CLOUDY, requested))
    }

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
