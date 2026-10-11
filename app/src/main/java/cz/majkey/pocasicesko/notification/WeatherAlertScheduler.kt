package cz.majkey.pocasicesko.notification

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import cz.majkey.pocasicesko.data.WeatherRepository
import cz.majkey.pocasicesko.data.WeatherSnapshot
import java.util.concurrent.TimeUnit

internal object WeatherAlertScheduler {
    const val JOB_ID = 7004

    fun notificationsAllowed(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)

    fun enabled(context: Context): Boolean {
        val settings = WeatherAlertSettings.load(context)
        return settings.backgroundAlertsAllowed &&
            WeatherAlertCategory.entries.any { settings.isEnabled(it) && WeatherAlerts.canPost(context, it.channelId) }
    }

    fun sync(context: Context) {
        WeatherAlerts.ensureChannels(context)
        DailyBriefingScheduler.ensureChannel(context)
        WeatherAlerts.cancelDisabled(context)
        val scheduler = context.getSystemService(JobScheduler::class.java)
        if (!enabled(context)) {
            scheduler.cancel(JOB_ID)
        } else if (scheduler.getPendingJob(JOB_ID) == null) {
            val result = scheduler.schedule(JobInfo.Builder(JOB_ID, ComponentName(context, WeatherAlertJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(TimeUnit.MINUTES.toMillis(15))
                .setPersisted(true)
                .build())
            if (result == JobScheduler.RESULT_FAILURE) Log.w("WeatherAlerts", "Unable to schedule alert checks")
        }
        val settings = WeatherAlertSettings.load(context)
        val snapshot = if (settings.backgroundAlertsAllowed && settings.rainEnabled &&
            WeatherAlerts.canPost(context, WeatherAlertCategory.RAIN.channelId)
        ) {
            val repository = WeatherRepository(context)
            repository.cachedForecast(repository.lastLocation())
        } else null
        scheduleRainCheck(context, snapshot)
    }

    fun scheduleRainCheck(context: Context, snapshot: WeatherSnapshot?, now: Long = System.currentTimeMillis()) {
        val manager = context.getSystemService(AlarmManager::class.java)
        val pending = PendingIntent.getBroadcast(context, 7100, Intent(context, WeatherAlertReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val trigger = snapshot?.takeIf { WeatherAlertSettings.load(context).backgroundAlertsAllowed &&
            WeatherAlerts.canPost(context, WeatherAlertCategory.RAIN.channelId) }
            ?.let { nextRainAlertTime(WeatherAlertSettings.load(context), it, now) }
        if (trigger == null) manager.cancel(pending)
        else DailyBriefingScheduler.setAlarm(context, trigger, pending)
    }
}

class WeatherAlertReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!WeatherAlertSettings.load(context).backgroundAlertsAllowed) return
        val repository = WeatherRepository(context)
        val location = repository.lastLocation()
        repository.cachedForecast(location)?.let { WeatherAlerts.evaluateAndNotify(context, location, it) }
        WeatherRefreshScheduler.request(context)
    }
}
