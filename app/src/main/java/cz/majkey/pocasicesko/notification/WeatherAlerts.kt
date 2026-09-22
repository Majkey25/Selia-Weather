package cz.majkey.pocasicesko.notification

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import cz.majkey.pocasicesko.MainActivity
import cz.majkey.pocasicesko.R
import cz.majkey.pocasicesko.data.CzechLocation
import cz.majkey.pocasicesko.data.WeatherSnapshot
import cz.majkey.pocasicesko.data.hasPrecipitationEvidence
import cz.majkey.pocasicesko.locale.AppLocale
import cz.majkey.pocasicesko.units.MeasurementSystem
import cz.majkey.pocasicesko.units.MeasurementUnits
import cz.majkey.pocasicesko.units.WeatherUnitFormatter
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.roundToInt

internal data class ForecastAlert(
    val category: WeatherAlertCategory,
    val validAtEpochMillis: Long,
    val value: Double? = null,
)

internal fun forecastAlerts(
    settings: WeatherAlertSettings,
    snapshot: WeatherSnapshot,
    nowEpochMillis: Long,
): List<ForecastAlert> {
    if (snapshot.updatedAtEpochMillis !in (nowEpochMillis - MAX_FORECAST_AGE_MILLIS)..nowEpochMillis) return emptyList()
    val zone = forecastAlertZone(snapshot) ?: return emptyList()
    val normalized = settings.normalized()
    val horizon = nowEpochMillis + normalized.lookAheadHours * HOUR_MILLIS
    val future = snapshot.hourly.mapNotNull { hour ->
        val local = runCatching { LocalDateTime.parse(hour.time) }.getOrNull() ?: return@mapNotNull null
        if (local.minute != 0 || local.second != 0 || local.nano != 0) return@mapNotNull null
        val offset = zone.rules.getValidOffsets(local).singleOrNull() ?: return@mapNotNull null
        val time = local.toInstant(offset).toEpochMilli()
        (time to hour).takeIf { time > nowEpochMillis && time <= horizon + HOUR_MILLIS }
    }.sortedBy { it.first }.distinctBy { it.first }
    val decisions = mutableListOf<ForecastAlert>()
    fun add(category: WeatherAlertCategory, time: Long, value: Double? = null) {
        if (normalized.isEnabled(category) && decisions.none { it.category == category }) {
            decisions += ForecastAlert(category, time, value)
        }
    }
    future.forEachIndexed { index, (time, hour) ->
        // Source precipitation totals end at their timestamp; advise only for wholly future intervals.
        if (time - HOUR_MILLIS >= nowEpochMillis && (
            hasPrecipitationEvidence(hour.weatherCode, hour.precipitation, hour.rain, hour.showers, hour.snowfall) ||
                hour.precipitationProbability in 40..100 ||
                (hour.precipitationSpread?.wetModelCount?.let { it > 0 } == true)
            )
        ) add(WeatherAlertCategory.RAIN, time - HOUR_MILLIS)
        if (time > horizon) return@forEachIndexed
        hour.temperature.takeIf { it.isFinite() && it in -100.0..70.0 }?.let { temperature ->
            if (temperature <= normalized.coldCelsius) add(WeatherAlertCategory.COLD, time, temperature)
            if (temperature >= normalized.heatCelsius) add(WeatherAlertCategory.HEAT, time, temperature)
            val before = future.subList(0, index).filter { time - it.first <= 6 * HOUR_MILLIS }
                .map { it.second.temperature }.filter { it.isFinite() && it in -100.0..70.0 }.maxOrNull()
            if (before != null && before - temperature >= normalized.dropCelsius) {
                add(WeatherAlertCategory.DROP, time, before - temperature)
            }
        }
        listOfNotNull(hour.windGusts, hour.windSpeed).filter { it.isFinite() && it in 0.0..500.0 }
            .maxOrNull()?.takeIf { it >= normalized.windKmh }?.let { add(WeatherAlertCategory.WIND, time, it) }
        hour.uvIndex?.takeIf { it.isFinite() && it in normalized.uvIndex..30.0 }
            ?.let { add(WeatherAlertCategory.UV, time, it) }
    }
    return decisions
}

internal fun shouldPostWeatherAlert(previousKey: String?, postedAt: Long, nextKey: String, now: Long): Boolean =
    previousKey != nextKey && (previousKey == null || postedAt > now || now - postedAt >= ALERT_COOLDOWN_MILLIS)

object WeatherAlerts {
    const val CHANNEL_OFFICIAL = "official_weather_warnings"
    const val NOTIFICATION_ID_OFFICIAL = 7107

    fun ensureChannels(context: Context) {
        val localized = AppLocale.localized(context)
        context.getSystemService(NotificationManager::class.java).createNotificationChannels(
            WeatherAlertCategory.entries.map { category ->
                NotificationChannel(category.channelId, localized.getString(category.labelResource),
                    if (category == WeatherAlertCategory.OFFICIAL) NotificationManager.IMPORTANCE_HIGH
                    else NotificationManager.IMPORTANCE_DEFAULT)
            },
        )
    }

