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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import cz.majkey.pocasicesko.data.WeatherKind

@Composable
fun WeatherIcon(
    kind: WeatherKind,
    isDay: Boolean,
    contentDescription: String,
    modifier: Modifier = Modifier,
    tint: Color = Color.White,
) {
    if (kind == WeatherKind.MAINLY_CLEAR) {
        Box(modifier.semantics { this.contentDescription = contentDescription }) {
            Icon(
                imageVector = if (isDay) Icons.Rounded.WbSunny else Icons.Rounded.DarkMode,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(0.82f).align(Alignment.TopStart),
                tint = tint,
            )
            Icon(
                imageVector = Icons.Rounded.Cloud,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(0.56f).align(Alignment.BottomEnd),
                tint = tint,
            )
        }
        return
    }
    val icon = when (kind) {
        WeatherKind.CLEAR -> if (isDay) Icons.Rounded.WbSunny else Icons.Rounded.DarkMode
        WeatherKind.MAINLY_CLEAR -> error("Handled above")
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
