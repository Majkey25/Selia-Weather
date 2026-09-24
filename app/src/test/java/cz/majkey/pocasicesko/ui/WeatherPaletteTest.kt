package cz.majkey.pocasicesko.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import cz.majkey.pocasicesko.data.WeatherKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherPaletteTest {
    @Test
    fun sunnyDayIsWarmAndCloudyDayIsBlue() {
        listOf(WeatherKind.CLEAR, WeatherKind.MAINLY_CLEAR).forEach { kind ->
            val sunny = weatherPalette(kind, true).background.first()
            assertTrue("$kind should be warm", sunny.red > sunny.blue && sunny.red > sunny.green)
        }
        listOf(WeatherKind.CLOUDY, WeatherKind.FOG, WeatherKind.SNOW).forEach { kind ->
            val cloudy = weatherPalette(kind, true).background.first()
            assertTrue("$kind should be blue", cloudy.blue > cloudy.red)
        }
        assertEquals(weatherPalette(WeatherKind.CLOUDY, true), weatherPalette(WeatherKind.FOG, true))
        assertEquals(weatherPalette(WeatherKind.RAIN, true), weatherPalette(WeatherKind.STORM, true))
    }

    @Test
    fun nightStaysCoolAndDarkerThanSunnyDay() {
        val day = weatherPalette(WeatherKind.CLEAR, true).background.first()
        val night = weatherPalette(WeatherKind.CLEAR, false).background.first()
        assertTrue(night.blue > night.red)
        assertTrue(night.luminance() < day.luminance())
        assertEquals(weatherPalette(WeatherKind.CLEAR, false), weatherPalette(WeatherKind.RAIN, false))
    }

    @Test
    fun unknownConditionsDoNotLookSunny() {
        assertEquals(weatherPalette(null, true), weatherPalette(WeatherKind.UNKNOWN, true))
        assertNotEquals(weatherPalette(WeatherKind.CLEAR, true), weatherPalette(WeatherKind.UNKNOWN, true))
    }

    @Test
    fun secondaryTextRemainsReadableEvenWhereBothGlowsOverlap() {
        val text = Color(0xFFDDEAF1)
        (WeatherKind.entries + null).forEach { kind ->
            listOf(true, false).forEach { isDay ->
                val palette = weatherPalette(kind, isDay)
                palette.background.forEach { background ->
                    val lit = palette.secondaryGlow.compositeOver(palette.primaryGlow.compositeOver(background))
                    assertEquals(1f, lit.alpha, 0f)
                    val contrast = (text.luminance() + 0.05f) / (lit.luminance() + 0.05f)
                    assertTrue("$kind day=$isDay secondary text contrast=$contrast", contrast >= 4.5f)
                }
            }
        }
    }
}
