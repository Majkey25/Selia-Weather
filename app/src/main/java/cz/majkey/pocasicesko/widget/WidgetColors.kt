package cz.majkey.pocasicesko.widget

import java.util.Locale
import kotlin.math.pow

internal fun widgetHexOrNull(value: String): String? {
    val digits = value.trim().removePrefix("#")
    if ((digits.length != 6 && digits.length != 8) || digits.any { it !in "0123456789abcdefABCDEF" }) return null
    return "#${digits.uppercase(Locale.ROOT)}"
}

internal fun widgetArgbOrNull(value: String): Int? = widgetHexOrNull(value)?.drop(1)?.let {
    (it.toLong(16) or if (it.length == 6) 0xFF000000L else 0L).toInt()
}

internal fun widgetColorChannel(value: String, shift: Int, channel: Int): String {
    require(shift in listOf(0, 8, 16, 24))
    val argb = widgetArgbOrNull(value) ?: -1
    val changed = (argb and (255 shl shift).inv()) or (channel.coerceIn(0, 255) shl shift)
    return String.format(Locale.ROOT, "#%08X", changed)
}

internal fun defaultAutomaticWidgetTextColors(primary: String, secondary: String): Boolean =
    (widgetArgbOrNull(primary) == -1 && widgetArgbOrNull(secondary)?.let {
        (it and 0x00FFFFFF) == 0x00FFFFFF && (it ushr 24) >= 128
    } == true) ||
        (widgetArgbOrNull(primary) == widgetArgbOrNull(LEGACY_LIGHT_PRIMARY_COLOR) &&
            widgetArgbOrNull(secondary) == widgetArgbOrNull(LEGACY_LIGHT_SECONDARY_COLOR))

internal fun widgetContrastRatio(foreground: Int, background: Int): Double {
    fun luminance(argb: Int, under: Int? = null): Double {
        val alpha = if (under == null) 1.0 else (argb ushr 24) / 255.0
        fun channel(shift: Int): Double {
            val component = (((argb ushr shift) and 255) * alpha +
                (((under ?: 0) ushr shift) and 255) * (1.0 - alpha)) / 255.0
            return if (component <= 0.04045) component / 12.92 else ((component + 0.055) / 1.055).pow(2.4)
        }
        return channel(16) * 0.2126 + channel(8) * 0.7152 + channel(0) * 0.0722
    }
    val front = luminance(foreground, background)
    val back = luminance(background)
    return (maxOf(front, back) + 0.05) / (minOf(front, back) + 0.05)
}

internal fun WidgetSettings.automaticTextColorsAvailable(): Boolean = opacity == 100 && when (backgroundMode) {
    WidgetBackgroundMode.TRANSPARENT, WidgetBackgroundMode.CUSTOM_IMAGE -> false
    WidgetBackgroundMode.SOLID -> widgetArgbOrNull(backgroundStart)?.ushr(24) == 255
    WidgetBackgroundMode.GRADIENT -> listOf(backgroundStart, backgroundEnd).all { widgetArgbOrNull(it)?.ushr(24) == 255 }
    else -> true
}

internal fun WidgetSettings.renderedTextColors(): WidgetSettings {
    if (!automaticTextColors || !automaticTextColorsAvailable()) return this
    val backgrounds = when (backgroundMode) {
        WidgetBackgroundMode.LIGHT -> listOf(0xFFF4F1EA.toInt())
        WidgetBackgroundMode.SOLID -> listOfNotNull(widgetArgbOrNull(backgroundStart))
        WidgetBackgroundMode.GRADIENT -> listOfNotNull(widgetArgbOrNull(backgroundStart), widgetArgbOrNull(backgroundEnd))
        WidgetBackgroundMode.TRANSPARENT, WidgetBackgroundMode.CUSTOM_IMAGE -> return this
        else -> return copy(primaryColor = DEFAULT_PRIMARY_COLOR, secondaryColor = DEFAULT_SECONDARY_COLOR)
    }
    if (backgrounds.isEmpty()) return this
    fun contrast(color: Int) = backgrounds.minOf { widgetContrastRatio(color, it) }
    val primary = if (contrast(0xFF000000.toInt()) >= contrast(-1)) 0xFF000000.toInt() else -1
    val muted = (primary and 0x00FFFFFF) or 0xCC000000.toInt()
    val secondary = if (contrast(muted) >= 4.5) muted else primary
    return copy(
        primaryColor = String.format(Locale.ROOT, "#%08X", primary),
        secondaryColor = String.format(Locale.ROOT, "#%08X", secondary),
    )
}
