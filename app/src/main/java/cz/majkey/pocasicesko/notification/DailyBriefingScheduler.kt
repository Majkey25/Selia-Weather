package cz.majkey.pocasicesko.notification

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import cz.majkey.pocasicesko.MainActivity
import cz.majkey.pocasicesko.R
import cz.majkey.pocasicesko.data.DailyWeather
import cz.majkey.pocasicesko.data.WeatherRepository
import cz.majkey.pocasicesko.locale.AppLocale
import cz.majkey.pocasicesko.units.MeasurementUnits
import cz.majkey.pocasicesko.units.WeatherUnitFormatter
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

internal object DailyBriefingScheduler {
    fun ensureChannel(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, AppLocale.localized(context).getString(R.string.daily_briefing_channel),
                NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    fun isEnabled(context: Context): Boolean = WeatherAlertSettings.load(context).backgroundAlertsAllowed && preferences(context).getBoolean(
        KEY_ENABLED,
        DEFAULT_DAILY_BRIEFING_ENABLED,
    )

    fun setEnabled(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
        if (enabled) schedule(context) else cancel(context)
    }

    fun time(context: Context): LocalTime = LocalTime.ofSecondOfDay(
        preferences(context).getInt("time_minutes", BRIEFING_TIME.hour * 60).coerceIn(0, 1439) * 60L,
    )

    fun setTime(context: Context, time: LocalTime) {
        preferences(context).edit().putInt("time_minutes", time.hour * 60 + time.minute).apply()
        WeatherRefreshScheduler.clearBriefing(context)
        schedule(context)
    }

    fun exactAlarmsAllowed(context: Context): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        alarmManager(context).canScheduleExactAlarms()

    fun setAlarm(context: Context, trigger: Long, pending: PendingIntent, exact: Boolean = true) {
        val manager = alarmManager(context)
        if (exact && exactAlarmsAllowed(context)) {
            try {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pending)
                return
            } catch (_: SecurityException) {
                // The special access can be revoked after the preceding check.
            }
        }
        manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pending)
    }

    fun schedule(context: Context, now: ZonedDateTime = ZonedDateTime.now()) {
        if (!isEnabled(context)) {
            cancel(context)
            return
        }
        val manager = alarmManager(context)
        val trigger = nextDailyBriefingTime(now, time(context)).toEpochMilli()
        setAlarm(context, trigger, pendingIntent(context, ACTION_SHOW, trigger))
        val prepareAt = trigger - 60 * 60 * 1_000L
        if (prepareAt > now.toInstant().toEpochMilli()) {
            setAlarm(context, prepareAt, pendingIntent(context, ACTION_REFRESH, trigger), exact = false)
        } else {
            manager.cancel(pendingIntent(context, ACTION_REFRESH))
            WeatherRefreshScheduler.request(context)
        }
    }

    private fun cancel(context: Context) {
        alarmManager(context).cancel(pendingIntent(context, ACTION_SHOW))
        alarmManager(context).cancel(pendingIntent(context, ACTION_REFRESH))
        WeatherRefreshScheduler.clearBriefing(context)
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    private fun pendingIntent(context: Context, action: String, trigger: Long = 0): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE,
        Intent(context, DailyBriefingReceiver::class.java).setAction(action).putExtra(EXTRA_SCHEDULED_AT, trigger),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun preferences(context: Context) =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    private fun alarmManager(context: Context): AlarmManager =
        context.getSystemService(AlarmManager::class.java)

    internal const val ACTION_SHOW = "com.majkeylab.weatheraladin.action.DAILY_BRIEFING"
    internal const val ACTION_REFRESH = "com.majkeylab.weatheraladin.action.PREPARE_BRIEFING"
    internal const val EXTRA_SCHEDULED_AT = "scheduled_at"
    internal const val NOTIFICATION_ID = 7001
    private const val REQUEST_CODE = 7001
    private const val PREFERENCES = "daily_briefing"
    private const val KEY_ENABLED = "enabled"
}

class DailyBriefingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Rain and warning checks do not depend on the optional morning briefing.
        WeatherAlertScheduler.sync(context)
        if (!DailyBriefingScheduler.isEnabled(context)) return
        if (intent.action == DailyBriefingScheduler.ACTION_SHOW) {
            val scheduledAt = intent.getLongExtra(DailyBriefingScheduler.EXTRA_SCHEDULED_AT, 0)
            if (isBriefingDeliveryDue(scheduledAt, System.currentTimeMillis(), DailyBriefingScheduler.time(context),
                ZoneId.systemDefault()) && !showBriefing(context, scheduledAt)) {
                WeatherRefreshScheduler.request(context, briefing = true, scheduledAt = scheduledAt)
            }
        } else if (intent.action == DailyBriefingScheduler.ACTION_REFRESH) {
            WeatherRefreshScheduler.request(context)
        }
        DailyBriefingScheduler.schedule(context)
    }

    internal fun showBriefing(context: Context, scheduledAt: Long): Boolean = synchronized(DailyBriefingScheduler) {
        if (!isBriefingDeliveryDue(scheduledAt, System.currentTimeMillis(), DailyBriefingScheduler.time(context),
            ZoneId.systemDefault())) return false
        if (!DailyBriefingScheduler.isEnabled(context) ||
            !NotificationManagerCompat.from(context).areNotificationsEnabled()
        ) return false
        val deliveries = context.getSharedPreferences("daily_briefing", Context.MODE_PRIVATE)
        val deliveryDay = Instant.ofEpochMilli(scheduledAt).atZone(ZoneId.systemDefault()).toLocalDate().toString()
        if (deliveries.getString("delivered_day", null) == deliveryDay) return true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        val repository = WeatherRepository(context)
        val location = repository.lastLocation()
        val snapshot = repository.cachedForecast(location) ?: return false
        val age = Instant.now().toEpochMilli() - snapshot.updatedAtEpochMillis
        if (age !in 0..MAX_FORECAST_AGE_MILLIS) return false
        val today = LocalDate.now(ZoneId.of(snapshot.timezone)).toString()
        val day = snapshot.daily.firstOrNull { it.date == today } ?: return false
        val localized = AppLocale.localized(context)
        DailyBriefingScheduler.ensureChannel(localized)
        if (!WeatherAlerts.canPost(context, CHANNEL_ID)) return false
        try {
            NotificationManagerCompat.from(context).notify(DailyBriefingScheduler.NOTIFICATION_ID,
                notification(localized, location.name, day)
                    .setTimeoutAfter((scheduledAt + BRIEFING_DELIVERY_WINDOW_MILLIS - System.currentTimeMillis()).coerceAtLeast(1)).build())
        } catch (_: SecurityException) { return false }
        deliveries.edit().putString("delivered_day", deliveryDay).apply()
        WeatherRefreshScheduler.clearBriefing(context)
        return true
    }

    private fun notification(
        context: Context,
        locationName: String,
        day: DailyWeather,
    ): NotificationCompat.Builder {
        val formatter = WeatherUnitFormatter(MeasurementUnits.current(context), AppLocale.locale(context))
        val minimum = day.apparentTemperatureMin ?: day.temperatureMin
        val maximum = day.apparentTemperatureMax ?: day.temperatureMax
        val content = dailyBriefingText(context, day)
        val openApp = PendingIntent.getActivity(
            context,
            REQUEST_CODE_OPEN,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_weather_cloud)
            .setContentTitle(
                context.getString(
                    R.string.daily_briefing_notification_title,
                    locationName,
                    formatter.temperature(minimum),
                    formatter.temperature(maximum),
                ),
            )
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
    }

}

internal fun dailyBriefingText(context: Context, day: DailyWeather): String {
    val advice = dailyBriefingAdvice(day)
    return listOfNotNull(
        context.getString(advice.outfit.resource(advice.umbrella)),
        context.getString(R.string.daily_briefing_no_umbrella)
            .takeIf { !advice.umbrella && day.precipitationProbability != null },
        context.getString(R.string.daily_briefing_sun_protection).takeIf { advice.sunProtection },
    ).joinToString(" ")
}

private fun OutfitLevel.resource(umbrella: Boolean): Int = when (this) {
    OutfitLevel.WINTER -> if (umbrella) R.string.daily_briefing_outfit_winter_rain else R.string.daily_briefing_outfit_winter
    OutfitLevel.WARM_COAT -> if (umbrella) R.string.daily_briefing_outfit_warm_coat_rain else R.string.daily_briefing_outfit_warm_coat
    OutfitLevel.JACKET -> if (umbrella) R.string.daily_briefing_outfit_jacket_rain else R.string.daily_briefing_outfit_jacket
    OutfitLevel.LIGHT_LAYERS -> if (umbrella) R.string.daily_briefing_outfit_light_layers_rain else R.string.daily_briefing_outfit_light_layers
    OutfitLevel.HOT -> if (umbrella) R.string.daily_briefing_outfit_hot_rain else R.string.daily_briefing_outfit_hot
}

private const val CHANNEL_ID = "daily_weather_briefing"
private const val REQUEST_CODE_OPEN = 7002
private const val MAX_FORECAST_AGE_MILLIS = 36 * 60 * 60 * 1_000L
