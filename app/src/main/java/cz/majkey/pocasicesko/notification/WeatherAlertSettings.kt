package cz.majkey.pocasicesko.notification

import android.content.Context
import cz.majkey.pocasicesko.R

enum class WeatherAlertCategory(val channelId: String, val notificationId: Int, val labelResource: Int) {
    RAIN("weather_rain", 7101, R.string.notification_rain),
    COLD("weather_cold", 7102, R.string.notification_cold),
    DROP("weather_temperature_drop", 7103, R.string.notification_drop),
    HEAT("weather_heat", 7104, R.string.notification_heat),
    WIND("weather_wind", 7105, R.string.notification_wind),
    UV("weather_uv", 7106, R.string.notification_uv),
    OFFICIAL(WeatherAlerts.CHANNEL_OFFICIAL, WeatherAlerts.NOTIFICATION_ID_OFFICIAL, R.string.notification_official),
}

data class WeatherAlertSettings(
    val rainEnabled: Boolean = true,
    val coldEnabled: Boolean = false,
    val dropEnabled: Boolean = false,
    val heatEnabled: Boolean = false,
    val windEnabled: Boolean = false,
    val uvEnabled: Boolean = false,
    val officialWarningsEnabled: Boolean = true,
    val coldCelsius: Double = 5.0,
    val dropCelsius: Double = 8.0,
    val heatCelsius: Double = 30.0,
    val windKmh: Double = 50.0,
    val uvIndex: Double = 6.0,
    val lookAheadHours: Int = 6,
) {
    val anyEnabled: Boolean get() = WeatherAlertCategory.entries.any(::isEnabled)

    fun isEnabled(category: WeatherAlertCategory): Boolean = when (category) {
        WeatherAlertCategory.RAIN -> rainEnabled
        WeatherAlertCategory.COLD -> coldEnabled
        WeatherAlertCategory.DROP -> dropEnabled
        WeatherAlertCategory.HEAT -> heatEnabled
        WeatherAlertCategory.WIND -> windEnabled
        WeatherAlertCategory.UV -> uvEnabled
        WeatherAlertCategory.OFFICIAL -> officialWarningsEnabled
    }

    fun withEnabled(category: WeatherAlertCategory, enabled: Boolean): WeatherAlertSettings = when (category) {
        WeatherAlertCategory.RAIN -> copy(rainEnabled = enabled)
        WeatherAlertCategory.COLD -> copy(coldEnabled = enabled)
        WeatherAlertCategory.DROP -> copy(dropEnabled = enabled)
        WeatherAlertCategory.HEAT -> copy(heatEnabled = enabled)
        WeatherAlertCategory.WIND -> copy(windEnabled = enabled)
        WeatherAlertCategory.UV -> copy(uvEnabled = enabled)
        WeatherAlertCategory.OFFICIAL -> copy(officialWarningsEnabled = enabled)
    }

    fun normalized(): WeatherAlertSettings = copy(
        coldCelsius = coldCelsius.takeIf(Double::isFinite)?.coerceIn(-30.0, 15.0) ?: 5.0,
        dropCelsius = dropCelsius.takeIf(Double::isFinite)?.coerceIn(3.0, 20.0) ?: 8.0,
        heatCelsius = heatCelsius.takeIf(Double::isFinite)?.coerceIn(20.0, 45.0) ?: 30.0,
        windKmh = windKmh.takeIf(Double::isFinite)?.coerceIn(20.0, 120.0) ?: 50.0,
        uvIndex = uvIndex.takeIf(Double::isFinite)?.coerceIn(3.0, 11.0) ?: 6.0,
        lookAheadHours = lookAheadHours.coerceIn(1, 12),
    )

    fun save(context: Context) {
        val settings = normalized()
        val editor = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
        WeatherAlertCategory.entries.forEach { editor.putBoolean(it.channelId, settings.isEnabled(it)) }
        editor.putFloat("cold_celsius", settings.coldCelsius.toFloat())
            .putFloat("drop_celsius", settings.dropCelsius.toFloat())
            .putFloat("heat_celsius", settings.heatCelsius.toFloat())
            .putFloat("wind_kmh", settings.windKmh.toFloat())
            .putFloat("uv_index", settings.uvIndex.toFloat())
            .putInt("look_ahead_hours", settings.lookAheadHours)
            .apply()
        WeatherAlerts.cancelDisabled(context, settings)
    }

    companion object {
        fun load(context: Context): WeatherAlertSettings {
            val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            return WeatherAlertSettings(
                rainEnabled = preferences.getBoolean(WeatherAlertCategory.RAIN.channelId, true),
                coldEnabled = preferences.getBoolean(WeatherAlertCategory.COLD.channelId, false),
                dropEnabled = preferences.getBoolean(WeatherAlertCategory.DROP.channelId, false),
                heatEnabled = preferences.getBoolean(WeatherAlertCategory.HEAT.channelId, false),
                windEnabled = preferences.getBoolean(WeatherAlertCategory.WIND.channelId, false),
                uvEnabled = preferences.getBoolean(WeatherAlertCategory.UV.channelId, false),
                officialWarningsEnabled = preferences.getBoolean(WeatherAlertCategory.OFFICIAL.channelId, true),
                coldCelsius = preferences.getFloat("cold_celsius", 5f).toDouble(),
                dropCelsius = preferences.getFloat("drop_celsius", 8f).toDouble(),
                heatCelsius = preferences.getFloat("heat_celsius", 30f).toDouble(),
                windKmh = preferences.getFloat("wind_kmh", 50f).toDouble(),
                uvIndex = preferences.getFloat("uv_index", 6f).toDouble(),
                lookAheadHours = preferences.getInt("look_ahead_hours", 6),
            ).normalized()
        }

        private const val PREFERENCES = "weather_alert_settings"
    }
}
