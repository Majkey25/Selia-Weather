package cz.majkey.pocasicesko.ui

import androidx.compose.ui.graphics.toArgb
import cz.majkey.pocasicesko.data.WeatherKind
import org.junit.Assert.assertEquals
import org.junit.Test

class WeatherPaletteTest {
    @Test
    fun sharedPalettePreservesExistingDayNightRainAndCloudColors() {
        assertEquals(listOf(0xFF1D5D7A, 0xFF102A3C, 0xFF060E16).map(Long::toInt), weatherPalette(WeatherKind.CLEAR, true).background.map { it.toArgb() })
        assertEquals(listOf(0xFF111A33, 0xFF080D1A, 0xFF04070D).map(Long::toInt), weatherPalette(WeatherKind.CLEAR, false).background.map { it.toArgb() })
        assertEquals(listOf(0xFF263A49, 0xFF101D28, 0xFF070C11).map(Long::toInt), weatherPalette(WeatherKind.RAIN, true).background.map { it.toArgb() })
        assertEquals(listOf(0xFF35444E, 0xFF17232B, 0xFF080D11).map(Long::toInt), weatherPalette(WeatherKind.CLOUDY, true).background.map { it.toArgb() })
        assertEquals(weatherPalette(WeatherKind.CLOUDY, true), weatherPalette(WeatherKind.FOG, true))
        assertEquals(weatherPalette(WeatherKind.RAIN, true), weatherPalette(WeatherKind.STORM, true))
        assertEquals(0x35E0A85D, weatherPalette(WeatherKind.CLEAR, true).primaryGlow.toArgb())
        assertEquals(0x2D536BAA, weatherPalette(WeatherKind.CLEAR, false).primaryGlow.toArgb())
        assertEquals(0xFF17384A.toInt(), weatherPalette(null, true).background.first().toArgb())
    }
}
