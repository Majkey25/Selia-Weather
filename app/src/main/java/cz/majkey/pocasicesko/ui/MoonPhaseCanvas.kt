package cz.majkey.pocasicesko.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import cz.majkey.pocasicesko.astro.MoonDetails
import kotlin.math.min

@Composable
internal fun MoonPhaseCanvas(details: MoonDetails, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val radius = min(size.width, size.height) / 2f - 3f
        val center = Offset(size.width / 2f, size.height / 2f)
        val disk = Rect(
            center.x - radius,
            center.y - radius,
            center.x + radius,
            center.y + radius,
        )
        drawCircle(Color(0xFF111923), radius, center)
        rotate(-details.brightLimbAngleDegrees.toFloat(), center) {
            val terminator = moonTerminatorX(
                details.illuminatedFraction,
                details.waxing,
                radius,
            )
            val light = Path().apply {
                moveTo(center.x, center.y - radius)
                arcTo(
                    disk,
                    -90f,
                    if (details.waxing) 180f else -180f,
                    false,
                )
                cubicTo(
                    center.x + terminator,
                    center.y + radius * 0.55f,
                    center.x + terminator,
                    center.y - radius * 0.55f,
                    center.x,
                    center.y - radius,
                )
                close()
            }
            drawPath(light, Color(0xFFF4F1DF))
        }
        drawCircle(
            Color.White.copy(alpha = 0.32f),
            radius,
            center,
            style = Stroke(width = 2f),
        )
    }
}

internal fun moonTerminatorX(fraction: Double, waxing: Boolean, radius: Float): Float {
    val direction = if (waxing) 1f else -1f
    return direction * radius * (1f - 2f * fraction.coerceIn(0.0, 1.0).toFloat())
}
