package cz.majkey.pocasicesko.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import cz.majkey.pocasicesko.data.WeatherKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppearanceSettingsTest {
    @Test
    fun missingOrUnsupportedPreferenceFallsBackToWeather() {
        assertEquals(AppAppearance.WEATHER, appAppearance(null))
        assertEquals(AppAppearance.WEATHER, appAppearance("unsupported"))
        assertEquals(AppAppearance.SUNSET, appAppearance("SUNSET"))
        assertEquals(AppAppearance.MINIMAL, appAppearance("MINIMAL"))
    }

    @Test
    fun fixedStylesIgnoreWeatherAndDaylightChanges() {
        AppAppearance.entries.filter { it != AppAppearance.WEATHER }.forEach { appearance ->
            val palette = appearancePalette(appearance, WeatherKind.CLEAR, true)
            assertEquals(palette, appearancePalette(appearance, WeatherKind.RAIN, false))
            assertEquals(palette, appearancePalette(appearance, null, true))
        }
    }

    @Test
    fun simpleStylesRenderSolidBackgroundsWithoutGlows() {
        listOf(AppAppearance.MATERIAL, AppAppearance.MINIMAL).forEach { appearance ->
            val palette = appearancePalette(appearance, WeatherKind.CLEAR, true)
            assertTrue(palette.background.size >= 2)
            assertEquals(1, palette.background.distinct().size)
            assertEquals(Color.Transparent, palette.primaryGlow)
            assertEquals(Color.Transparent, palette.secondaryGlow)
        }
    }

    @Test
    fun everyFixedStyleKeepsSecondaryTextReadableWithGlows() {
        AppAppearance.entries.filter { it != AppAppearance.WEATHER }.forEach { appearance ->
            val palette = appearancePalette(appearance, WeatherKind.CLEAR, true)
            val text = when (appearance) {
                AppAppearance.MATERIAL -> Color(0xFFCDDAE7)
                AppAppearance.MINIMAL -> Color(0xFFD4D6D8)
                else -> Color(0xFFDDEAF1)
            }
            palette.background.forEach { background ->
                val lit = palette.secondaryGlow.compositeOver(palette.primaryGlow.compositeOver(background))
                assertEquals(1f, lit.alpha, 0f)
                val contrast = (text.luminance() + 0.05f) / (lit.luminance() + 0.05f)
                assertTrue("$appearance secondary text contrast=$contrast", contrast >= 4.5f)
            }
        }
    }
}
