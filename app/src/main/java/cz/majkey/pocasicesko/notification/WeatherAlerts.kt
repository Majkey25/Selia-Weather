package cz.majkey.pocasicesko.notification

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import cz.majkey.pocasicesko.MainActivity
import cz.majkey.pocasicesko.R
import cz.majkey.pocasicesko.data.CzechLocation
import cz.majkey.pocasicesko.data.HourlyWeather
import cz.majkey.pocasicesko.data.LocationMode
import cz.majkey.pocasicesko.data.WeatherRepository
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
    val lastValidAtEpochMillis: Long = validAtEpochMillis,
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
        val time = forecastHourTime(hour, zone) ?: return@mapNotNull null
        (time to hour).takeIf { time > nowEpochMillis &&
            time <= nowEpochMillis + maxOf(normalized.lookAheadHours, normalized.rainLeadHours) * HOUR_MILLIS + HOUR_MILLIS }
    }.sortedBy { it.first }.distinctBy { it.first }
    val decisions = linkedMapOf<WeatherAlertCategory, ForecastAlert>()
    fun add(category: WeatherAlertCategory, time: Long, value: Double? = null, withinLeadTime: Boolean = true) {
        if (!normalized.isEnabled(category)) return
        val first = decisions[category]
        if (first == null) {
            if (withinLeadTime) decisions[category] = ForecastAlert(category, time, value)
        } else if (time > first.lastValidAtEpochMillis && time - first.lastValidAtEpochMillis <= HOUR_MILLIS) {
            decisions[category] = first.copy(lastValidAtEpochMillis = time)
        }
    }
    future.forEachIndexed { index, (time, hour) ->
        // Advise only for future instants or wholly future accumulation intervals.
        rainAlertTimes(hour, normalized, time)
            .filter { it >= nowEpochMillis }
            .forEach { add(WeatherAlertCategory.RAIN, it,
                withinLeadTime = it <= nowEpochMillis + normalized.rainLeadHours * HOUR_MILLIS) }
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
    return decisions.values.toList()
}

private fun forecastHourTime(hour: HourlyWeather, zone: ZoneId): Long? {
    val local = runCatching { LocalDateTime.parse(hour.time) }.getOrNull() ?: return null
    if (local.minute != 0 || local.second != 0 || local.nano != 0) return null
    val offset = zone.rules.getValidOffsets(local).singleOrNull() ?: return null
    return local.toInstant(offset).toEpochMilli()
}

private fun rainAlertTimes(hour: HourlyWeather, settings: WeatherAlertSettings, time: Long): List<Long> = buildList {
    // Amounts and probabilities describe the preceding hour; weather codes describe the instant.
    if (hasPrecipitationEvidence(0, hour.precipitation, hour.rain, hour.showers, hour.snowfall) ||
        hour.precipitationProbability?.let { it in settings.rainProbabilityPercent..100 } == true ||
        hour.precipitationSpread?.let { it.wetModelCount * 2 >= it.modelCount && it.maximumMm >= 0.1 } == true
    ) add(time - HOUR_MILLIS)
    if (hasPrecipitationEvidence(hour.weatherCode)) add(time)
}

