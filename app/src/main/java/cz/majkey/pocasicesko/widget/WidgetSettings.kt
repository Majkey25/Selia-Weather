package cz.majkey.pocasicesko.widget

import kotlin.math.roundToInt

enum class WidgetBackgroundMode {
    AUTOMATIC,
    LIGHT,
    DARK,
    TRANSPARENT,
    SOLID,
    GRADIENT,
    CUSTOM_IMAGE,
}

enum class WidgetAlignment {
    LEFT,
    CENTER,
    RIGHT,
}

data class WidgetSettings(
    val backgroundMode: WidgetBackgroundMode = WidgetBackgroundMode.AUTOMATIC,
    val backgroundStart: String = "#0C1922",
    val backgroundEnd: String = "#28758D",
    val primaryColor: String = "#FFFFFFFF",
    val secondaryColor: String = "#CCFFFFFF",
    val accentColor: String = "#FF66C9DF",
    val opacity: Int = 100,
    val textScale: Int = 100,
    val alignment: WidgetAlignment = WidgetAlignment.LEFT,
    val customLabel: String = "",
    val imageUri: String = "",
    val showClock: Boolean = true,
    val showDate: Boolean = true,
    val showLocation: Boolean = true,
    val showTemperature: Boolean = true,
    val showIcon: Boolean = true,
    val showCondition: Boolean = true,
    val showRange: Boolean = true,
    val showHourly: Boolean = true,
    val showPrecipitation: Boolean = false,
    val showWind: Boolean = false,
    val showHumidity: Boolean = false,
    val showUpdatedAt: Boolean = false,
)

enum class WidgetSize {
    COMPACT,
    STANDARD,
    TALL,
    WIDE,
}

internal data class WidgetBitmapSize(val width: Int, val height: Int)
internal data class WidgetAlignmentSpacers(val showLeft: Boolean, val showRight: Boolean)

internal fun widgetSize(minWidth: Int, minHeight: Int): WidgetSize = when {
    minWidth >= 320 -> WidgetSize.WIDE
    minHeight >= 120 -> WidgetSize.TALL
    minWidth >= 180 -> WidgetSize.STANDARD
    else -> WidgetSize.COMPACT
}

internal fun WidgetSettings.normalized(): WidgetSettings = copy(
    backgroundStart = normalizedWidgetColor(backgroundStart, DEFAULT_BACKGROUND_START),
    backgroundEnd = normalizedWidgetColor(backgroundEnd, DEFAULT_BACKGROUND_END),
    primaryColor = normalizedWidgetColor(primaryColor, DEFAULT_PRIMARY_COLOR),
    secondaryColor = normalizedWidgetColor(secondaryColor, DEFAULT_SECONDARY_COLOR),
    accentColor = normalizedWidgetColor(accentColor, DEFAULT_ACCENT_COLOR),
    opacity = opacity.coerceIn(0, 100),
    textScale = textScale.coerceIn(80, 140),
    customLabel = customLabel.trim().take(40),
    imageUri = imageUri.trim(),
)

internal fun isWidgetColor(value: String): Boolean = WIDGET_COLOR.matches(value)

internal fun normalizedWidgetColor(value: String, fallback: String): String {
    val trimmed = value.trim()
    return if (isWidgetColor(trimmed)) trimmed.uppercase() else fallback
}

internal fun widgetBackgroundMode(value: String?, legacyTheme: String?): WidgetBackgroundMode =
    value?.let { backgroundModeOrNull(it) ?: WidgetBackgroundMode.AUTOMATIC }
        ?: legacyTheme?.let(::legacyBackgroundMode)
        ?: WidgetBackgroundMode.AUTOMATIC

internal fun widgetAlignment(value: String?): WidgetAlignment = runCatching {
    WidgetAlignment.valueOf(value.orEmpty())
}.getOrDefault(WidgetAlignment.LEFT)

internal fun widgetPreferenceKey(appWidgetId: Int, name: String): String =
    "widget_settings_${appWidgetId}_$name"

internal fun backgroundBitmapSize(width: Int, height: Int): WidgetBitmapSize {
    val sourceWidth = width.coerceAtLeast(1)
    val sourceHeight = height.coerceAtLeast(1)
    val scale = minOf(
        1.0,
        MAX_BACKGROUND_WIDTH.toDouble() / sourceWidth,
        MAX_BACKGROUND_HEIGHT.toDouble() / sourceHeight,
    )
    return WidgetBitmapSize(
        width = (sourceWidth * scale).roundToInt().coerceAtLeast(1),
        height = (sourceHeight * scale).roundToInt().coerceAtLeast(1),
    )
}

internal fun widgetAlignmentSpacers(alignment: WidgetAlignment): WidgetAlignmentSpacers = when (alignment) {
    WidgetAlignment.LEFT -> WidgetAlignmentSpacers(showLeft = false, showRight = true)
    WidgetAlignment.CENTER -> WidgetAlignmentSpacers(showLeft = true, showRight = true)
    WidgetAlignment.RIGHT -> WidgetAlignmentSpacers(showLeft = true, showRight = false)
}

enum class WidgetTheme {
    AUTOMATIC,
    LIGHT,
    DARK,
    TRANSPARENT,
}

internal val WidgetSettings.theme: WidgetTheme
    get() = when (backgroundMode) {
        WidgetBackgroundMode.LIGHT -> WidgetTheme.LIGHT
        WidgetBackgroundMode.DARK -> WidgetTheme.DARK
        WidgetBackgroundMode.TRANSPARENT -> WidgetTheme.TRANSPARENT
        else -> WidgetTheme.AUTOMATIC
    }

internal fun WidgetSettings.copy(theme: WidgetTheme): WidgetSettings = copy(
    backgroundMode = when (theme) {
        WidgetTheme.AUTOMATIC -> WidgetBackgroundMode.AUTOMATIC
        WidgetTheme.LIGHT -> WidgetBackgroundMode.LIGHT
        WidgetTheme.DARK -> WidgetBackgroundMode.DARK
        WidgetTheme.TRANSPARENT -> WidgetBackgroundMode.TRANSPARENT
    },
)

internal val WidgetSettings.showDetails: Boolean
    get() = showCondition && showRange && showHourly

internal fun WidgetSettings.copy(showDetails: Boolean): WidgetSettings = copy(
    showCondition = showDetails,
    showRange = showDetails,
    showHourly = showDetails,
)

private fun backgroundModeOrNull(value: String): WidgetBackgroundMode? = runCatching {
    WidgetBackgroundMode.valueOf(value)
}.getOrNull()

private fun legacyBackgroundMode(value: String): WidgetBackgroundMode? = when (value) {
    "AUTOMATIC" -> WidgetBackgroundMode.AUTOMATIC
    "LIGHT" -> WidgetBackgroundMode.LIGHT
    "DARK" -> WidgetBackgroundMode.DARK
    "TRANSPARENT" -> WidgetBackgroundMode.TRANSPARENT
    else -> null
}

private val WIDGET_COLOR = Regex("^#[0-9A-Fa-f]{6}([0-9A-Fa-f]{2})?$")
internal const val MAX_BACKGROUND_WIDTH = 512
internal const val MAX_BACKGROUND_HEIGHT = 256
internal const val DEFAULT_BACKGROUND_START = "#0C1922"
internal const val DEFAULT_BACKGROUND_END = "#28758D"
internal const val DEFAULT_PRIMARY_COLOR = "#FFFFFFFF"
internal const val DEFAULT_SECONDARY_COLOR = "#CCFFFFFF"
internal const val DEFAULT_ACCENT_COLOR = "#FF66C9DF"
