package cz.majkey.pocasicesko.widget

import android.content.Context
import android.content.SharedPreferences
import cz.majkey.pocasicesko.R
import cz.majkey.pocasicesko.astro.MoonPhaseKey
import cz.majkey.pocasicesko.data.WeatherKind
import cz.majkey.pocasicesko.data.WeatherRepository
import cz.majkey.pocasicesko.ui.labelResource
import cz.majkey.pocasicesko.units.WeatherUnitFormatter
import kotlin.math.roundToInt
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

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

enum class WidgetFontStyle {
    SYSTEM,
    MATERIAL,
    ROUNDED,
    LIGHT,
}

enum class WidgetPreset {
    MINIMAL,
    MATERIAL,
    PIXEL,
    CUPERTINO,
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
    val fontStyle: WidgetFontStyle = WidgetFontStyle.SYSTEM,
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
    val showDewPoint: Boolean = false,
    val showPressure: Boolean = false,
    val showVisibility: Boolean = false,
    val showWindGusts: Boolean = false,
    val showMoon: Boolean = false,
    val showUpdatedAt: Boolean = false,
)

enum class WidgetSize {
    COMPACT,
    STANDARD,
    TALL,
    WIDE,
}

internal enum class WidgetWorkKind {
    UPDATE,
    APPLY,
    DELETE,
}

internal enum class WidgetWorkAdmission {
    ENQUEUE,
    REPLACE_PENDING,
    DROP_INCOMING,
    REJECT_INCOMING,
}

internal enum class WidgetDeleteMergeTarget {
    ACTIVE,
    QUEUED,
    NEW,
}

internal class WidgetApplyState {
    private val lock = Any()
    private var active = false

    fun start(): Boolean = synchronized(lock) {
        if (active) return@synchronized false
        active = true
        true
    }

    fun cancel() = synchronized(lock) { active = false }

    fun isActive(): Boolean = synchronized(lock) { active }

    fun commitIfActive(commit: () -> Boolean): Boolean = synchronized(lock) {
        active && commit()
    }
}

internal data class WidgetBitmapSize(val width: Int, val height: Int)
internal data class WidgetHostSize(val width: Int, val height: Int)
internal data class WidgetDataAvailability(
    val hourlyAvailable: Boolean,
    val precipitationAvailable: Boolean,
    val windAvailable: Boolean,
    val humidityAvailable: Boolean,
    val hasUpdatedAt: Boolean,
)
internal data class WidgetPreviewBackgroundKey(
    val mode: WidgetBackgroundMode,
    val start: String,
    val end: String,
    val opacity: Int,
    val imageUri: String,
    val kind: WeatherKind,
    val isDay: Boolean,
    val width: Int = MAX_BACKGROUND_WIDTH,
    val height: Int = MAX_BACKGROUND_HEIGHT,
)
internal data class WidgetAdvancedData(
    val dewPoint: String?,
    val pressure: String?,
    val visibility: String?,
    val windGusts: String?,
    val moon: String?,
)
internal data class WidgetContentVisibility(
    val showLabel: Boolean,
    val showLocation: Boolean,
    val showCondition: Boolean,
    val showRange: Boolean,
    val showTemperature: Boolean,
    val showClock: Boolean,
    val showIcon: Boolean,
    val showHourly: Boolean,
    val showMetrics: Boolean,
    val showPrecipitation: Boolean,
    val showWind: Boolean,
    val showHumidity: Boolean,
    val showDate: Boolean,
    val showUpdatedAt: Boolean,
)

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

internal fun widgetColorInput(value: String): String = value.take(MAX_WIDGET_COLOR_INPUT_LENGTH)

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

internal fun widgetFontStyle(value: String?): WidgetFontStyle = runCatching {
    WidgetFontStyle.valueOf(value.orEmpty())
}.getOrDefault(WidgetFontStyle.SYSTEM)

