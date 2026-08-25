package cz.majkey.pocasicesko.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
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
import cz.majkey.pocasicesko.units.MeasurementUnits
import cz.majkey.pocasicesko.units.WeatherUnitFormatter
import java.time.LocalDate
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

class WeatherWidgetProvider : AppWidgetProvider() {
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (isWidgetLocaleChange(intent.action)) runBroadcastWork { updateAllNow(context) }
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        runBroadcastWork {
            reconcilePersistedImageGrants(context)
            appWidgetIds.forEach { renderWidget(context, manager, it) }
            if (appWidgetIds.isNotEmpty()) {
                runCatching {
                    val repository = WeatherRepository(context)
                    repository.fetchForecastBlocking(repository.lastLocation())
                }.onFailure { updateAllNow(context) }
            }
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        manager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) {
        runBroadcastWork { renderWidget(context, manager, appWidgetId) }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        val pendingResult = goAsync()
        val finished = AtomicBoolean()
        val finish = { if (finished.compareAndSet(false, true)) pendingResult.finish() }
        if (!submitDeleteWork(context.applicationContext, appWidgetIds, finish)) finish()
    }

    private fun runBroadcastWork(work: () -> Unit) {
        val pendingResult = goAsync()
        val finished = AtomicBoolean()
        val finish = { if (finished.compareAndSet(false, true)) pendingResult.finish() }
        if (!submitWidgetWork(
            kind = WidgetWorkKind.UPDATE,
            work = {
                try {
                    work()
                } finally {
                    finish()
                }
            },
            onDiscard = finish,
        )) finish()
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
            R.id.widget_advanced to 10f,
            R.id.widget_update_time to 9f,
            R.id.widget_hour_1_time to 11f,
            R.id.widget_hour_2_time to 11f,
            R.id.widget_hour_3_time to 11f,
        )

        fun updateAll(context: Context) {
            submitWidgetWork(WidgetWorkKind.UPDATE, work = { updateAllNow(context.applicationContext) })
        }

        fun reconcileImageGrants(context: Context) {
            submitWidgetWork(WidgetWorkKind.UPDATE, work = {
                reconcilePersistedImageGrants(context.applicationContext)
            })
        }

        fun applySettings(
            context: Context,
            appWidgetId: Int,
            settings: WidgetSettings,
            needsImageGrant: Boolean,
            canCommit: () -> Boolean,
            commitIfActive: (() -> Boolean) -> Boolean,
            onComplete: (Boolean) -> Unit,
        ): Boolean = submitWidgetWork(
            kind = WidgetWorkKind.APPLY,
            work = {
                var grantTaken = false
                val committed = runCatching {
                    check(widgetApplyCanCommit(canCommit(), widgetExists(context, appWidgetId)))
                    if (needsImageGrant) {
                        context.contentResolver.takePersistableUriPermission(
                            Uri.parse(settings.imageUri),
                            Intent.FLAG_GRANT_READ_URI_PERMISSION,
                        )
                        grantTaken = true
                    }
                    check(widgetExists(context, appWidgetId))
                    commitIfActive { saveSettings(context, appWidgetId, settings) }
                }.getOrDefault(false)
                if (!committed && grantTaken) releaseImageIfUnused(context, settings.imageUri)
                val rendered = if (committed) runCatching {
                    renderWidget(
                        context.applicationContext,
                        AppWidgetManager.getInstance(context),
                        appWidgetId,
                    )
                    true
                }.getOrDefault(false) else false
                onComplete(widgetApplySucceeded(committed, rendered))
            },
            onDiscard = { onComplete(false) },
        )

        private fun updateAllNow(context: Context, fallbackOnFailure: Boolean = true) {
            val manager = AppWidgetManager.getInstance(context)
            val component = ComponentName(context, WeatherWidgetProvider::class.java)
            manager.getAppWidgetIds(component).forEach {
                renderWidget(context, manager, it, fallbackOnFailure)
            }
        }

        private fun renderWidget(
            context: Context,
            manager: AppWidgetManager,
            appWidgetId: Int,
            fallbackOnFailure: Boolean = true,
        ) {
            synchronized(widgetRenderLock) {
                if (!fallbackOnFailure) return render(context, manager, appWidgetId)
                runCatching { render(context, manager, appWidgetId) }.onFailure { error ->
                    Log.e("ALADINWidget", "Widget $appWidgetId render failed", error)
                    manager.updateAppWidget(appWidgetId, fallbackViews(AppLocale.localized(context)))
                }
            }
        }