    fun canPost(context: Context, channelId: String): Boolean =
        (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            context.getSystemService(NotificationManager::class.java).getNotificationChannel(channelId)
                ?.importance?.let { it != NotificationManager.IMPORTANCE_NONE } == true

    fun cancelDisabled(context: Context, settings: WeatherAlertSettings = WeatherAlertSettings.load(context)) {
        val manager = NotificationManagerCompat.from(context)
        WeatherAlertCategory.entries.filter { !settings.isEnabled(it) || !canPost(context, it.channelId) }
            .forEach { manager.cancel(it.notificationId) }
    }

    @Synchronized
    fun evaluateAndNotify(context: Context, location: CzechLocation, snapshot: WeatherSnapshot) {
        ensureChannels(context)
        val now = System.currentTimeMillis()
        val settings = WeatherAlertSettings.load(context)
        cancelDisabled(context, settings)
        val alerts = forecastAlerts(settings, snapshot, now)
        val manager = NotificationManagerCompat.from(context)
        WeatherAlertCategory.entries.filter { it != WeatherAlertCategory.OFFICIAL && alerts.none { alert -> alert.category == it } }
            .forEach { manager.cancel(it.notificationId) }
        val preferences = context.getSharedPreferences("weather_alert_deliveries", Context.MODE_PRIVATE)
        val localized = AppLocale.localized(context)
        val zone = forecastAlertZone(snapshot) ?: return
        alerts.forEach { alert ->
            val category = alert.category
            if (!WeatherAlertSettings.load(context).isEnabled(category) || !canPost(context, category.channelId)) return@forEach
            val key = "${location.latitude},${location.longitude}|${alert.validAtEpochMillis}"
            if (!shouldPostWeatherAlert(preferences.getString(category.channelId, null),
                preferences.getLong("${category.channelId}_posted_at", 0), key, now)) return@forEach
            val text = alertText(localized, alert, zone)
            val notification = NotificationCompat.Builder(localized, category.channelId)
                .setSmallIcon(R.drawable.ic_weather_cloud)
                .setContentTitle(localized.getString(R.string.notification_location_title,
                    localized.getString(category.labelResource), location.name))
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(PendingIntent.getActivity(context, category.notificationId,
                    Intent(context, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setTimeoutAfter((alert.validAtEpochMillis + HOUR_MILLIS - now).coerceAtLeast(1))
                .build()
            try {
                manager.notify(category.notificationId, notification)
                preferences.edit().putString(category.channelId, key)
                    .putLong("${category.channelId}_posted_at", now).apply()
            } catch (_: SecurityException) {
                // Permission can be revoked between the check and the system call.
                manager.cancel(category.notificationId)
            }
        }
    }
}

private fun forecastAlertZone(snapshot: WeatherSnapshot): ZoneId? =
    runCatching { ZoneId.of(snapshot.timezone) }.getOrNull()
        ?: snapshot.utcOffsetSeconds?.takeIf { it in -64_800..64_800 }?.let(ZoneOffset::ofTotalSeconds)

private fun alertText(context: Context, alert: ForecastAlert, zone: ZoneId): String {
    val locale = AppLocale.locale(context)
    val system = MeasurementUnits.current(context)
    val units = WeatherUnitFormatter(system, locale)
    val time = Instant.ofEpochMilli(alert.validAtEpochMillis).atZone(zone)
        .format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withLocale(locale))
    return when (alert.category) {
        WeatherAlertCategory.RAIN -> context.getString(R.string.notification_rain_advice, time)
        WeatherAlertCategory.COLD -> context.getString(R.string.notification_cold_advice, time, units.temperature(requireNotNull(alert.value)))
        WeatherAlertCategory.DROP -> context.getString(R.string.notification_drop_advice, time,
            temperatureDropText(requireNotNull(alert.value), system))
        WeatherAlertCategory.HEAT -> context.getString(R.string.notification_heat_advice, time, units.temperature(requireNotNull(alert.value)))
        WeatherAlertCategory.WIND -> context.getString(R.string.notification_wind_advice, time, units.windSpeed(requireNotNull(alert.value)))
        WeatherAlertCategory.UV -> context.getString(R.string.notification_uv_advice, time, requireNotNull(alert.value).roundToInt())
        WeatherAlertCategory.OFFICIAL -> error("Official warning text comes from its issuing authority.")
    }
}

fun temperatureDropText(celsius: Double, system: MeasurementSystem): String =
    if (system == MeasurementSystem.IMPERIAL) "${(celsius * 9 / 5).roundToInt()}°F" else "${celsius.roundToInt()}°C"

private const val HOUR_MILLIS = 60 * 60 * 1_000L
private const val MAX_FORECAST_AGE_MILLIS = 6 * HOUR_MILLIS
private const val ALERT_COOLDOWN_MILLIS = 6 * HOUR_MILLIS
