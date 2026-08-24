package cz.majkey.pocasicesko.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
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
import kotlin.math.roundToInt

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
        private const val WIDE_THRESHOLD_DP = 320
        private const val SETTINGS_PREFIX = "widget_settings_"
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

        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val component = ComponentName(context, WeatherWidgetProvider::class.java)
            manager.getAppWidgetIds(component).forEach { update(context, manager, it) }
        }

        fun update(context: Context, manager: AppWidgetManager, appWidgetId: Int) {
            val localizedContext = AppLocale.localized(context)
            val weather = localizedContext.getSharedPreferences(WeatherRepository.PREFERENCES_NAME, Context.MODE_PRIVATE)
            val settings = loadSettings(localizedContext, appWidgetId)
            val minWidth = manager.getAppWidgetOptions(appWidgetId)
                .getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, WIDE_THRESHOLD_DP)
            val wide = minWidth >= WIDE_THRESHOLD_DP
            val views = RemoteViews(
                localizedContext.packageName,
                if (wide) R.layout.widget_wide else R.layout.widget_compact,
            )

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

            val textColor = if (settings.theme == WidgetTheme.LIGHT) 0xFF173042.toInt() else 0xFFFFFFFF.toInt()
            val secondaryTextColor = if (settings.theme == WidgetTheme.LIGHT) 0xB3173042.toInt() else 0xCCFFFFFF.toInt()
            views.setInt(R.id.widget_root, "setBackgroundResource", backgroundFor(settings.theme, kind, isDay))
            views.setTextViewText(
                R.id.widget_temperature,
                if (temperature.isNaN()) localizedContext.getString(R.string.widget_placeholder_temperature)
                else "${temperature.roundToInt()}°",
            )
            views.setTextColor(R.id.widget_temperature, textColor)
            views.setTextViewText(R.id.widget_city, city)
            views.setTextColor(R.id.widget_city, secondaryTextColor)
            views.setTextViewText(R.id.widget_condition, condition)
            views.setTextColor(R.id.widget_condition, textColor)
            views.setViewVisibility(R.id.widget_details, if (settings.showDetails) View.VISIBLE else View.GONE)
            views.setViewVisibility(R.id.widget_clock, if (settings.showClock) View.VISIBLE else View.GONE)
            views.setTextColor(R.id.widget_clock, textColor)
            views.setViewVisibility(R.id.widget_icon, if (settings.showIcon) View.VISIBLE else View.GONE)
            views.setImageViewResource(R.id.widget_icon, iconFor(kind, isDay))
            views.setInt(R.id.widget_icon, "setColorFilter", textColor)
            views.setContentDescription(R.id.widget_icon, condition)

            if (wide) {
                views.setTextViewText(
                    R.id.widget_high_low,
                    if (high.isNaN() || low.isNaN()) {
                        localizedContext.getString(R.string.widget_placeholder_range)
                    } else {
                        "${high.roundToInt()}° / ${low.roundToInt()}°"
                    },
                )
                views.setTextColor(R.id.widget_high_low, secondaryTextColor)
                val showHourly = settings.showDetails && hourlyTimes.size >= 3 && hourlyTemperatures.size >= 3
                views.setViewVisibility(R.id.widget_hourly, if (showHourly) View.VISIBLE else View.GONE)
                HOUR_TIME_IDS.forEachIndexed { index, id ->
                    views.setTextViewText(
                        id,
                        hourlyTimes.getOrNull(index)
                            ?: localizedContext.getString(R.string.widget_placeholder_time),
                    )
                    views.setTextColor(id, secondaryTextColor)
                }
                HOUR_TEMPERATURE_IDS.forEachIndexed { index, id ->
                    val value = hourlyTemperatures.getOrNull(index)?.let { "$it°" }
                        ?: localizedContext.getString(R.string.widget_placeholder_temperature)
                    views.setTextViewText(id, value)
                    views.setTextColor(id, textColor)
                }
            }

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

        fun loadSettings(context: Context, appWidgetId: Int): WidgetSettings {
            val preferences = context.getSharedPreferences(WeatherRepository.PREFERENCES_NAME, Context.MODE_PRIVATE)
            val prefix = "$SETTINGS_PREFIX$appWidgetId"
            val theme = runCatching {
                WidgetTheme.valueOf(preferences.getString("${prefix}_theme", WidgetTheme.AUTOMATIC.name).orEmpty())
            }.getOrDefault(WidgetTheme.AUTOMATIC)
            return WidgetSettings(
                theme = theme,
                showClock = preferences.getBoolean("${prefix}_clock", true),
                showIcon = preferences.getBoolean("${prefix}_icon", true),
                showDetails = preferences.getBoolean("${prefix}_details", true),
            )
        }

        fun saveSettings(context: Context, appWidgetId: Int, settings: WidgetSettings) {
            val prefix = "$SETTINGS_PREFIX$appWidgetId"
            context.getSharedPreferences(WeatherRepository.PREFERENCES_NAME, Context.MODE_PRIVATE).edit {
                putString("${prefix}_theme", settings.theme.name)
                putBoolean("${prefix}_clock", settings.showClock)
                putBoolean("${prefix}_icon", settings.showIcon)
                putBoolean("${prefix}_details", settings.showDetails)
            }
        }

        private fun deleteSettings(context: Context, appWidgetId: Int) {
            val prefix = "$SETTINGS_PREFIX$appWidgetId"
            context.getSharedPreferences(WeatherRepository.PREFERENCES_NAME, Context.MODE_PRIVATE).edit {
                remove("${prefix}_theme")
                remove("${prefix}_clock")
                remove("${prefix}_icon")
                remove("${prefix}_details")
            }
        }

        private fun backgroundFor(theme: WidgetTheme, kind: WeatherKind, isDay: Boolean): Int = when (theme) {
            WidgetTheme.LIGHT -> R.drawable.widget_light
            WidgetTheme.DARK -> R.drawable.widget_dark
            WidgetTheme.TRANSPARENT -> R.drawable.widget_transparent
            WidgetTheme.AUTOMATIC -> when {
                !isDay -> R.drawable.widget_night
                kind == WeatherKind.RAIN || kind == WeatherKind.STORM || kind == WeatherKind.SNOW ->
                    R.drawable.widget_rain
                else -> R.drawable.widget_day
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
