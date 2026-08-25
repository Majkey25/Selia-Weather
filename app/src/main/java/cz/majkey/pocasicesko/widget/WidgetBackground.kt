package cz.majkey.pocasicesko.widget

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.net.Uri
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import cz.majkey.pocasicesko.R
import cz.majkey.pocasicesko.data.WeatherKind

internal object WidgetBackground {
    fun previewBitmap(
        context: Context,
        settings: WidgetSettings,
        kind: WeatherKind,
        isDay: Boolean,
    ): Bitmap = bitmapFor(context, settings.normalized(), kind, isDay)

    private fun bitmapFor(
        context: Context,
        settings: WidgetSettings,
        kind: WeatherKind,
        isDay: Boolean,
    ): Bitmap {
        val normalized = settings.normalized()
        return when (normalized.backgroundMode) {
            WidgetBackgroundMode.AUTOMATIC -> resourceBitmap(context, automaticResource(kind, isDay))
            WidgetBackgroundMode.LIGHT -> resourceBitmap(context, R.drawable.widget_light)
            WidgetBackgroundMode.DARK -> resourceBitmap(context, R.drawable.widget_dark)
            WidgetBackgroundMode.TRANSPARENT -> resourceBitmap(context, R.drawable.widget_transparent)
            WidgetBackgroundMode.SOLID -> solidBitmap(normalized.backgroundStart)
            WidgetBackgroundMode.GRADIENT -> gradientBitmap(normalized)
            WidgetBackgroundMode.CUSTOM_IMAGE -> customImage(context, normalized) ?: gradientBitmap(normalized)
        }
    }

    fun apply(
        context: Context,
        views: RemoteViews,
        settings: WidgetSettings,
        kind: WeatherKind,
        isDay: Boolean,
    ) {
        val normalized = settings.normalized()
        applyImage(views, normalized, bitmapFor(context, normalized, kind, isDay))
    }

    private fun applyImage(views: RemoteViews, settings: WidgetSettings, bitmap: Bitmap) {
        views.setInt(R.id.widget_root, "setBackgroundResource", android.R.color.transparent)
        views.setImageViewBitmap(R.id.widget_background_image, bitmap)
        views.setInt(R.id.widget_background_image, "setImageAlpha", widgetBackgroundAlpha(settings, 255))
        views.setViewVisibility(R.id.widget_background_image, View.VISIBLE)
    }

    private fun resourceBitmap(context: Context, resource: Int): Bitmap = Bitmap.createBitmap(
        MAX_BACKGROUND_WIDTH,
        MAX_BACKGROUND_HEIGHT,
        Bitmap.Config.ARGB_8888,
    ).apply {
        val drawable = requireNotNull(context.getDrawable(resource))
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
