package cz.majkey.pocasicesko.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val WeatherColors = darkColorScheme(
    primary = Color.White,
    onPrimary = Color(0xFF15344B),
    surface = Color(0xFF102332),
    onSurface = Color.White,
    surfaceVariant = Color(0xFF294556),
    onSurfaceVariant = Color(0xFFDDEAF1),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
)

@Composable
fun WeatherTheme(appearance: AppAppearance = AppAppearance.WEATHER, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = when (appearance) {
            AppAppearance.MATERIAL -> WeatherColors.copy(
                primary = Color(0xFFABD0F7),
                onPrimary = Color(0xFF0B324B),
                background = Color(0xFF171C22),
                surface = Color(0xFF171C22),
                surfaceVariant = Color(0xFF313D48),
                onSurfaceVariant = Color(0xFFCDDAE7),
            )
            AppAppearance.MINIMAL -> WeatherColors.copy(
                onPrimary = Color(0xFF16191C),
                background = Color(0xFF121416),
                surface = Color(0xFF191C20),
                surfaceVariant = Color(0xFF30343A),
                onSurfaceVariant = Color(0xFFD4D6D8),
            )
            else -> WeatherColors
        },
        content = content,
    )
}
