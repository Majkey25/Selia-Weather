package cz.majkey.pocasicesko.notification

import android.app.NotificationManager
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import java.time.Instant
import java.time.ZoneId
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in cold-process probe using real forecast data, never the production package. */
class BackgroundDeliveryProbe {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun prepare() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("backgroundProbe") == "true")
        check(context.packageName.endsWith(".debug"))
        check(WeatherAlertSettings.load(context).backgroundAlertsAllowed) { "Enable background alerts in the app before the probe" }
        val scheduler = context.getSystemService(JobScheduler::class.java)
        check(scheduler.getPendingJob(7003) == null) { "Existing refresh work must finish first" }
        val backup = context.getSharedPreferences("notification_probe", Context.MODE_PRIVATE)
        check(!backup.contains("enabled")) { "Restore the preceding probe first" }
        check(backup.edit().putBoolean("enabled", DailyBriefingScheduler.isEnabled(context))
            .putInt("time_minutes", context.getSharedPreferences("daily_briefing", Context.MODE_PRIVATE).getInt("time_minutes", -1))
            .putString("pending_day", context.getSharedPreferences("weather_refresh", Context.MODE_PRIVATE)
                .getString("briefing_day", null))
            .putLong("scheduled_at", WeatherRefreshScheduler.briefingScheduledAt(context))
            .putString("delivered_day", context.getSharedPreferences("daily_briefing", Context.MODE_PRIVATE)
                .getString("delivered_day", null)).commit())
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            context.getSharedPreferences("daily_briefing", Context.MODE_PRIVATE).edit().remove("delivered_day").commit()
            DailyBriefingScheduler.setEnabled(context, true)
            val scheduledAt = System.currentTimeMillis() / 60_000 * 60_000
            DailyBriefingScheduler.setTime(context, Instant.ofEpochMilli(scheduledAt).atZone(ZoneId.systemDefault()).toLocalTime())
            WeatherRefreshScheduler.request(context, briefing = true, scheduledAt = scheduledAt)
            check(scheduler.schedule(JobInfo.Builder(7003, ComponentName(context, WeatherRefreshJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setMinimumLatency(120_000)
                .setPersisted(true).build()) == JobScheduler.RESULT_SUCCESS)
            context.getSystemService(NotificationManager::class.java).cancel(DailyBriefingScheduler.NOTIFICATION_ID)
        }
    }

    @Test
    fun restore() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("backgroundProbe") == "true")
        check(context.packageName.endsWith(".debug"))
        val backup = context.getSharedPreferences("notification_probe", Context.MODE_PRIVATE)
        check(backup.contains("enabled"))
        context.getSystemService(JobScheduler::class.java).cancel(7003)
        if (backup.contains("time_minutes")) {
            val briefing = context.getSharedPreferences("daily_briefing", Context.MODE_PRIVATE).edit()
            val timeMinutes = backup.getInt("time_minutes", -1)
            if (timeMinutes < 0) briefing.remove("time_minutes") else briefing.putInt("time_minutes", timeMinutes)
            check(briefing.commit())
        }
        DailyBriefingScheduler.setEnabled(context, backup.getBoolean("enabled", false))
        check(context.getSharedPreferences("weather_refresh", Context.MODE_PRIVATE).edit()
            .putString("briefing_day", backup.getString("pending_day", null))
            .putLong("briefing_scheduled_at", backup.getLong("scheduled_at", 0)).commit())
        check(context.getSharedPreferences("daily_briefing", Context.MODE_PRIVATE).edit()
            .putString("delivered_day", backup.getString("delivered_day", null)).commit())
        context.getSystemService(NotificationManager::class.java).cancel(DailyBriefingScheduler.NOTIFICATION_ID)
        check(backup.edit().clear().commit())
    }
}
