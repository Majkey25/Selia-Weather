package cz.majkey.pocasicesko.widget

enum class WidgetTheme {
    AUTOMATIC,
    LIGHT,
    DARK,
    TRANSPARENT,
}

data class WidgetSettings(
    val theme: WidgetTheme = WidgetTheme.AUTOMATIC,
    val showClock: Boolean = true,
    val showIcon: Boolean = true,
    val showDetails: Boolean = true,
)

enum class WidgetSize {
    COMPACT,
    STANDARD,
    TALL,
    WIDE,
}

internal fun widgetSize(minWidth: Int, minHeight: Int): WidgetSize = when {
    minWidth >= 320 -> WidgetSize.WIDE
    minHeight >= 120 -> WidgetSize.TALL
    minWidth >= 180 -> WidgetSize.STANDARD
    else -> WidgetSize.COMPACT
}
