package cz.majkey.pocasicesko.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AcUnit
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Dehaze
import androidx.compose.material.icons.rounded.FilterDrama
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import cz.majkey.pocasicesko.data.WeatherKind

@Composable
fun WeatherIcon(
    kind: WeatherKind,
    isDay: Boolean,
    contentDescription: String,
    modifier: Modifier = Modifier,
    tint: Color = Color.White,
) {
    val icon = when (kind) {
        WeatherKind.CLEAR -> if (isDay) Icons.Rounded.WbSunny else Icons.Rounded.DarkMode
        WeatherKind.PARTLY_CLOUDY -> Icons.Rounded.FilterDrama
        WeatherKind.CLOUDY -> Icons.Rounded.Cloud
        WeatherKind.FOG -> Icons.Rounded.Dehaze
        WeatherKind.RAIN -> Icons.Rounded.WaterDrop
        WeatherKind.STORM -> Icons.Rounded.Bolt
        WeatherKind.SNOW -> Icons.Rounded.AcUnit
        WeatherKind.UNKNOWN -> Icons.Rounded.Cloud
    }
    Icon(
        imageVector = icon,
        contentDescription = contentDescription,
        modifier = modifier,
        tint = tint,
    )
}
