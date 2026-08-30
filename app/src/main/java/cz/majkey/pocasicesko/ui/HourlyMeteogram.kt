package cz.majkey.pocasicesko.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
    val isDay: Boolean,
)

internal data class HourlyMeteogramGeometry(
    val hours: List<MeteogramHourGeometry>,
)

@Composable
internal fun HourlyMeteogram(
    hours: List<HourlyWeather>,
    columnWidth: Dp,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier.clearAndSetSemantics { }) {
        if (hours.isEmpty() || size.width <= 0f || size.height <= 0f) return@Canvas
        val geometry = calculateHourlyMeteogram(
            hours = hours,
            width = size.width,
            height = size.height,
            columnWidth = columnWidth.toPx(),
        )
        val columnWidthPx = columnWidth.toPx()
        geometry.hours.forEachIndexed { index, hour ->
            drawRect(
                color = if (hour.isDay) DAYLIGHT_TINT else NIGHT_TINT,
                topLeft = Offset(index * columnWidthPx, 0f),
                size = Size(columnWidthPx, size.height),
            )
        }
        val precipitationBaseline = size.height * 0.96f
        drawLine(
            color = Color.White.copy(alpha = 0.08f),
            start = Offset(0f, precipitationBaseline),
            end = Offset(size.width, precipitationBaseline),
            strokeWidth = 1.dp.toPx(),
        )
        geometry.hours.forEach { hour ->
            if (hour.precipitationHeight > 0f) {
                val barWidth = columnWidthPx * 0.38f
                drawRect(
                    color = PRECIPITATION_COLOR.copy(alpha = hour.precipitationAlpha),
                    topLeft = Offset(
                        hour.centerX - barWidth / 2f,
                        precipitationBaseline - hour.precipitationHeight,
                    ),
                    size = Size(barWidth, hour.precipitationHeight),
                )
            }
        }
        if (geometry.hours.size < 2) return@Canvas
        val path = Path().apply {
            geometry.hours.forEachIndexed { index, hour ->
                if (index == 0) moveTo(hour.centerX, hour.temperatureY)
                else lineTo(hour.centerX, hour.temperatureY)
            }
        }
        drawPath(
            path = path,
            brush = Brush.horizontalGradient(
                listOf(Color(0xFF58C8E2), accent, Color(0xFFFFC468)),
            ),
            style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round),
        )
        geometry.hours.forEach { hour ->
            drawCircle(
                color = Color(0xFF111B24),
                radius = 4.5.dp.toPx(),
                center = Offset(hour.centerX, hour.temperatureY),
            )
            drawCircle(accent, radius = 2.4.dp.toPx(), center = Offset(hour.centerX, hour.temperatureY))
        }
    }
}

internal fun calculateHourlyMeteogram(
    hours: List<HourlyWeather>,
    width: Float,
    height: Float,
    columnWidth: Float,
): HourlyMeteogramGeometry {
    require(width.isFinite() && width > 0f)
    require(height.isFinite() && height > 0f)
    require(columnWidth.isFinite() && columnWidth > 0f)
    if (hours.isEmpty()) return HourlyMeteogramGeometry(emptyList())
    require(hours.all {
        it.temperature.isFinite() && it.precipitation.isFinite() && it.precipitation >= 0.0 &&
            it.precipitationProbability in 0..100
    })

    val minimumTemperature = hours.minOf(HourlyWeather::temperature)
    val temperatureRange = (hours.maxOf(HourlyWeather::temperature) - minimumTemperature).coerceAtLeast(1.0)
    val maximumPrecipitation = hours.maxOf(HourlyWeather::precipitation).coerceAtLeast(0.1)
    return HourlyMeteogramGeometry(
        hours.mapIndexed { index, hour ->
            MeteogramHourGeometry(
                centerX = columnWidth * index + columnWidth / 2f,
                temperatureY = height * (
                    0.54f -
                        ((hour.temperature - minimumTemperature) / temperatureRange).toFloat() * 0.42f
                    ),
                precipitationHeight = height * 0.30f *
                    (hour.precipitation / maximumPrecipitation).toFloat().coerceIn(0f, 1f),
                precipitationAlpha = (
                    0.30f + hour.precipitationProbability / 100f * 0.70f
                    ).coerceIn(0.30f, 1f),
                isDay = hour.isDay,
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

private val DAYLIGHT_TINT = Color(0xFFFFC96B).copy(alpha = 0.035f)
private val NIGHT_TINT = Color(0xFF7284D8).copy(alpha = 0.045f)
private val PRECIPITATION_COLOR = Color(0xFF66D7EE)
