package cz.majkey.pocasicesko.widget

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import androidx.compose.ui.graphics.toArgb
import cz.majkey.pocasicesko.R
import cz.majkey.pocasicesko.data.WeatherKind
import cz.majkey.pocasicesko.ui.weatherPalette
import kotlin.math.roundToInt

internal object WidgetBackground {
    fun previewBitmap(
        context: Context,
        settings: WidgetSettings,
        kind: WeatherKind,
        isDay: Boolean,
        widthDp: Int = MAX_BACKGROUND_WIDTH,
        heightDp: Int = MAX_BACKGROUND_HEIGHT,
    ): Bitmap = bitmapFor(context, settings.normalized(), kind, isDay, widthDp, heightDp)

    private fun bitmapFor(
        context: Context,
        settings: WidgetSettings,
        kind: WeatherKind,
        isDay: Boolean,
        widthDp: Int,
        heightDp: Int,
    ): Bitmap {
        val normalized = settings.normalized()
        val source = when (normalized.backgroundMode) {
            WidgetBackgroundMode.APP_STYLE -> appStyleBitmap(context, kind, isDay, widthDp, heightDp)
            WidgetBackgroundMode.AUTOMATIC -> resourceBitmap(context, automaticResource(kind, isDay))
            WidgetBackgroundMode.LIGHT -> resourceBitmap(context, R.drawable.widget_light)
            WidgetBackgroundMode.DARK -> resourceBitmap(context, R.drawable.widget_dark)
            WidgetBackgroundMode.TRANSPARENT -> resourceBitmap(context, R.drawable.widget_transparent)
            WidgetBackgroundMode.SOLID -> solidBitmap(normalized.backgroundStart)
            WidgetBackgroundMode.GRADIENT -> gradientBitmap(normalized)
            WidgetBackgroundMode.CUSTOM_IMAGE -> customImage(context, normalized) ?: gradientBitmap(normalized)
        }
        return try {
            val density = context.resources.displayMetrics.density
            val hostSize = widgetHostSize(widthDp, heightDp)
            val size = backgroundBitmapSize((hostSize.width * density).roundToInt(), (hostSize.height * density).roundToInt())
            val radius = widgetCornerRadiusPixels(normalized.corners, hostSize, size)
            Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888).apply {
                val scale = maxOf(width.toFloat() / source.width, height.toFloat() / source.height)
                val shader = BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                    setLocalMatrix(Matrix().apply {
                        setScale(scale, scale)
                        postTranslate((width - source.width * scale) / 2f, (height - source.height * scale) / 2f)
                    })
                }
                Canvas(this).drawRoundRect(
                    RectF(0f, 0f, width.toFloat(), height.toFloat()), radius, radius,
                    Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { this.shader = shader },
                )
            }
        } finally {
            source.recycle()
        }
    }

    fun apply(
        context: Context,
        views: RemoteViews,
        settings: WidgetSettings,
        kind: WeatherKind,
        isDay: Boolean,
        widthDp: Int = MAX_BACKGROUND_WIDTH,
        heightDp: Int = MAX_BACKGROUND_HEIGHT,
    ) {
        val normalized = settings.normalized()
        applyImage(views, normalized, bitmapFor(context, normalized, kind, isDay, widthDp, heightDp))
    }

    private fun applyImage(views: RemoteViews, settings: WidgetSettings, bitmap: Bitmap) {
        views.setInt(R.id.widget_root, "setBackgroundResource", android.R.color.transparent)
        views.setInt(R.id.widget_background_image, "setBackgroundResource", android.R.color.transparent)
        views.setImageViewBitmap(R.id.widget_background_image, bitmap)
        views.setInt(R.id.widget_background_image, "setImageAlpha", widgetBackgroundAlpha(settings, 255))
        views.setViewVisibility(R.id.widget_background_image, View.VISIBLE)
    }

    private fun resourceBitmap(context: Context, resource: Int): Bitmap = Bitmap.createBitmap(
        MAX_BACKGROUND_WIDTH,
        MAX_BACKGROUND_HEIGHT,
        Bitmap.Config.ARGB_8888,
    ).apply {
        val drawable = requireNotNull(context.getDrawable(resource)).mutate()
        if (drawable is GradientDrawable) drawable.cornerRadius = 0f
        drawable.setBounds(0, 0, width, height)
        drawable.draw(Canvas(this))
    }

    private fun gradientBitmap(settings: WidgetSettings): Bitmap = Bitmap.createBitmap(
        MAX_BACKGROUND_WIDTH,
        MAX_BACKGROUND_HEIGHT,
        Bitmap.Config.ARGB_8888,
    ).apply {
        Canvas(this).drawRect(
            0f,
            0f,
            width.toFloat(),
            height.toFloat(),
            Paint().apply {
                shader = LinearGradient(
                    0f,
                    0f,
                    width.toFloat(),
                    height.toFloat(),
                    Color.parseColor(settings.backgroundStart),
                    Color.parseColor(settings.backgroundEnd),
                    Shader.TileMode.CLAMP,
                )
            },
        )
    }

    private fun appStyleBitmap(context: Context, kind: WeatherKind, isDay: Boolean, widthDp: Int, heightDp: Int): Bitmap {
        val density = context.resources.displayMetrics.density
        val size = backgroundBitmapSize((widthDp * density).roundToInt(), (heightDp * density).roundToInt())
        val palette = weatherPalette(kind, isDay)
        return Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888).apply {
            val canvas = Canvas(this)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.shader = LinearGradient(
                0f, 0f, 0f, height.toFloat(), palette.background.map { it.toArgb() }.toIntArray(), null, Shader.TileMode.CLAMP,
            )
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
            paint.shader = RadialGradient(
                width * 0.14f, height * 0.18f, width * 0.78f, palette.primaryGlow.toArgb(), Color.TRANSPARENT, Shader.TileMode.CLAMP,
            )
            canvas.drawCircle(width * 0.14f, height * 0.18f, width * 0.78f, paint)
            paint.shader = RadialGradient(
                width * 0.9f, height * 0.38f, width * 0.7f, palette.secondaryGlow.toArgb(), Color.TRANSPARENT, Shader.TileMode.CLAMP,
            )
            canvas.drawCircle(width * 0.9f, height * 0.38f, width * 0.7f, paint)
        }
    }

    private fun solidBitmap(color: String): Bitmap = Bitmap.createBitmap(
        1,
        1,
        Bitmap.Config.ARGB_8888,
    ).apply {
        eraseColor(Color.parseColor(color))
    }

    private fun customImage(context: Context, settings: WidgetSettings): Bitmap? {
        val uri = settings.imageUri.takeIf(String::isNotBlank)?.let(Uri::parse)
            ?.takeIf { it.scheme == ContentResolver.SCHEME_CONTENT }
            ?: return null
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, bounds)
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null

            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight)
            }
            val decoded = context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            } ?: return@runCatching null
            val size = backgroundBitmapSize(decoded.width, decoded.height)
            try {
                Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888).also { rendered ->
                    Canvas(rendered).apply {
                        drawBitmap(
                            decoded,
                            null,
                            android.graphics.Rect(0, 0, size.width, size.height),
                            Paint(Paint.FILTER_BITMAP_FLAG),
                        )
                        drawColor(Color.parseColor(settings.backgroundStart) and 0x33FFFFFF)
                    }
                }
            } finally {
                decoded.recycle()
            }
        }.onFailure {
            Log.w("ALADINWidget", "Custom widget background unavailable")
        }.getOrNull()
    }

    private fun sampleSize(width: Int, height: Int): Int {
        var sample = 1
        while (width / sample > MAX_BACKGROUND_WIDTH || height / sample > MAX_BACKGROUND_HEIGHT) {
            sample *= 2
        }
        return sample
    }

    private fun automaticResource(kind: WeatherKind, isDay: Boolean): Int = when {
        !isDay -> R.drawable.widget_night
        kind == WeatherKind.RAIN || kind == WeatherKind.STORM || kind == WeatherKind.SNOW ->
            R.drawable.widget_rain
        else -> R.drawable.widget_day
    }
}