internal fun widgetPresetSettings(preset: WidgetPreset, current: WidgetSettings): WidgetSettings = when (preset) {
    WidgetPreset.MINIMAL -> current.copy(
        backgroundMode = WidgetBackgroundMode.TRANSPARENT,
        primaryColor = "#FFFFFFFF",
        secondaryColor = "#CCFFFFFF",
        accentColor = "#00FFFFFF",
        opacity = 100,
        textScale = 100,
        fontStyle = WidgetFontStyle.LIGHT,
        alignment = WidgetAlignment.LEFT,
        showClock = true,
        showDate = true,
        showLocation = true,
        showTemperature = true,
        showIcon = false,
        showCondition = false,
        showRange = false,
        showHourly = false,
        showPrecipitation = false,
        showWind = false,
        showHumidity = false,
        showDewPoint = false,
        showPressure = false,
        showVisibility = false,
        showWindGusts = false,
        showMoon = false,
        showUpdatedAt = false,
    )
    WidgetPreset.MATERIAL -> current.copy(
        backgroundMode = WidgetBackgroundMode.AUTOMATIC,
        primaryColor = DEFAULT_PRIMARY_COLOR,
        secondaryColor = DEFAULT_SECONDARY_COLOR,
        accentColor = DEFAULT_ACCENT_COLOR,
        opacity = 100,
        textScale = 100,
        fontStyle = WidgetFontStyle.MATERIAL,
        alignment = WidgetAlignment.LEFT,
        showClock = true,
        showDate = true,
        showLocation = true,
        showTemperature = true,
        showIcon = true,
        showCondition = true,
        showRange = true,
        showHourly = true,
        showPrecipitation = false,
        showWind = false,
        showHumidity = false,
        showDewPoint = false,
        showPressure = false,
        showVisibility = false,
        showWindGusts = false,
        showMoon = false,
        showUpdatedAt = false,
    )
    WidgetPreset.PIXEL -> current.copy(
        backgroundMode = WidgetBackgroundMode.GRADIENT,
        backgroundStart = "#101A31",
        backgroundEnd = "#324E7A",
        primaryColor = "#FFFFFFFF",
        secondaryColor = "#D9FFFFFF",
        accentColor = "#FF8AB4F8",
        opacity = 100,
        textScale = 100,
        fontStyle = WidgetFontStyle.ROUNDED,
        alignment = WidgetAlignment.LEFT,
        showClock = true,
        showDate = true,
        showLocation = true,
        showTemperature = true,
        showIcon = true,
        showCondition = true,
        showRange = true,
        showHourly = true,
        showPrecipitation = true,
        showWind = true,
        showHumidity = true,
        showDewPoint = false,
        showPressure = false,
        showVisibility = false,
        showWindGusts = false,
        showMoon = false,
        showUpdatedAt = false,
    )
    WidgetPreset.CUPERTINO -> current.copy(
        backgroundMode = WidgetBackgroundMode.GRADIENT,
        backgroundStart = "#243B55",
        backgroundEnd = "#576A8C",
        primaryColor = "#FFFFFFFF",
        secondaryColor = "#D9FFFFFF",
        accentColor = "#80FFFFFF",
        opacity = 92,
        textScale = 105,
        fontStyle = WidgetFontStyle.LIGHT,
        alignment = WidgetAlignment.CENTER,
        showClock = true,
        showDate = true,
        showLocation = true,
        showTemperature = true,
        showIcon = true,
        showCondition = false,
        showRange = true,
        showHourly = false,
        showPrecipitation = false,
        showWind = false,
        showHumidity = false,
        showDewPoint = false,
        showPressure = false,
        showVisibility = false,
        showWindGusts = false,
        showMoon = false,
        showUpdatedAt = false,
    )
}.normalized()

internal fun migratedWidgetVisibility(
    newValue: Boolean?,
    legacyDetails: Boolean?,
    defaultValue: Boolean,
): Boolean = newValue ?: legacyDetails ?: defaultValue

internal fun migratedWidgetColor(
    mode: WidgetBackgroundMode,
    hasStoredColor: Boolean,
    storedColor: String?,
    fallback: String,
): String = when {
    hasStoredColor -> storedColor ?: fallback
    mode != WidgetBackgroundMode.LIGHT -> fallback
    fallback == DEFAULT_PRIMARY_COLOR -> LEGACY_LIGHT_PRIMARY_COLOR
    fallback == DEFAULT_SECONDARY_COLOR -> LEGACY_LIGHT_SECONDARY_COLOR
    else -> fallback
}

internal fun widgetOpacityAlpha(baseAlpha: Int, opacity: Int): Int =
    baseAlpha.coerceIn(0, 255) * opacity.coerceIn(0, 100) / 100

internal fun widgetBackgroundAlpha(settings: WidgetSettings, baseAlpha: Int): Int =
    widgetOpacityAlpha(baseAlpha, settings.opacity)

internal fun widgetDataAvailability(
    hourlyTimes: List<String>,
    hourlyTemperatures: List<String>,
    precipitation: Int,
    wind: Float,
    humidity: Int,
    updatedAt: Long,
): WidgetDataAvailability = WidgetDataAvailability(
    hourlyAvailable = hourlyTimes.size >= 3 && hourlyTemperatures.size >= 3 &&
        hourlyTimes.take(3).all(String::isNotBlank) && hourlyTemperatures.take(3).all(String::isNotBlank),
    precipitationAvailable = precipitation in 0..100,
    windAvailable = wind.isFinite() && wind >= 0f,
    humidityAvailable = humidity in 0..100,
    hasUpdatedAt = updatedAt > 0L,
)

