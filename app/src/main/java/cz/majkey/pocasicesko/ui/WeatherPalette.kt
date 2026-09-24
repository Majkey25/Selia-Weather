package cz.majkey.pocasicesko.ui

import androidx.compose.ui.graphics.Color
import cz.majkey.pocasicesko.data.WeatherKind

internal data class WeatherPalette(
    val background: List<Color>,
    val primaryGlow: Color,
    val secondaryGlow: Color,
)

internal fun weatherPalette(kind: WeatherKind?, isDay: Boolean): WeatherPalette = when {
    !isDay -> WeatherPalette(
        background = listOf(Color(0xFF111A33), Color(0xFF080D1A), Color(0xFF04070D)),
        primaryGlow = Color(0x2D536BAA),
        secondaryGlow = Color(0x1F6D4F8A),
    )
    kind == null || kind == WeatherKind.UNKNOWN -> WeatherPalette(
        background = listOf(Color(0xFF17384A), Color(0xFF09131C), Color(0xFF050A0F)),
        primaryGlow = Color(0x333E9FBD),
        secondaryGlow = Color(0x22205A76),
    )
    kind == WeatherKind.STORM || kind == WeatherKind.RAIN -> WeatherPalette(
        background = listOf(Color(0xFF263A49), Color(0xFF101D28), Color(0xFF070C11)),
        primaryGlow = Color(0x2D537F92),
        secondaryGlow = Color(0x1F758494),
    )
    kind == WeatherKind.CLEAR || kind == WeatherKind.MAINLY_CLEAR -> SunsetPalette
    else -> WeatherPalette(
        background = listOf(Color(0xFF244F6D), Color(0xFF142C42), Color(0xFF080F1B)),
        primaryGlow = Color(0x2A739AB6),
        secondaryGlow = Color(0x1F45779F),
    )
}

internal fun appearancePalette(appearance: AppAppearance, kind: WeatherKind?, isDay: Boolean): WeatherPalette =
    when (appearance) {
        AppAppearance.WEATHER -> weatherPalette(kind, isDay)
        AppAppearance.OCEAN -> WeatherPalette(
            background = listOf(Color(0xFF1D5D7A), Color(0xFF102A3C), Color(0xFF060E16)),
            primaryGlow = Color(0x2A43A2C3),
            secondaryGlow = Color(0x224E6CBA),
        )
        AppAppearance.SUNSET -> SunsetPalette
        AppAppearance.FOREST -> WeatherPalette(
            background = listOf(Color(0xFF234D43), Color(0xFF132D29), Color(0xFF080F10)),
            primaryGlow = Color(0x246B9C70),
            secondaryGlow = Color(0x1F3C8F87),
        )
        AppAppearance.MATERIAL -> WeatherPalette(
            background = listOf(Color(0xFF171C22), Color(0xFF171C22)),
            primaryGlow = Color.Transparent,
            secondaryGlow = Color.Transparent,
        )
        AppAppearance.MINIMAL -> WeatherPalette(
            background = listOf(Color(0xFF121416), Color(0xFF121416)),
            primaryGlow = Color.Transparent,
            secondaryGlow = Color.Transparent,
        )
    }

private val SunsetPalette = WeatherPalette(
    background = listOf(Color(0xFF743C2E), Color(0xFF3F2026), Color(0xFF160F1B)),
    primaryGlow = Color(0x24ED9B50),
    secondaryGlow = Color(0x18C85840),
)
