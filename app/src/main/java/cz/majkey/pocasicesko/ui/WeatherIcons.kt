package cz.majkey.pocasicesko.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Dehaze
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import cz.majkey.pocasicesko.data.WeatherKind
import cz.majkey.pocasicesko.data.WeatherConditionKey
import cz.majkey.pocasicesko.R
import cz.majkey.pocasicesko.widget.widgetContrastRatio

@Composable
fun WeatherIcon(
    kind: WeatherKind,
    isDay: Boolean,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    conditionKey: WeatherConditionKey? = null,
    weatherCode: Int? = null,
) {
    val appearance = LocalWeatherAppearance.current
    val tint = remember(kind, isDay, appearance) { weatherIconTint(kind, isDay, appearance) }
    if (kind == WeatherKind.RAIN || kind == WeatherKind.SNOW) {
        val resource = if (kind == WeatherKind.SNOW) snowIconResource(weatherCode) else precipitationIconResource(conditionKey, weatherCode)
        Icon(painter = painterResource(resource), contentDescription = contentDescription,
            modifier = modifier, tint = tint)
        return
    }
    if (kind == WeatherKind.MAINLY_CLEAR || kind == WeatherKind.PARTLY_CLOUDY) {
        val cloudFraction = compositeCloudFraction(kind)
        val sunOrMoonFraction = if (kind == WeatherKind.MAINLY_CLEAR) 0.84f else 0.78f
        val sunOrMoonTint = remember(isDay, appearance) { weatherIconTint(WeatherKind.CLEAR, isDay, appearance) }
        Box(
            if (contentDescription == null) {
                modifier
            } else {
                modifier.semantics { this.contentDescription = contentDescription }
            },
        ) {
            Icon(
                imageVector = if (isDay) Icons.Outlined.WbSunny else Icons.Outlined.DarkMode,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(sunOrMoonFraction).align(Alignment.TopStart),
                tint = sunOrMoonTint,
            )
            Icon(
                imageVector = Icons.Filled.Cloud,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(cloudFraction).align(Alignment.BottomEnd),
                tint = tint,
            )
        }
        return
    }
    val icon = when (kind) {
        WeatherKind.CLEAR -> if (isDay) Icons.Outlined.WbSunny else Icons.Outlined.DarkMode
        WeatherKind.MAINLY_CLEAR -> error("Handled above")
        WeatherKind.PARTLY_CLOUDY -> error("Handled above")
        WeatherKind.CLOUDY -> Icons.Outlined.Cloud
        WeatherKind.FOG -> Icons.Outlined.Dehaze
        WeatherKind.RAIN -> error("Handled above")
        WeatherKind.STORM -> Icons.Outlined.Bolt
        WeatherKind.SNOW -> error("Handled above")
        WeatherKind.UNKNOWN -> Icons.Outlined.Cloud
    }
    Icon(
        imageVector = icon,
        contentDescription = contentDescription,
        modifier = modifier,
        tint = tint,
    )
}

internal fun compositeCloudFraction(kind: WeatherKind): Float = when (kind) {
    WeatherKind.MAINLY_CLEAR -> 0.50f
    WeatherKind.PARTLY_CLOUDY -> 0.64f
    else -> error("Only composite conditions have a cloud fraction")
}

internal fun precipitationIconResource(key: WeatherConditionKey?, weatherCode: Int? = null): Int =
    if (key == WeatherConditionKey.DRIZZLE) R.drawable.ic_weather_drizzle else when (weatherCode) {
        61, 66, 80 -> R.drawable.ic_weather_light_rain
        65, 67, 82 -> R.drawable.ic_weather_heavy_rain
        else -> R.drawable.ic_weather_rain
    }

internal fun snowIconResource(weatherCode: Int?): Int = when (weatherCode) {
    68, 69, 83, 84 -> R.drawable.ic_weather_rain_snow
    71, 85 -> R.drawable.ic_weather_light_snow
    75, 86 -> R.drawable.ic_weather_heavy_snow
    else -> R.drawable.ic_weather_snow
}

internal fun weatherIconTint(kind: WeatherKind, isDay: Boolean, appearance: RenderedAppearance): Color {
    val dark = appearance.dark
    val seed = when (kind) {
        WeatherKind.CLEAR -> if (isDay) {
            if (dark) Color(0xFFFFD34F) else Color(0xFFAD8500)
        } else if (dark) Color(0xFFC3C8FF) else Color(0xFF6569A3)
        WeatherKind.MAINLY_CLEAR, WeatherKind.PARTLY_CLOUDY, WeatherKind.CLOUDY, WeatherKind.UNKNOWN ->
            if (dark) Color(0xFF82D6A0) else Color(0xFF2B7844)
        WeatherKind.RAIN -> if (dark) Color(0xFF70C6F2) else Color(0xFF1A6FA4)
        WeatherKind.SNOW -> if (dark) Color(0xFF9DDFF5) else Color(0xFF267A94)
        WeatherKind.STORM -> if (dark) Color(0xFFC4A7FF) else Color(0xFF78509C)
        WeatherKind.FOG -> if (dark) Color(0xFF9DCCC4) else Color(0xFF40786F)
    }
    val backgrounds = gradientContrastSamples(appearance.palette.background) +
        listOf(appearance.colors.surface, appearance.colors.surfaceVariant)
    return (0..10).asSequence().map { lerp(seed, appearance.colors.onSurface, it / 10f) }
        .firstOrNull { candidate -> backgrounds.all { widgetContrastRatio(candidate.toArgb(), it.toArgb()) >= 3.1 } }
        ?: appearance.colors.onSurface
}