internal fun widgetPreviewBackgroundKey(
    settings: WidgetSettings,
    kind: WeatherKind,
    isDay: Boolean,
): WidgetPreviewBackgroundKey = settings.normalized().let {
    WidgetPreviewBackgroundKey(
        mode = it.backgroundMode,
        start = it.backgroundStart,
        end = it.backgroundEnd,
        opacity = it.opacity,
        imageUri = it.imageUri,
        kind = kind,
        isDay = isDay,
    )
}

internal fun widgetAdvancedText(settings: WidgetSettings, data: WidgetAdvancedData): String = listOfNotNull(
    data.dewPoint.takeIf { settings.showDewPoint },
    data.pressure.takeIf { settings.showPressure },
    data.visibility.takeIf { settings.showVisibility },
    data.windGusts.takeIf { settings.showWindGusts },
    data.moon.takeIf { settings.showMoon },
).joinToString(" · ")

internal fun widgetAdvancedVisible(size: WidgetSize, text: String): Boolean =
    (size == WidgetSize.TALL || size == WidgetSize.WIDE) && text.isNotBlank()

internal fun SharedPreferences.widgetAdvancedData(
    context: Context,
    unitFormatter: WeatherUnitFormatter,
): WidgetAdvancedData = WidgetAdvancedData(
    dewPoint = formattedFloat(WeatherRepository.KEY_WIDGET_DEW_POINT) {
        "${context.getString(R.string.dew_point)} ${unitFormatter.temperature(it)}"
    },
    pressure = formattedFloat(WeatherRepository.KEY_WIDGET_PRESSURE) {
        "${context.getString(R.string.pressure)} ${unitFormatter.pressure(it)}"
    },
    visibility = formattedFloat(WeatherRepository.KEY_WIDGET_VISIBILITY) {
        "${context.getString(R.string.visibility)} ${unitFormatter.visibility(it)}"
    },
    windGusts = formattedFloat(WeatherRepository.KEY_WIDGET_WIND_GUSTS) {
        "${context.getString(R.string.wind_gusts)} ${unitFormatter.windSpeed(it)}"
    },
    moon = widgetMoon(context),
)

private fun SharedPreferences.formattedFloat(key: String, formatter: (Double) -> String): String? =
    getFloat(key, Float.NaN).takeIf(Float::isFinite)?.toDouble()?.let(formatter)

private fun SharedPreferences.widgetMoon(context: Context): String? {
    val phase = runCatching {
        MoonPhaseKey.valueOf(getString(WeatherRepository.KEY_WIDGET_MOON_PHASE, null).orEmpty())
    }.getOrNull() ?: return null
    val illumination = getFloat(WeatherRepository.KEY_WIDGET_MOON_ILLUMINATION, Float.NaN)
        .takeIf(Float::isFinite) ?: return null
    return "${context.getString(R.string.moon)} ${context.getString(phase.labelResource())} " +
        "${(illumination * 100).roundToInt()}%"
}

internal fun widgetContentVisibility(
    settings: WidgetSettings,
    size: WidgetSize,
    availability: WidgetDataAvailability,
    heightDp: Int = 120,
): WidgetContentVisibility {
    val showDetails = size != WidgetSize.COMPACT && (settings.showCondition || settings.showRange)
    val metricsFit = size == WidgetSize.TALL || (size == WidgetSize.WIDE && heightDp >= 120)
    val showPrecipitation = metricsFit && settings.showPrecipitation && availability.precipitationAvailable
    val showWind = metricsFit && settings.showWind && availability.windAvailable
    val showHumidity = metricsFit && settings.showHumidity && availability.humidityAvailable
    val showMetrics = showPrecipitation || showWind || showHumidity
    val showHourly = size == WidgetSize.WIDE && settings.showHourly && availability.hourlyAvailable &&
        (!showMetrics || heightDp >= 180)
    return WidgetContentVisibility(
        showLabel = size != WidgetSize.COMPACT && settings.customLabel.isNotBlank(),
        showLocation = size != WidgetSize.COMPACT && settings.showLocation,
        showCondition = showDetails && settings.showCondition,
        showRange = showDetails && settings.showRange,
        showTemperature = settings.showTemperature,
        showClock = settings.showClock,
        showIcon = settings.showIcon,
        showHourly = showHourly,
        showMetrics = showMetrics,
        showPrecipitation = showPrecipitation,
        showWind = showWind,
        showHumidity = showHumidity,
        showDate = size != WidgetSize.COMPACT && settings.showDate,
        showUpdatedAt = settings.showUpdatedAt && size != WidgetSize.COMPACT && availability.hasUpdatedAt,
    )
}

internal fun widgetImageUriReferenced(values: Map<String, *>, uri: String): Boolean =
    uri.isNotBlank() && values.any { (key, value) ->
        key.endsWith("_image_uri") && value == uri
    }

