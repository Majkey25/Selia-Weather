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
fun WeatherTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = WeatherColors,
        content = content,
    )
}
