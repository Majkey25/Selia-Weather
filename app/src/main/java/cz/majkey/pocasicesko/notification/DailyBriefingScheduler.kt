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
import java.time.ZoneId
import java.time.ZonedDateTime

internal object DailyBriefingScheduler {
    fun isEnabled(context: Context): Boolean = preferences(context).getBoolean(
        KEY_ENABLED,
        DEFAULT_DAILY_BRIEFING_ENABLED,
    )

    fun setEnabled(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
        if (enabled) schedule(context) else cancel(context)
    }

    fun schedule(context: Context, now: ZonedDateTime = ZonedDateTime.now()) {
        if (!isEnabled(context)) return
        val manager = alarmManager(context)
        val trigger = nextDailyBriefingTime(now).toEpochMilli()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager.canScheduleExactAlarms()) {
            manager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                trigger,
                pendingIntent(context),
            )
        } else {
            manager.setWindow(
                AlarmManager.RTC_WAKEUP,
                trigger,
                BRIEFING_WINDOW_MILLIS,
                pendingIntent(context),
            )
        }
    }

    private fun cancel(context: Context) {
        alarmManager(context).cancel(pendingIntent(context))
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE,
        Intent(context, DailyBriefingReceiver::class.java).setAction(ACTION_SHOW),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun preferences(context: Context) =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    private fun alarmManager(context: Context): AlarmManager =
        context.getSystemService(AlarmManager::class.java)

    internal const val ACTION_SHOW = "com.majkeylab.weatheraladin.action.DAILY_BRIEFING"
    internal const val NOTIFICATION_ID = 7001
    private const val REQUEST_CODE = 7001
    private const val PREFERENCES = "daily_briefing"
    private const val KEY_ENABLED = "enabled"
}

class DailyBriefingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!DailyBriefingScheduler.isEnabled(context)) return
        if (intent.action == DailyBriefingScheduler.ACTION_SHOW) showBriefing(context)
        DailyBriefingScheduler.schedule(context)
    }

    private fun showBriefing(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val repository = WeatherRepository(context)
        val location = repository.lastLocation()
        val snapshot = repository.cachedForecast(location) ?: return
        val age = Instant.now().toEpochMilli() - snapshot.updatedAtEpochMillis
        if (age !in 0..MAX_FORECAST_AGE_MILLIS) return
        val today = LocalDate.now(ZoneId.of(snapshot.timezone)).toString()
        val day = snapshot.daily.firstOrNull { it.date == today } ?: return
        val localized = AppLocale.localized(context)
        createChannel(localized)
        NotificationManagerCompat.from(context).notify(
            DailyBriefingScheduler.NOTIFICATION_ID,
            notification(localized, location.name, day).build(),
        )
    }

    private fun notification(
        context: Context,
        locationName: String,
        day: DailyWeather,
    ): NotificationCompat.Builder {
        val formatter = WeatherUnitFormatter(MeasurementUnits.current(context), AppLocale.locale(context))
        val advice = dailyBriefingAdvice(day)
        val minimum = day.apparentTemperatureMin ?: day.temperatureMin
        val maximum = day.apparentTemperatureMax ?: day.temperatureMax
        val content = listOfNotNull(
            context.getString(advice.outfit.resource()),
            context.getString(
                if (advice.umbrella) R.string.daily_briefing_umbrella else R.string.daily_briefing_no_umbrella,
            ),
            context.getString(R.string.daily_briefing_sun_protection).takeIf { advice.sunProtection },
        ).joinToString(" ")
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
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
    }

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.daily_briefing_channel),
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
    }
}

private fun OutfitLevel.resource(): Int = when (this) {
    OutfitLevel.WINTER -> R.string.daily_briefing_outfit_winter
    OutfitLevel.WARM_COAT -> R.string.daily_briefing_outfit_warm_coat
    OutfitLevel.JACKET -> R.string.daily_briefing_outfit_jacket
    OutfitLevel.LIGHT_LAYERS -> R.string.daily_briefing_outfit_light_layers
    OutfitLevel.HOT -> R.string.daily_briefing_outfit_hot
}

private const val CHANNEL_ID = "daily_weather_briefing"
private const val REQUEST_CODE_OPEN = 7002
private const val MAX_FORECAST_AGE_MILLIS = 36 * 60 * 60 * 1_000L
private const val BRIEFING_WINDOW_MILLIS = 30 * 60 * 1_000L
