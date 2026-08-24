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
    fun apply(
        context: Context,
        views: RemoteViews,
        settings: WidgetSettings,
        kind: WeatherKind,
        isDay: Boolean,
    ) {
        val normalized = settings.normalized()
        when (normalized.backgroundMode) {
            WidgetBackgroundMode.AUTOMATIC -> applyResource(views, automaticResource(kind, isDay))
            WidgetBackgroundMode.LIGHT -> applyResource(views, R.drawable.widget_light)
            WidgetBackgroundMode.DARK -> applyResource(views, R.drawable.widget_dark)
            WidgetBackgroundMode.TRANSPARENT -> applyResource(views, R.drawable.widget_transparent)
            WidgetBackgroundMode.SOLID -> applySolid(views, normalized.backgroundStart)
            WidgetBackgroundMode.GRADIENT -> applyGradient(views, normalized)
            WidgetBackgroundMode.CUSTOM_IMAGE -> {
                val image = customImage(context, normalized)
                if (image == null) applyGradient(views, normalized) else applyImage(views, normalized, image)
            }
        }
    }

    private fun applyResource(views: RemoteViews, resource: Int) {
        views.setViewVisibility(R.id.widget_background_image, View.GONE)
        views.setInt(R.id.widget_root, "setBackgroundResource", resource)
    }

    private fun applySolid(views: RemoteViews, color: String) {
        views.setViewVisibility(R.id.widget_background_image, View.GONE)
        views.setInt(R.id.widget_root, "setBackgroundColor", Color.parseColor(color))
    }

    private fun applyGradient(views: RemoteViews, settings: WidgetSettings) {
        applyImage(views, settings, gradientBitmap(settings))
    }

    private fun applyImage(views: RemoteViews, settings: WidgetSettings, bitmap: Bitmap) {
        views.setInt(R.id.widget_root, "setBackgroundColor", Color.parseColor(settings.backgroundStart))
        views.setImageViewBitmap(R.id.widget_background_image, bitmap)
        views.setInt(R.id.widget_background_image, "setImageAlpha", settings.opacity * 255 / 100)
        views.setViewVisibility(R.id.widget_background_image, View.VISIBLE)
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
