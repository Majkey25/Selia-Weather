package cz.majkey.pocasicesko.ui

import androidx.compose.ui.graphics.Color
import cz.majkey.pocasicesko.data.WeatherKind

internal data class WeatherPalette(
    val background: List<Color>,
    val primaryGlow: Color,
    val secondaryGlow: Color,
)

internal fun weatherPalette(kind: WeatherKind?, isDay: Boolean): WeatherPalette = when {
    kind == null -> WeatherPalette(
        background = listOf(Color(0xFF17384A), Color(0xFF09131C), Color(0xFF050A0F)),
        primaryGlow = Color(0x333E9FBD),
        secondaryGlow = Color(0x22205A76),
    )
    !isDay -> WeatherPalette(
        background = listOf(Color(0xFF111A33), Color(0xFF080D1A), Color(0xFF04070D)),
        primaryGlow = Color(0x2D536BAA),
        secondaryGlow = Color(0x1F6D4F8A),
    )
    kind == WeatherKind.STORM || kind == WeatherKind.RAIN -> WeatherPalette(
        background = listOf(Color(0xFF263A49), Color(0xFF101D28), Color(0xFF070C11)),
        primaryGlow = Color(0x2D537F92),
        secondaryGlow = Color(0x1F758494),
    )
    kind == WeatherKind.FOG || kind == WeatherKind.CLOUDY -> WeatherPalette(
        background = listOf(Color(0xFF35444E), Color(0xFF17232B), Color(0xFF080D11)),
        primaryGlow = Color(0x2A9AA6A8),
        secondaryGlow = Color(0x1F617984),
    )
    else -> WeatherPalette(
        background = listOf(Color(0xFF1D5D7A), Color(0xFF102A3C), Color(0xFF060E16)),
        primaryGlow = Color(0x35E0A85D),
        secondaryGlow = Color(0x2D3FA0BF),
    )
}
