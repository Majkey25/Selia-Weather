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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import cz.majkey.pocasicesko.data.WeatherKind
import cz.majkey.pocasicesko.data.WeatherConditionKey
import cz.majkey.pocasicesko.R

@Composable
fun WeatherIcon(
    kind: WeatherKind,
    isDay: Boolean,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = Color.White,
    conditionKey: WeatherConditionKey? = null,
    weatherCode: Int? = null,
) {
    if (kind == WeatherKind.RAIN || kind == WeatherKind.SNOW) {
        val resource = if (kind == WeatherKind.SNOW) snowIconResource(weatherCode) else precipitationIconResource(conditionKey, weatherCode)
        Icon(painter = painterResource(resource), contentDescription = contentDescription,
            modifier = modifier, tint = tint)
        return
    }
    if (kind == WeatherKind.MAINLY_CLEAR || kind == WeatherKind.PARTLY_CLOUDY) {
        val cloudFraction = compositeCloudFraction(kind)
        val dark = LocalWeatherAppearance.current.dark
        val cloudTint = if (dark) compositeCloudTint(kind, tint) else Color(0xFF477B9C)
        val sunOrMoonFraction = if (kind == WeatherKind.MAINLY_CLEAR) 0.84f else 0.78f
        val sunOrMoonTint = if (isDay) { if (dark) Color(0xFFFFD477) else Color(0xFFA06416) }
            else if (dark) Color(0xFFDDE6FF) else Color(0xFF6569A3)
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
                tint = cloudTint.copy(alpha = 1f),
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

internal fun compositeCloudTint(kind: WeatherKind, requested: Color): Color = when (kind) {
    WeatherKind.MAINLY_CLEAR -> Color(0xFF9FB7FF)
    WeatherKind.PARTLY_CLOUDY -> requested
    else -> error("Only composite conditions have a cloud tint")
}
