package cz.majkey.pocasicesko.ui

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cz.majkey.pocasicesko.data.HourlyWeather

internal data class MeteogramHourGeometry(
    val centerX: Float,
    val temperatureY: Float,
    val precipitationHeight: Float,
    val precipitationAlpha: Float,
)

internal data class HourlyMeteogramGeometry(
    val hours: List<MeteogramHourGeometry>,
)

@Composable
internal fun HourlyMeteogram(
    hours: List<HourlyWeather>,
    columnWidth: Dp,
    modifier: Modifier = Modifier,
    precipitationHours: List<HourlyWeather?> = hours,
) {
    val lineColor = MaterialTheme.colorScheme.primary
    val dividerColor = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier.clearAndSetSemantics { }) {
        if (hours.isEmpty() || size.width <= 0f || size.height <= 0f) return@Canvas
        val geometry = calculateHourlyMeteogram(
            hours = hours,
            width = size.width,
            height = size.height,
            columnWidth = columnWidth.toPx(),
            precipitationHours = precipitationHours,
        )
        val columnWidthPx = columnWidth.toPx()
        val precipitationBaseline = size.height * 0.96f
        drawLine(
            color = dividerColor,
            start = Offset(0f, precipitationBaseline),
            end = Offset(size.width, precipitationBaseline),
            strokeWidth = 1.dp.toPx(),
        )
        geometry.hours.forEach { hour ->
            if (hour.precipitationHeight > 0f) {
                val barWidth = columnWidthPx * 0.38f
                drawRect(
                    color = lineColor.copy(alpha = hour.precipitationAlpha),
                    topLeft = Offset(
                        hour.centerX - barWidth / 2f,
                        precipitationBaseline - hour.precipitationHeight,
                    ),
                    size = Size(barWidth, hour.precipitationHeight),
                )
            }
        }
        if (geometry.hours.size == 1) {
            val hour = geometry.hours.single()
            drawCircle(lineColor, radius = 2.dp.toPx(), center = Offset(hour.centerX, hour.temperatureY))
            return@Canvas
        }
        val path = Path().apply {
            geometry.hours.forEachIndexed { index, hour ->
                if (index == 0) moveTo(hour.centerX, hour.temperatureY)
                else lineTo(hour.centerX, hour.temperatureY)
            }
        }
        drawPath(
            path = path,
            color = lineColor,
            style = Stroke(width = 1.25.dp.toPx(), cap = StrokeCap.Round),
        )
    }
}

internal fun calculateHourlyMeteogram(
    hours: List<HourlyWeather>,
    width: Float,
    height: Float,
    columnWidth: Float,
    precipitationHours: List<HourlyWeather?> = hours,
): HourlyMeteogramGeometry {
    require(width.isFinite() && width > 0f)
    require(height.isFinite() && height > 0f)
    require(columnWidth.isFinite() && columnWidth > 0f)
    require(precipitationHours.size == hours.size)
    if (hours.isEmpty()) return HourlyMeteogramGeometry(emptyList())
    require(hours.all { it.temperature.isFinite() })
    require(precipitationHours.all {
        it == null || it.precipitation.isFinite() && it.precipitation >= 0.0 &&
            (it.precipitationProbability == null || it.precipitationProbability in 0..100)
    })

    val minimumTemperature = hours.minOf(HourlyWeather::temperature)
    val temperatureRange = (hours.maxOf(HourlyWeather::temperature) - minimumTemperature).coerceAtLeast(1.0)
    val maximumPrecipitation = (precipitationHours.mapNotNull { it?.precipitation }.maxOrNull() ?: 0.0).coerceAtLeast(0.1)
    return HourlyMeteogramGeometry(
        hours.mapIndexed { index, hour ->
            val precipitation = precipitationHours[index]
            MeteogramHourGeometry(
                centerX = columnWidth * index + columnWidth / 2f,
                temperatureY = height * (
                    0.54f -
                        ((hour.temperature - minimumTemperature) / temperatureRange).toFloat() * 0.42f
                    ),
                precipitationHeight = height * 0.30f *
                    ((precipitation?.precipitation ?: 0.0) / maximumPrecipitation).toFloat().coerceIn(0f, 1f),
                precipitationAlpha = precipitation?.precipitationProbability?.let {
                    (0.30f + it / 100f * 0.70f).coerceIn(0.30f, 1f)
                } ?: 0.65f,
            )
        },
    )
}

internal fun hourlyAccessibilityDescription(
    time: String,
    condition: String,
    temperature: String,
    precipitationLabel: String,
    windLabel: String,
): String = "$time, $condition, $temperature, $precipitationLabel, $windLabel"

internal fun windArrowRotation(degrees: Int): Float =
    ((Math.floorMod(degrees, 360) + 180) % 360).toFloat()