internal fun requiresWidgetImageGrant(initialUri: String, imageUri: String): Boolean =
    imageUri.isNotBlank() && imageUri != initialUri

internal fun widgetApplySucceeded(commitSucceeded: Boolean, renderSucceeded: Boolean): Boolean = commitSucceeded

internal fun widgetApplyInProgress(started: Boolean, callbackDelivered: Boolean): Boolean =
    started && !callbackDelivered

internal fun widgetApplyCanCommit(active: Boolean, widgetExists: Boolean): Boolean = active && widgetExists

internal fun widgetCanFinishActivity(destroyed: Boolean, finishing: Boolean): Boolean = !destroyed && !finishing

internal fun widgetDeleteIds(current: IntArray, incoming: IntArray): IntArray =
    (current.asList() + incoming.asList()).distinct().toIntArray()

internal fun widgetDeleteMergeTarget(activeComplete: Boolean, queuedDelete: Boolean): WidgetDeleteMergeTarget = when {
    !activeComplete -> WidgetDeleteMergeTarget.ACTIVE
    queuedDelete -> WidgetDeleteMergeTarget.QUEUED
    else -> WidgetDeleteMergeTarget.NEW
}

internal fun finishWidgetDelete(cleanup: () -> Unit, onComplete: () -> Unit) {
    try {
        cleanup()
    } finally {
        onComplete()
    }
}

internal fun widgetImageUris(values: Map<String, *>): Set<String> = values
    .filterKeys { it.endsWith("_image_uri") }
    .values
    .filterIsInstance<String>()
    .filter(String::isNotBlank)
    .toSet()

internal fun widgetOrphanImageUris(persisted: Set<String>, referenced: Set<String>): Set<String> =
    persisted - referenced

internal fun widgetWorkAdmission(
    pending: WidgetWorkKind?,
    incoming: WidgetWorkKind,
): WidgetWorkAdmission = when {
    incoming == WidgetWorkKind.DELETE -> WidgetWorkAdmission.ENQUEUE
    pending == null -> WidgetWorkAdmission.ENQUEUE
    pending == WidgetWorkKind.UPDATE -> WidgetWorkAdmission.REPLACE_PENDING
    incoming == WidgetWorkKind.UPDATE -> WidgetWorkAdmission.DROP_INCOMING
    else -> WidgetWorkAdmission.REJECT_INCOMING
}

internal fun widgetUpdatedAt(epochMillis: Long, zoneId: ZoneId, locale: Locale): String =
    Instant.ofEpochMilli(epochMillis)
        .atZone(zoneId)
        .format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale))

internal fun widgetDate(date: LocalDate, locale: Locale): String =
    date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT).withLocale(locale))

internal fun widgetClock(time: LocalTime, is24Hour: Boolean): String =
    time.format(DateTimeFormatter.ofPattern(if (is24Hour) "HH:mm" else "h:mm"))

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

internal fun widgetHostSize(minWidth: Int, minHeight: Int): WidgetHostSize = WidgetHostSize(
    minWidth.coerceAtLeast(1),
    minHeight.coerceAtLeast(1),
)

internal fun widgetFontLayout(fontStyle: WidgetFontStyle, alignment: WidgetAlignment): Int = when (fontStyle) {
    WidgetFontStyle.SYSTEM -> when (alignment) {
        WidgetAlignment.LEFT -> R.layout.widget_font_system
        WidgetAlignment.CENTER -> R.layout.widget_font_system_center
        WidgetAlignment.RIGHT -> R.layout.widget_font_system_right
    }
    WidgetFontStyle.MATERIAL -> when (alignment) {
        WidgetAlignment.LEFT -> R.layout.widget_font_material
        WidgetAlignment.CENTER -> R.layout.widget_font_material_center
        WidgetAlignment.RIGHT -> R.layout.widget_font_material_right
    }
    WidgetFontStyle.ROUNDED -> when (alignment) {
        WidgetAlignment.LEFT -> R.layout.widget_font_rounded
        WidgetAlignment.CENTER -> R.layout.widget_font_rounded_center
        WidgetAlignment.RIGHT -> R.layout.widget_font_rounded_right
    }
    WidgetFontStyle.LIGHT -> when (alignment) {
        WidgetAlignment.LEFT -> R.layout.widget_font_light
        WidgetAlignment.CENTER -> R.layout.widget_font_light_center
        WidgetAlignment.RIGHT -> R.layout.widget_font_light_right
    }
}

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
internal const val LEGACY_LIGHT_PRIMARY_COLOR = "#FF173042"
internal const val LEGACY_LIGHT_SECONDARY_COLOR = "#B3173042"
internal const val MAX_WIDGET_COLOR_INPUT_LENGTH = 9