        private fun render(context: Context, manager: AppWidgetManager, appWidgetId: Int) {
            val localizedContext = AppLocale.localized(context)
            val unitFormatter = WeatherUnitFormatter(
                MeasurementUnits.current(context),
                localizedContext.resources.configuration.locales[0],
            )
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
            val advancedText = widgetAdvancedText(
                settings,
                weather.widgetAdvancedData(localizedContext, unitFormatter),
            )
            val updatedAt = weather.getLong(WeatherRepository.KEY_WIDGET_UPDATED_AT, 0L)
            val availability = widgetDataAvailability(
                hourlyTimes,
                hourlyTemperatures,
                precipitation,
                wind,
                humidity,
                updatedAt,
            )
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
                else unitFormatter.temperature(temperature.toDouble()),
            )
            views.setTextColor(R.id.widget_temperature, primaryColor)
            views.setTextViewText(R.id.widget_city, city)
            views.setTextColor(R.id.widget_city, secondaryColor)
            views.setTextViewText(R.id.widget_condition, condition)
            views.setTextColor(R.id.widget_condition, primaryColor)
            val visibility = widgetContentVisibility(
                settings = settings,
                size = size,
                availability = availability,
            )
            views.setViewVisibility(
                R.id.widget_label,
                if (visibility.showLabel) View.VISIBLE else View.GONE,
            )
            views.setTextViewText(R.id.widget_label, settings.customLabel)
            views.setTextColor(R.id.widget_label, secondaryColor)
            views.setViewVisibility(
                R.id.widget_city,
                if (visibility.showLocation) View.VISIBLE else View.GONE,
            )
            views.setViewVisibility(
                R.id.widget_details,
                if (visibility.showCondition || visibility.showRange) View.VISIBLE else View.GONE,
            )
            views.setViewVisibility(
                R.id.widget_condition,
                if (visibility.showCondition) View.VISIBLE else View.GONE,
            )
            views.setViewVisibility(
                R.id.widget_high_low,
                if (visibility.showRange) View.VISIBLE else View.GONE,
            )
            views.setViewVisibility(
                R.id.widget_temperature,
                if (visibility.showTemperature) View.VISIBLE else View.GONE,
            )
            views.setViewVisibility(R.id.widget_clock, if (visibility.showClock) View.VISIBLE else View.GONE)
            views.setTextColor(R.id.widget_clock, primaryColor)
            views.setViewVisibility(R.id.widget_icon, if (visibility.showIcon) View.VISIBLE else View.GONE)
            views.setImageViewResource(R.id.widget_icon, iconFor(kind, isDay))
            views.setInt(R.id.widget_icon, "setColorFilter", primaryColor)
            views.setContentDescription(R.id.widget_icon, condition)