internal fun nextRainAlertTime(settings: WeatherAlertSettings, snapshot: WeatherSnapshot, now: Long): Long? {
    val current = settings.normalized()
    if (!current.rainEnabled || snapshot.updatedAtEpochMillis !in (now - MAX_FORECAST_AGE_MILLIS)..now) return null
    val zone = forecastAlertZone(snapshot) ?: return null
    return snapshot.hourly.flatMap { hour ->
        val time = forecastHourTime(hour, zone) ?: return@flatMap emptyList()
        rainAlertTimes(hour, current, time).map { it - current.rainLeadHours * HOUR_MILLIS }
            .filter { it > now && it <= snapshot.updatedAtEpochMillis + MAX_FORECAST_AGE_MILLIS }
    }.minOrNull()
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
        WeatherAlertCategory.entries.filter { !settings.backgroundAlertsAllowed || !settings.isEnabled(it) || !canPost(context, it.channelId) }
            .forEach { manager.cancel(it.notificationId) }
    }

    @Synchronized
    fun evaluateAndNotify(
        context: Context,
        location: CzechLocation,
        snapshot: WeatherSnapshot,
        requireBackgroundPermission: Boolean = false,
    ) {
        val repository = WeatherRepository(context)
        repository.publishForCurrentLocation(location, requireBackgroundPermission) {
            notifyCurrentLocation(context, location, snapshot, repository.locationMode())
        }
    }

    private fun notifyCurrentLocation(context: Context, location: CzechLocation, snapshot: WeatherSnapshot, mode: LocationMode) {
        ensureChannels(context)
        val now = System.currentTimeMillis()
        val settings = WeatherAlertSettings.load(context)
        cancelDisabled(context, settings)
        if (!settings.backgroundAlertsAllowed) return
        val alerts = forecastAlerts(settings, snapshot, now)
        WeatherAlertScheduler.scheduleRainCheck(context, snapshot, now)
        val manager = NotificationManagerCompat.from(context)
        WeatherAlertCategory.entries.filter { it != WeatherAlertCategory.OFFICIAL && alerts.none { alert -> alert.category == it } }
            .forEach { manager.cancel(it.notificationId) }
        val preferences = context.getSharedPreferences("weather_alert_deliveries", Context.MODE_PRIVATE)
        val localized = AppLocale.localized(context)
        val zone = runCatching { ZoneId.of(snapshot.timezone) }.getOrNull() ?: forecastAlertZone(snapshot) ?: return
        alerts.forEach { alert ->
            val category = alert.category
            val current = WeatherAlertSettings.load(context)
            if (!current.backgroundAlertsAllowed || !current.isEnabled(category) || !canPost(context, category.channelId)) return@forEach
            val place = "${location.latitude},${location.longitude}"
            val key = "$place|${alert.validAtEpochMillis}"
            val previous = preferences.getString(category.channelId, null)
            val postedAt = preferences.getLong("${category.channelId}_posted_at", 0)
            val lastValidAtKey = "${category.channelId}_last_valid_at"
            val firstValidAt = previous?.substringAfterLast('|')?.toLongOrNull()
            if (firstValidAt != null && postedAt in 1..now) {
                val lastValidAt = preferences.getLong(lastValidAtKey, firstValidAt)
                if (alert.validAtEpochMillis <= lastValidAt + HOUR_MILLIS &&
                    firstValidAt <= alert.lastValidAtEpochMillis + HOUR_MILLIS &&
                    sameForecastAlertArea(previous.substringBeforeLast('|'), place, location, mode)) {
                    if (alert.lastValidAtEpochMillis > lastValidAt) {
                        preferences.edit().putLong(lastValidAtKey, alert.lastValidAtEpochMillis).apply()
                    }
                    return@forEach
                }
            }
            if (!shouldPostWeatherAlert(previous, postedAt, key, now)) return@forEach
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
                    .putLong("${category.channelId}_posted_at", now)
                    .putLong(lastValidAtKey, alert.lastValidAtEpochMillis).apply()
            } catch (_: SecurityException) {
                // Permission can be revoked between the check and the system call.
                manager.cancel(category.notificationId)
            }
        }
    }
}

private fun sameForecastAlertArea(previous: String, place: String, location: CzechLocation, mode: LocationMode): Boolean {
    if (previous == place) return true
    if (mode != LocationMode.FOLLOW_DEVICE) return false
    val coordinates = previous.split(',', limit = 3)
    if (coordinates.size != 2) return false
    val latitude = coordinates[0].toDoubleOrNull()?.takeIf { it in -90.0..90.0 } ?: return false
    val longitude = coordinates[1].toDoubleOrNull()?.takeIf { it in -180.0..180.0 } ?: return false
    val distance = FloatArray(1)
    Location.distanceBetween(latitude, longitude, location.latitude, location.longitude, distance)
    return distance[0] <= FOLLOW_ALERT_AREA_METERS
}

private fun forecastAlertZone(snapshot: WeatherSnapshot): ZoneId? =
    snapshot.utcOffsetSeconds?.takeIf { it in -64_800..64_800 }?.let(ZoneOffset::ofTotalSeconds)
        ?: runCatching { ZoneId.of(snapshot.timezone) }.getOrNull()

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
private const val FOLLOW_ALERT_AREA_METERS = 1_000f
