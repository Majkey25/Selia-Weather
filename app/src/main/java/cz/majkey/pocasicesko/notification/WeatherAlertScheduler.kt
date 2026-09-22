package cz.majkey.pocasicesko.notification

import android.Manifest
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.TimeUnit

internal object WeatherAlertScheduler {
    const val JOB_ID = 7004

    fun notificationsAllowed(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)

    fun enabled(context: Context): Boolean {
        val settings = WeatherAlertSettings.load(context)
        return WeatherAlertCategory.entries.any { settings.isEnabled(it) && WeatherAlerts.canPost(context, it.channelId) }
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
    }
}