            val range = if (high.isNaN() || low.isNaN()) {
                localizedContext.getString(R.string.widget_placeholder_range)
            } else {
                "${unitFormatter.temperature(high.toDouble())} / ${unitFormatter.temperature(low.toDouble())}"
            }
            views.setTextViewText(R.id.widget_high_low, range)
            views.setTextColor(R.id.widget_high_low, secondaryColor)
            views.setViewVisibility(R.id.widget_hourly, if (visibility.showHourly) View.VISIBLE else View.GONE)
            views.setViewVisibility(R.id.widget_hourly_divider, if (visibility.showHourly) View.VISIBLE else View.GONE)
            views.setInt(
                R.id.widget_hourly_divider,
                "setBackgroundColor",
                android.graphics.Color.parseColor(settings.accentColor),
            )
            views.setViewVisibility(R.id.widget_metrics, if (visibility.showMetrics) View.VISIBLE else View.GONE)
            views.setViewVisibility(
                R.id.widget_precipitation,
                if (visibility.showPrecipitation) View.VISIBLE else View.GONE,
            )
            views.setViewVisibility(R.id.widget_wind, if (visibility.showWind) View.VISIBLE else View.GONE)
            views.setViewVisibility(
                R.id.widget_humidity,
                if (visibility.showHumidity) View.VISIBLE else View.GONE,
            )
            views.setTextViewText(
                R.id.widget_precipitation,
                if (precipitation < 0) "--" else "${localizedContext.getString(R.string.precipitation)} $precipitation%",
            )
            views.setTextViewText(
                R.id.widget_wind,
                if (wind.isNaN()) "--" else "${localizedContext.getString(R.string.wind)} ${unitFormatter.windSpeed(wind.toDouble())}",
            )
            views.setTextViewText(
                R.id.widget_humidity,
                if (humidity < 0) "--" else "${localizedContext.getString(R.string.humidity)} $humidity%",
            )
            views.setTextColor(R.id.widget_precipitation, secondaryColor)
            views.setTextColor(R.id.widget_wind, secondaryColor)
            views.setTextColor(R.id.widget_humidity, secondaryColor)
            views.setViewVisibility(
                R.id.widget_advanced,
                if (widgetAdvancedVisible(size, advancedText)) View.VISIBLE else View.GONE,
            )
            views.setTextViewText(R.id.widget_advanced, advancedText)
            views.setTextColor(R.id.widget_advanced, secondaryColor)
            views.setViewVisibility(
                R.id.widget_date,
                if (visibility.showDate) View.VISIBLE else View.GONE,
            )
            views.setTextViewText(
                R.id.widget_date,
                widgetDate(LocalDate.now(), localizedContext.resources.configuration.locales[0]),
            )
            views.setViewVisibility(R.id.widget_update_time, if (visibility.showUpdatedAt) View.VISIBLE else View.GONE)
            if (visibility.showUpdatedAt) {
                views.setTextViewText(
                    R.id.widget_update_time,
                    widgetUpdatedAt(
                        updatedAt,
                        java.time.ZoneId.systemDefault(),
                        localizedContext.resources.configuration.locales[0],
                    ),
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
                val value = hourlyTemperatures.getOrNull(index)?.toDoubleOrNull()?.let(unitFormatter::temperature)
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
            WidgetBackground.apply(context, this, WidgetSettings(backgroundMode = WidgetBackgroundMode.DARK), WeatherKind.UNKNOWN, true)
            setTextViewText(R.id.widget_temperature, context.getString(R.string.widget_placeholder_temperature))
            setTextViewText(R.id.widget_city, context.getString(R.string.widget_placeholder_city))
        }

        fun loadSettings(context: Context, appWidgetId: Int): WidgetSettings {
            val preferences = context.getSharedPreferences(WeatherRepository.PREFERENCES_NAME, Context.MODE_PRIVATE)
            val modeKey = widgetPreferenceKey(appWidgetId, "background_mode")
            val legacyTheme = preferences.getString(widgetPreferenceKey(appWidgetId, "theme"), null)
            val legacyDetailsKey = widgetPreferenceKey(appWidgetId, "details")
            val legacyDetails = preferences.getBoolean(legacyDetailsKey, true).takeIf {
                preferences.contains(legacyDetailsKey)
            }
            val mode = widgetBackgroundMode(preferences.getString(modeKey, null), legacyTheme)
            if (!preferences.contains(modeKey) && legacyTheme != null) {
                preferences.edit { putString(modeKey, mode.name) }
            }
            fun storedBoolean(name: String): Boolean? {
                val key = widgetPreferenceKey(appWidgetId, name)
                return preferences.getBoolean(key, false).takeIf { preferences.contains(key) }
            }
            fun migratedVisibility(name: String, defaultValue: Boolean): Boolean = migratedWidgetVisibility(
                newValue = storedBoolean(name),
                legacyDetails = legacyDetails,
                defaultValue = defaultValue,
            )
            fun migratedColor(name: String, fallback: String): String {
                val key = widgetPreferenceKey(appWidgetId, name)
                return migratedWidgetColor(
                    mode = mode,
                    hasStoredColor = preferences.contains(key),
                    storedColor = preferences.getString(key, null),
                    fallback = fallback,
                )
            }
            return WidgetSettings(
                backgroundMode = mode,
                backgroundStart = preferences.getString(widgetPreferenceKey(appWidgetId, "background_start"), DEFAULT_BACKGROUND_START)
                    .orEmpty(),
                backgroundEnd = preferences.getString(widgetPreferenceKey(appWidgetId, "background_end"), DEFAULT_BACKGROUND_END)
                    .orEmpty(),
                primaryColor = migratedColor("primary_color", DEFAULT_PRIMARY_COLOR),
                secondaryColor = migratedColor("secondary_color", DEFAULT_SECONDARY_COLOR),
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
                showCondition = migratedVisibility("condition", true),
                showRange = migratedVisibility("range", true),
                showHourly = migratedVisibility("hourly", true),
                showPrecipitation = preferences.getBoolean(widgetPreferenceKey(appWidgetId, "precipitation"), false),
                showWind = preferences.getBoolean(widgetPreferenceKey(appWidgetId, "wind"), false),
                showHumidity = preferences.getBoolean(widgetPreferenceKey(appWidgetId, "humidity"), false),
                showDewPoint = preferences.getBoolean(widgetPreferenceKey(appWidgetId, "dew_point"), false),
                showPressure = preferences.getBoolean(widgetPreferenceKey(appWidgetId, "pressure"), false),
                showVisibility = preferences.getBoolean(widgetPreferenceKey(appWidgetId, "visibility"), false),
                showWindGusts = preferences.getBoolean(widgetPreferenceKey(appWidgetId, "wind_gusts"), false),
                showMoon = preferences.getBoolean(widgetPreferenceKey(appWidgetId, "moon"), false),
                showUpdatedAt = preferences.getBoolean(widgetPreferenceKey(appWidgetId, "updated_at"), false),
            ).normalized()
        }

        fun saveSettings(context: Context, appWidgetId: Int, settings: WidgetSettings): Boolean {
            val normalized = settings.normalized()
            val preferences = context.getSharedPreferences(WeatherRepository.PREFERENCES_NAME, Context.MODE_PRIVATE)
            val imageKey = widgetPreferenceKey(appWidgetId, "image_uri")
            val oldImageUri = preferences.getString(imageKey, null).orEmpty()
            val saved = preferences.edit()
                .putString(widgetPreferenceKey(appWidgetId, "background_mode"), normalized.backgroundMode.name)
                .putString(widgetPreferenceKey(appWidgetId, "background_start"), normalized.backgroundStart)
                .putString(widgetPreferenceKey(appWidgetId, "background_end"), normalized.backgroundEnd)
                .putString(widgetPreferenceKey(appWidgetId, "primary_color"), normalized.primaryColor)
                .putString(widgetPreferenceKey(appWidgetId, "secondary_color"), normalized.secondaryColor)
                .putString(widgetPreferenceKey(appWidgetId, "accent_color"), normalized.accentColor)
                .putInt(widgetPreferenceKey(appWidgetId, "opacity"), normalized.opacity)
                .putInt(widgetPreferenceKey(appWidgetId, "text_scale"), normalized.textScale)
                .putString(widgetPreferenceKey(appWidgetId, "alignment"), normalized.alignment.name)
                .putString(widgetPreferenceKey(appWidgetId, "label"), normalized.customLabel)
                .putString(imageKey, normalized.imageUri)
                .putBoolean(widgetPreferenceKey(appWidgetId, "clock"), normalized.showClock)
                .putBoolean(widgetPreferenceKey(appWidgetId, "date"), normalized.showDate)
                .putBoolean(widgetPreferenceKey(appWidgetId, "location"), normalized.showLocation)
                .putBoolean(widgetPreferenceKey(appWidgetId, "temperature"), normalized.showTemperature)
                .putBoolean(widgetPreferenceKey(appWidgetId, "icon"), normalized.showIcon)
                .putBoolean(widgetPreferenceKey(appWidgetId, "condition"), normalized.showCondition)
                .putBoolean(widgetPreferenceKey(appWidgetId, "range"), normalized.showRange)
                .putBoolean(widgetPreferenceKey(appWidgetId, "hourly"), normalized.showHourly)
                .putBoolean(widgetPreferenceKey(appWidgetId, "precipitation"), normalized.showPrecipitation)
                .putBoolean(widgetPreferenceKey(appWidgetId, "wind"), normalized.showWind)
                .putBoolean(widgetPreferenceKey(appWidgetId, "humidity"), normalized.showHumidity)
                .putBoolean(widgetPreferenceKey(appWidgetId, "dew_point"), normalized.showDewPoint)
                .putBoolean(widgetPreferenceKey(appWidgetId, "pressure"), normalized.showPressure)
                .putBoolean(widgetPreferenceKey(appWidgetId, "visibility"), normalized.showVisibility)
                .putBoolean(widgetPreferenceKey(appWidgetId, "wind_gusts"), normalized.showWindGusts)
                .putBoolean(widgetPreferenceKey(appWidgetId, "moon"), normalized.showMoon)
                .putBoolean(widgetPreferenceKey(appWidgetId, "updated_at"), normalized.showUpdatedAt)
                .commit()
            if (saved && oldImageUri != normalized.imageUri) releaseImageIfUnused(context, oldImageUri)
            return saved
        }

        private fun deleteSettings(context: Context, appWidgetId: Int) {
            val preferences = context.getSharedPreferences(WeatherRepository.PREFERENCES_NAME, Context.MODE_PRIVATE)
            val prefix = "widget_settings_${appWidgetId}_"
            val oldImageUri = preferences.getString(widgetPreferenceKey(appWidgetId, "image_uri"), null).orEmpty()
            val deleted = preferences.edit().apply {
                preferences.all.keys.filter { it.startsWith(prefix) }.forEach(::remove)
            }.commit()
            if (deleted) releaseImageIfUnused(context, oldImageUri)
        }

        fun releaseImageIfUnused(context: Context, uriValue: String) {
            if (uriValue.isBlank()) return
            val preferences = context.getSharedPreferences(WeatherRepository.PREFERENCES_NAME, Context.MODE_PRIVATE)
            if (widgetImageUriReferenced(preferences.all, uriValue)) return
            runCatching {
                context.contentResolver.releasePersistableUriPermission(
                    Uri.parse(uriValue),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
        }

        private fun reconcilePersistedImageGrants(context: Context) {
            val preferences = context.getSharedPreferences(WeatherRepository.PREFERENCES_NAME, Context.MODE_PRIVATE)
            val orphaned = widgetOrphanImageUris(
                context.contentResolver.persistedUriPermissions.mapTo(mutableSetOf()) { it.uri.toString() },
                widgetImageUris(preferences.all),
            )
            orphaned.forEach { releaseImageIfUnused(context, it) }
        }

        private fun widgetExists(context: Context, appWidgetId: Int): Boolean =
            AppWidgetManager.getInstance(context)
                .getAppWidgetIds(ComponentName(context, WeatherWidgetProvider::class.java))
                .contains(appWidgetId)

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

        private fun submitWidgetWork(
            kind: WidgetWorkKind,
            work: () -> Unit,
            onDiscard: () -> Unit = {},
        ): Boolean =
            synchronized(widgetWorkLock) {
                val task = WidgetWork(kind, work, onDiscard)
                try {
                    widgetWorker.execute(task)
                    true
                } catch (_: RejectedExecutionException) {
                    val queued = widgetWorker.queue.filterIsInstance<WidgetWork>()
                    when (kind) {
                        WidgetWorkKind.UPDATE -> {
                            queued.filter { it.kind == WidgetWorkKind.UPDATE }.forEach {
                                widgetWorker.queue.remove(it)
                                it.discard()
                            }
                            submitAfterAdmission(task)
                        }
                        WidgetWorkKind.APPLY -> {
                            if (queued.any { it.kind == WidgetWorkKind.APPLY }) {
                                task.discard()
                                false
                            } else {
                                queued.filter { it.kind == WidgetWorkKind.UPDATE }.forEach {
                                    widgetWorker.queue.remove(it)
                                    it.discard()
                                }
                                submitAfterAdmission(task)
                            }
                        }
                        WidgetWorkKind.DELETE -> error("Delete work has dedicated admission")
                    }
                } catch (error: RuntimeException) {
                    Log.w("ALADINWidget", "Widget worker did not start", error)
                    task.discard()
                    false
                }
            }

        private fun submitDeleteWork(
            context: Context,
            appWidgetIds: IntArray,
            onComplete: () -> Unit,
        ): Boolean = synchronized(widgetWorkLock) {
            val queued = widgetWorker.queue.filterIsInstance<WidgetWork>()
            if (activeDelete?.merge(appWidgetIds, onComplete) == true) return true
            if (queued.filterIsInstance<DeleteWork>().firstOrNull()?.merge(appWidgetIds, onComplete) == true) return true
            queued.filter { it.kind == WidgetWorkKind.UPDATE }.forEach {
                widgetWorker.queue.remove(it)
                it.discard()
            }
            submitAfterAdmission(DeleteWork(context, appWidgetIds, onComplete))
        }

        private fun submitAfterAdmission(task: WidgetWork): Boolean = runCatching {
            widgetWorker.execute(task)
        }.onFailure {
            task.discard()
        }.isSuccess

        private val widgetRenderLock = Any()
        private val widgetWorkLock = Any()
        private var activeDelete: DeleteWork? = null
        private val widgetWorker = ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            ArrayBlockingQueue<Runnable>(3),
            { runnable -> Thread(runnable, "pocasi-widget-worker") },
            ThreadPoolExecutor.AbortPolicy(),
        )

        private open class WidgetWork(
            val kind: WidgetWorkKind,
            private val work: () -> Unit,
            private val onDiscard: () -> Unit,
        ) : Runnable {
            override fun run() = work()

            open fun discard() = onDiscard()
        }

        private class DeleteWork(
            private val context: Context,
            appWidgetIds: IntArray,
            onComplete: () -> Unit,
        ) : WidgetWork(WidgetWorkKind.DELETE, {}, onComplete) {
            private val lock = Any()
            private var appWidgetIds = appWidgetIds
            private val completions = mutableListOf(onComplete)
            private var complete = false

            fun merge(ids: IntArray, onComplete: () -> Unit): Boolean = synchronized(lock) {
                if (complete) false else {
                    appWidgetIds = widgetDeleteIds(appWidgetIds, ids)
                    completions += onComplete
                    true
                }
            }

            override fun run() {
                synchronized(widgetWorkLock) { activeDelete = this }
                var callbacks = emptyList<() -> Unit>()
                try {
                    while (true) {
                        val ids = synchronized(lock) {
                            appWidgetIds.also { appWidgetIds = intArrayOf() }
                        }
                        ids.forEach { appWidgetId ->
                            runCatching { deleteSettings(context, appWidgetId) }
                                .onFailure { Log.w("ALADINWidget", "Widget $appWidgetId cleanup failed", it) }
                        }
                        val finishedCallbacks = synchronized(lock) {
                            if (appWidgetIds.isNotEmpty()) null else {
                                complete = true
                                completions.toList().also { completions.clear() }
                            }
                        }
                        if (finishedCallbacks != null) {
                            callbacks = finishedCallbacks
                            finishWidgetDelete(
                                { reconcilePersistedImageGrants(context) },
                                { callbacks.forEach { it() } },
                            )
                            return
                        }
                    }
                } catch (error: RuntimeException) {
                    Log.w("ALADINWidget", "Widget cleanup failed", error)
                } finally {
                    synchronized(widgetWorkLock) {
                        if (activeDelete === this) activeDelete = null
                    }
                    if (callbacks.isEmpty()) {
                        callbacks = synchronized(lock) {
                            complete = true
                            completions.toList().also { completions.clear() }
                        }
                    }
                    callbacks.forEach { it() }
                }
            }

            override fun discard() {
                val callbacks = synchronized(lock) {
                    complete = true
                    completions.toList().also { completions.clear() }
                }
                callbacks.forEach { it() }
            }
        }
    }
}

internal fun widgetConditionKey(value: String?): WeatherConditionKey = runCatching {
    WeatherConditionKey.valueOf(value.orEmpty())
}.getOrDefault(WeatherConditionKey.UNKNOWN)

internal fun isWidgetLocaleChange(action: String?): Boolean = action == Intent.ACTION_APPLICATION_LOCALE_CHANGED ||
    action == Intent.ACTION_LOCALE_CHANGED

internal fun Context.widgetConditionLabel(conditionKey: String?, kind: WeatherKind): String = conditionKey
    ?.let { getString(WeatherCondition(widgetConditionKey(it), kind).labelResource()) }
    ?: getString(R.string.widget_placeholder_condition)
