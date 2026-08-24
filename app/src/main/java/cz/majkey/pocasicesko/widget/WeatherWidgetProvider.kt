package cz.majkey.pocasicesko.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.util.SizeF
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import androidx.core.content.edit
import cz.majkey.pocasicesko.MainActivity
import cz.majkey.pocasicesko.R
import cz.majkey.pocasicesko.data.WeatherCondition
import cz.majkey.pocasicesko.data.WeatherConditionKey
import cz.majkey.pocasicesko.data.WeatherKind
import cz.majkey.pocasicesko.data.WeatherRepository
import cz.majkey.pocasicesko.locale.AppLocale
import cz.majkey.pocasicesko.ui.labelResource
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.roundToInt

class WeatherWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { update(context, manager, it) }
        if (appWidgetIds.isEmpty()) return

        val pendingResult = goAsync()
        Thread(
            {
                try {
                    val repository = WeatherRepository(context)
                    repository.fetchForecastBlocking(repository.lastLocation())
                } catch (_: Exception) {
                    updateAll(context)
                } finally {
                    pendingResult.finish()
                }
            },
            "pocasi-widget-refresh",
        ).start()
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        manager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) {
        update(context, manager, appWidgetId)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        appWidgetIds.forEach { deleteSettings(context, it) }
    }

    companion object {
        private const val COMPACT_WIDTH_DP = 110
        private const val COMPACT_HEIGHT_DP = 40
        private val HOUR_TIME_IDS = intArrayOf(
            R.id.widget_hour_1_time,
            R.id.widget_hour_2_time,
            R.id.widget_hour_3_time,
        )
        private val HOUR_TEMPERATURE_IDS = intArrayOf(
            R.id.widget_hour_1_temp,
            R.id.widget_hour_2_temp,
            R.id.widget_hour_3_temp,
        )
        private val primaryTextSizes = arrayOf(
            R.id.widget_temperature to 34f,
            R.id.widget_condition to 12f,
            R.id.widget_clock to 14f,
            R.id.widget_hour_1_temp to 14f,
            R.id.widget_hour_2_temp to 14f,
            R.id.widget_hour_3_temp to 14f,
        )
        private val secondaryTextSizes = arrayOf(
            R.id.widget_label to 11f,
            R.id.widget_city to 12f,
            R.id.widget_high_low to 11f,
            R.id.widget_date to 10f,
            R.id.widget_precipitation to 10f,
            R.id.widget_wind to 10f,
            R.id.widget_humidity to 10f,
            R.id.widget_update_time to 9f,
            R.id.widget_hour_1_time to 11f,
            R.id.widget_hour_2_time to 11f,
            R.id.widget_hour_3_time to 11f,
        )

        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val component = ComponentName(context, WeatherWidgetProvider::class.java)
            manager.getAppWidgetIds(component).forEach { update(context, manager, it) }
        }

        fun update(context: Context, manager: AppWidgetManager, appWidgetId: Int) {
            runCatching { render(context, manager, appWidgetId) }
                .onFailure { error ->
                    Log.e("ALADINWidget", "Widget $appWidgetId render failed", error)
                    manager.updateAppWidget(appWidgetId, fallbackViews(AppLocale.localized(context)))
                }
        }

        private fun render(context: Context, manager: AppWidgetManager, appWidgetId: Int) {
            val localizedContext = AppLocale.localized(context)
            val weather = localizedContext.getSharedPreferences(WeatherRepository.PREFERENCES_NAME, Context.MODE_PRIVATE)
            val settings = loadSettings(localizedContext, appWidgetId)
            val options = manager.getAppWidgetOptions(appWidgetId)
            val minWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, COMPACT_WIDTH_DP)
            val minHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, COMPACT_HEIGHT_DP)
            @Suppress("DEPRECATION")
            val currentSize = options.getParcelableArrayList<SizeF>(AppWidgetManager.OPTION_APPWIDGET_SIZES)
                ?.firstOrNull()
            val width = currentSize?.width?.roundToInt() ?: minWidth
            val height = currentSize?.height?.roundToInt() ?: minHeight
            val size = widgetSize(width, height)
            val views = RemoteViews(localizedContext.packageName, R.layout.widget_adaptive)

            val city = weather.getString(WeatherRepository.KEY_WIDGET_CITY, null)
                ?: localizedContext.getString(R.string.widget_placeholder_city)
            val temperature = weather.getFloat(WeatherRepository.KEY_WIDGET_TEMPERATURE, Float.NaN)
            val kind = runCatching {
                WeatherKind.valueOf(weather.getString(WeatherRepository.KEY_WIDGET_KIND, "UNKNOWN").orEmpty())
            }.getOrDefault(WeatherKind.UNKNOWN)
            val condition = localizedContext.widgetConditionLabel(
                weather.getString(WeatherRepository.KEY_WIDGET_CONDITION_KEY, null),
                kind,
            )
            val isDay = weather.getBoolean(WeatherRepository.KEY_WIDGET_IS_DAY, true)
            val high = weather.getFloat(WeatherRepository.KEY_WIDGET_HIGH, Float.NaN)
            val low = weather.getFloat(WeatherRepository.KEY_WIDGET_LOW, Float.NaN)
            val hourlyTimes = weather.getString(WeatherRepository.KEY_WIDGET_HOURLY_TIMES, null)
                ?.split('|')
                .orEmpty()
            val hourlyTemperatures = weather.getString(WeatherRepository.KEY_WIDGET_HOURLY_TEMPERATURES, null)
                ?.split('|')
                .orEmpty()
            val precipitation = weather.getInt(WeatherRepository.KEY_WIDGET_PRECIPITATION_PROBABILITY, -1)
            val wind = weather.getFloat(WeatherRepository.KEY_WIDGET_WIND_SPEED, Float.NaN)
            val humidity = weather.getInt(WeatherRepository.KEY_WIDGET_HUMIDITY, -1)
            val updatedAt = weather.getLong(WeatherRepository.KEY_WIDGET_UPDATED_AT, 0L)
            val primaryColor = android.graphics.Color.parseColor(settings.primaryColor)
            val secondaryColor = android.graphics.Color.parseColor(settings.secondaryColor)
            val spacers = widgetAlignmentSpacers(settings.alignment)

            WidgetBackground.apply(localizedContext, views, settings, kind, isDay)
            views.setViewVisibility(
                R.id.widget_alignment_left,
                if (spacers.showLeft) View.VISIBLE else View.GONE,
            )
            views.setViewVisibility(
                R.id.widget_alignment_right,
                if (spacers.showRight) View.VISIBLE else View.GONE,
            )
            views.setTextViewText(
                R.id.widget_temperature,
                if (temperature.isNaN()) localizedContext.getString(R.string.widget_placeholder_temperature)
                else "${temperature.roundToInt()}°",
            )
            views.setTextColor(R.id.widget_temperature, primaryColor)
            views.setTextViewText(R.id.widget_city, city)
            views.setTextColor(R.id.widget_city, secondaryColor)
            views.setTextViewText(R.id.widget_condition, condition)
            views.setTextColor(R.id.widget_condition, primaryColor)
            val showDetails = size != WidgetSize.COMPACT && (settings.showCondition || settings.showRange)
            val showHourly = size == WidgetSize.WIDE && settings.showHourly &&
                hourlyTimes.size >= 3 && hourlyTemperatures.size >= 3
            val showMetrics = size == WidgetSize.TALL &&
                (settings.showPrecipitation || settings.showWind || settings.showHumidity)
            val showUpdate = settings.showUpdatedAt && size != WidgetSize.COMPACT && updatedAt > 0L
            views.setViewVisibility(
                R.id.widget_label,
                if (size != WidgetSize.COMPACT && settings.customLabel.isNotBlank()) View.VISIBLE else View.GONE,
            )
            views.setTextViewText(R.id.widget_label, settings.customLabel)
            views.setTextColor(R.id.widget_label, secondaryColor)
            views.setViewVisibility(
                R.id.widget_city,
                if (size != WidgetSize.COMPACT && settings.showLocation) View.VISIBLE else View.GONE,
            )
            views.setViewVisibility(R.id.widget_details, if (showDetails) View.VISIBLE else View.GONE)
            views.setViewVisibility(
                R.id.widget_condition,
                if (showDetails && settings.showCondition) View.VISIBLE else View.GONE,
            )
            views.setViewVisibility(
                R.id.widget_high_low,
                if (showDetails && settings.showRange) View.VISIBLE else View.GONE,
            )
            views.setViewVisibility(
                R.id.widget_temperature,
                if (settings.showTemperature) View.VISIBLE else View.GONE,
            )
            views.setViewVisibility(R.id.widget_clock, if (settings.showClock) View.VISIBLE else View.GONE)
            views.setTextColor(R.id.widget_clock, primaryColor)
            views.setViewVisibility(R.id.widget_icon, if (settings.showIcon) View.VISIBLE else View.GONE)
            views.setImageViewResource(R.id.widget_icon, iconFor(kind, isDay))
            views.setInt(R.id.widget_icon, "setColorFilter", primaryColor)
            views.setContentDescription(R.id.widget_icon, condition)

            val range = if (high.isNaN() || low.isNaN()) {
                localizedContext.getString(R.string.widget_placeholder_range)
            } else {
                "${high.roundToInt()}° / ${low.roundToInt()}°"
            }
            views.setTextViewText(R.id.widget_high_low, range)
            views.setTextColor(R.id.widget_high_low, secondaryColor)
            views.setViewVisibility(R.id.widget_hourly, if (showHourly) View.VISIBLE else View.GONE)
            views.setViewVisibility(R.id.widget_hourly_divider, if (showHourly) View.VISIBLE else View.GONE)
            views.setInt(
                R.id.widget_hourly_divider,
                "setBackgroundColor",
                android.graphics.Color.parseColor(settings.accentColor),
            )
            views.setViewVisibility(R.id.widget_metrics, if (showMetrics) View.VISIBLE else View.GONE)
            views.setViewVisibility(
                R.id.widget_precipitation,
                if (showMetrics && settings.showPrecipitation) View.VISIBLE else View.GONE,
            )
            views.setViewVisibility(R.id.widget_wind, if (showMetrics && settings.showWind) View.VISIBLE else View.GONE)
            views.setViewVisibility(
                R.id.widget_humidity,
                if (showMetrics && settings.showHumidity) View.VISIBLE else View.GONE,
            )
            views.setTextViewText(
                R.id.widget_precipitation,
                if (precipitation < 0) "--" else "${localizedContext.getString(R.string.precipitation)} $precipitation%",
            )
            views.setTextViewText(
                R.id.widget_wind,
                if (wind.isNaN()) "--" else "${localizedContext.getString(R.string.wind)} ${wind.roundToInt()} km/h",
            )
            views.setTextViewText(
                R.id.widget_humidity,
                if (humidity < 0) "--" else "${localizedContext.getString(R.string.humidity)} $humidity%",
            )
            views.setTextColor(R.id.widget_precipitation, secondaryColor)
            views.setTextColor(R.id.widget_wind, secondaryColor)
            views.setTextColor(R.id.widget_humidity, secondaryColor)
            views.setViewVisibility(
                R.id.widget_date,
                if (size != WidgetSize.COMPACT && settings.showDate) View.VISIBLE else View.GONE,
            )
            views.setTextViewText(
                R.id.widget_date,
                LocalDate.now().format(
                    DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT)
                        .withLocale(localizedContext.resources.configuration.locales[0]),
                ),
            )
            views.setViewVisibility(R.id.widget_update_time, if (showUpdate) View.VISIBLE else View.GONE)
            if (showUpdate) {
                views.setTextViewText(
                    R.id.widget_update_time,
                    Instant.ofEpochMilli(updatedAt)
                        .atZone(ZoneId.systemDefault())
                        .format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)),
                )
            }
            views.setTextColor(R.id.widget_date, secondaryColor)
            views.setTextColor(R.id.widget_update_time, secondaryColor)
            HOUR_TIME_IDS.forEachIndexed { index, id ->
                views.setTextViewText(
                    id,
                    hourlyTimes.getOrNull(index)
                        ?: localizedContext.getString(R.string.widget_placeholder_time),
                )
                views.setTextColor(id, secondaryColor)
            }
            HOUR_TEMPERATURE_IDS.forEachIndexed { index, id ->
                val value = hourlyTemperatures.getOrNull(index)?.let { "$it°" }
                    ?: localizedContext.getString(R.string.widget_placeholder_temperature)
                views.setTextViewText(id, value)
                views.setTextColor(id, primaryColor)
            }
            applyTextStyle(views, settings.textScale, primaryColor, secondaryColor)

            val openApp = Intent(context, MainActivity::class.java)
            val pendingIntent = PendingIntent.getActivity(
                context,
                0,
                openApp,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(R.id.widget_root, pendingIntent)
            manager.updateAppWidget(appWidgetId, views)
        }

        private fun fallbackViews(context: Context): RemoteViews = RemoteViews(
            context.packageName,
            R.layout.widget_adaptive,
        ).apply {
            setInt(R.id.widget_root, "setBackgroundResource", R.drawable.widget_dark)
            setTextViewText(R.id.widget_temperature, context.getString(R.string.widget_placeholder_temperature))
            setTextViewText(R.id.widget_city, context.getString(R.string.widget_placeholder_city))
        }

        fun loadSettings(context: Context, appWidgetId: Int): WidgetSettings {
            val preferences = context.getSharedPreferences(WeatherRepository.PREFERENCES_NAME, Context.MODE_PRIVATE)
            val modeKey = widgetPreferenceKey(appWidgetId, "background_mode")
            val legacyTheme = preferences.getString(widgetPreferenceKey(appWidgetId, "theme"), null)
            val mode = widgetBackgroundMode(preferences.getString(modeKey, null), legacyTheme)
            if (!preferences.contains(modeKey) && legacyTheme != null) {
                preferences.edit { putString(modeKey, mode.name) }
            }
            return WidgetSettings(
                backgroundMode = mode,
                backgroundStart = preferences.getString(widgetPreferenceKey(appWidgetId, "background_start"), DEFAULT_BACKGROUND_START)
                    .orEmpty(),
                backgroundEnd = preferences.getString(widgetPreferenceKey(appWidgetId, "background_end"), DEFAULT_BACKGROUND_END)
                    .orEmpty(),
                primaryColor = preferences.getString(widgetPreferenceKey(appWidgetId, "primary_color"), DEFAULT_PRIMARY_COLOR)
                    .orEmpty(),
                secondaryColor = preferences.getString(
                    widgetPreferenceKey(appWidgetId, "secondary_color"),
                    DEFAULT_SECONDARY_COLOR,
                ).orEmpty(),
                accentColor = preferences.getString(widgetPreferenceKey(appWidgetId, "accent_color"), DEFAULT_ACCENT_COLOR)
                    .orEmpty(),
                opacity = preferences.getInt(widgetPreferenceKey(appWidgetId, "opacity"), 100),
                textScale = preferences.getInt(widgetPreferenceKey(appWidgetId, "text_scale"), 100),
                alignment = widgetAlignment(preferences.getString(widgetPreferenceKey(appWidgetId, "alignment"), null)),
                customLabel = preferences.getString(widgetPreferenceKey(appWidgetId, "label"), "").orEmpty(),
                imageUri = preferences.getString(widgetPreferenceKey(appWidgetId, "image_uri"), "").orEmpty(),
                showClock = preferences.getBoolean(widgetPreferenceKey(appWidgetId, "clock"), true),
                showDate = preferences.getBoolean(widgetPreferenceKey(appWidgetId, "date"), true),
                showLocation = preferences.getBoolean(widgetPreferenceKey(appWidgetId, "location"), true),
                showTemperature = preferences.getBoolean(widgetPreferenceKey(appWidgetId, "temperature"), true),
                showIcon = preferences.getBoolean(widgetPreferenceKey(appWidgetId, "icon"), true),
                showCondition = preferences.getBoolean(widgetPreferenceKey(appWidgetId, "condition"), true),
                showRange = preferences.getBoolean(widgetPreferenceKey(appWidgetId, "range"), true),
                showHourly = preferences.getBoolean(widgetPreferenceKey(appWidgetId, "hourly"), true),
                showPrecipitation = preferences.getBoolean(widgetPreferenceKey(appWidgetId, "precipitation"), false),
                showWind = preferences.getBoolean(widgetPreferenceKey(appWidgetId, "wind"), false),
                showHumidity = preferences.getBoolean(widgetPreferenceKey(appWidgetId, "humidity"), false),
                showUpdatedAt = preferences.getBoolean(widgetPreferenceKey(appWidgetId, "updated_at"), false),
            ).normalized()
        }

        fun saveSettings(context: Context, appWidgetId: Int, settings: WidgetSettings) {
            val normalized = settings.normalized()
            context.getSharedPreferences(WeatherRepository.PREFERENCES_NAME, Context.MODE_PRIVATE).edit {
                putString(widgetPreferenceKey(appWidgetId, "background_mode"), normalized.backgroundMode.name)
                putString(widgetPreferenceKey(appWidgetId, "background_start"), normalized.backgroundStart)
                putString(widgetPreferenceKey(appWidgetId, "background_end"), normalized.backgroundEnd)
                putString(widgetPreferenceKey(appWidgetId, "primary_color"), normalized.primaryColor)
                putString(widgetPreferenceKey(appWidgetId, "secondary_color"), normalized.secondaryColor)
                putString(widgetPreferenceKey(appWidgetId, "accent_color"), normalized.accentColor)
                putInt(widgetPreferenceKey(appWidgetId, "opacity"), normalized.opacity)
                putInt(widgetPreferenceKey(appWidgetId, "text_scale"), normalized.textScale)
                putString(widgetPreferenceKey(appWidgetId, "alignment"), normalized.alignment.name)
                putString(widgetPreferenceKey(appWidgetId, "label"), normalized.customLabel)
                putString(widgetPreferenceKey(appWidgetId, "image_uri"), normalized.imageUri)
                putBoolean(widgetPreferenceKey(appWidgetId, "clock"), normalized.showClock)
                putBoolean(widgetPreferenceKey(appWidgetId, "date"), normalized.showDate)
                putBoolean(widgetPreferenceKey(appWidgetId, "location"), normalized.showLocation)
                putBoolean(widgetPreferenceKey(appWidgetId, "temperature"), normalized.showTemperature)
                putBoolean(widgetPreferenceKey(appWidgetId, "icon"), normalized.showIcon)
                putBoolean(widgetPreferenceKey(appWidgetId, "condition"), normalized.showCondition)
                putBoolean(widgetPreferenceKey(appWidgetId, "range"), normalized.showRange)
                putBoolean(widgetPreferenceKey(appWidgetId, "hourly"), normalized.showHourly)
                putBoolean(widgetPreferenceKey(appWidgetId, "precipitation"), normalized.showPrecipitation)
                putBoolean(widgetPreferenceKey(appWidgetId, "wind"), normalized.showWind)
                putBoolean(widgetPreferenceKey(appWidgetId, "humidity"), normalized.showHumidity)
                putBoolean(widgetPreferenceKey(appWidgetId, "updated_at"), normalized.showUpdatedAt)
            }
        }

        private fun deleteSettings(context: Context, appWidgetId: Int) {
            val preferences = context.getSharedPreferences(WeatherRepository.PREFERENCES_NAME, Context.MODE_PRIVATE)
            val prefix = "widget_settings_${appWidgetId}_"
            preferences.edit {
                preferences.all.keys.filter { it.startsWith(prefix) }.forEach(::remove)
            }
        }

        private fun applyTextStyle(
            views: RemoteViews,
            textScale: Int,
            primaryColor: Int,
            secondaryColor: Int,
        ) {
            primaryTextSizes.forEach { (id, size) ->
                views.setTextViewTextSize(id, TypedValue.COMPLEX_UNIT_SP, size * textScale / 100f)
                views.setTextColor(id, primaryColor)
            }
            secondaryTextSizes.forEach { (id, size) ->
                views.setTextViewTextSize(id, TypedValue.COMPLEX_UNIT_SP, size * textScale / 100f)
                views.setTextColor(id, secondaryColor)
            }
        }

        private fun iconFor(kind: WeatherKind, isDay: Boolean): Int = when (kind) {
            WeatherKind.CLEAR -> if (isDay) R.drawable.ic_weather_clear else R.drawable.ic_weather_cloud
            WeatherKind.PARTLY_CLOUDY, WeatherKind.CLOUDY, WeatherKind.UNKNOWN -> R.drawable.ic_weather_cloud
            WeatherKind.FOG -> R.drawable.ic_weather_fog
            WeatherKind.RAIN -> R.drawable.ic_weather_rain
            WeatherKind.STORM -> R.drawable.ic_weather_storm
            WeatherKind.SNOW -> R.drawable.ic_weather_snow
        }
    }
}

internal fun widgetConditionKey(value: String?): WeatherConditionKey = runCatching {
    WeatherConditionKey.valueOf(value.orEmpty())
}.getOrDefault(WeatherConditionKey.UNKNOWN)

internal fun Context.widgetConditionLabel(conditionKey: String?, kind: WeatherKind): String = conditionKey
    ?.let { getString(WeatherCondition(widgetConditionKey(it), kind).labelResource()) }
    ?: getString(R.string.widget_placeholder_condition)
