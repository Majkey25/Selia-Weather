package cz.majkey.pocasicesko.notification

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.util.Log
import cz.majkey.pocasicesko.data.CzechLocation
import cz.majkey.pocasicesko.data.WeatherRepository
import cz.majkey.pocasicesko.data.WeatherWarningsRepository
import cz.majkey.pocasicesko.locale.AppLocale
import kotlinx.coroutines.runBlocking
import cz.majkey.pocasicesko.widget.WeatherWidgetProvider
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Future
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

internal object WeatherRefreshScheduler {
    private const val JOB_ID = 7003
    private const val PREFERENCES = "weather_refresh"
    private const val KEY_BRIEFING_DAY = "briefing_day"

    @Synchronized
    fun request(context: Context, briefing: Boolean = false, scheduledAt: Long = System.currentTimeMillis()) {
        if (briefing) {
            preferences(context).edit().putString(KEY_BRIEFING_DAY, LocalDate.now().toString())
                .putLong("briefing_scheduled_at", scheduledAt).apply()
        }
        val scheduler = context.getSystemService(JobScheduler::class.java)
        if (scheduler.getPendingJob(JOB_ID) != null) return
        val result = scheduler.schedule(
            JobInfo.Builder(JOB_ID, ComponentName(context, WeatherRefreshJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setBackoffCriteria(TimeUnit.MINUTES.toMillis(15), JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                .setPersisted(true)
                .build(),
        )
        if (result == JobScheduler.RESULT_FAILURE) Log.w("WeatherRefresh", "Unable to schedule weather refresh")
    }

    @Synchronized
    fun pendingBriefing(context: Context): String? {
        val preferences = preferences(context)
        val day = preferences.getString(KEY_BRIEFING_DAY, null)
        if (isPendingBriefingForToday(day, LocalDate.now()) && DailyBriefingScheduler.isEnabled(context) &&
            isBriefingDeliveryDue(briefingScheduledAt(context), System.currentTimeMillis(), DailyBriefingScheduler.time(context),
                ZoneId.systemDefault())) return day
        clearBriefing(context)
        return null
    }

    fun briefingScheduledAt(context: Context): Long = preferences(context).getLong("briefing_scheduled_at", 0)

    @Synchronized
    fun clearBriefing(context: Context) {
        preferences(context).edit().remove(KEY_BRIEFING_DAY).remove("briefing_scheduled_at").apply()
    }

    @Synchronized
    fun deliveredBriefing(context: Context, day: String) {
        val preferences = preferences(context)
        if (preferences.getString(KEY_BRIEFING_DAY, null) == day) {
            clearBriefing(context)
        }
    }

    private fun preferences(context: Context) = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
}

open class WeatherRefreshJob : JobService() {
    private val worker = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(1), ThreadPoolExecutor.DiscardOldestPolicy(),
    )
    private var task: Future<*>? = null
    @Volatile private var generation = 0

    override fun onStartJob(params: JobParameters): Boolean {
        val briefingDay = WeatherRefreshScheduler.pendingBriefing(this)
        val hasWidgets = AppWidgetManager.getInstance(this)
            .getAppWidgetIds(ComponentName(this, WeatherWidgetProvider::class.java)).isNotEmpty()
        val checkAlerts = WeatherAlertScheduler.enabled(this)
        val settings = WeatherAlertSettings.load(this)
        val prepareBriefing = params.jobId != WeatherAlertScheduler.JOB_ID && DailyBriefingScheduler.isEnabled(this)
        val checkForecast = hasWidgets || briefingDay != null || prepareBriefing || (checkAlerts && WeatherAlertCategory.entries.any {
            it != WeatherAlertCategory.OFFICIAL && settings.isEnabled(it) && WeatherAlerts.canPost(this, it.channelId)
        })
        if (!hasWidgets && briefingDay == null && !checkAlerts && !prepareBriefing) return false
        val run = ++generation
        task = worker.submit {
            var retry = false
            val repository = WeatherRepository(applicationContext)
            var resolvedLocation: CzechLocation? = null
            try {
                if (!hasWidgets && !WeatherAlertSettings.load(this).backgroundAlertsAllowed) return@submit
                val location = runBlocking { repository.refreshFollowedLocation(requireBackgroundPermission = true) }
                resolvedLocation = location
                if (checkForecast) {
                    val cached = repository.cachedForecast(location)
                    val snapshot = if ((params.jobId == WeatherAlertScheduler.JOB_ID || prepareBriefing) && cached != null &&
                        System.currentTimeMillis() - cached.updatedAtEpochMillis in 0 until TimeUnit.HOURS.toMillis(1)) {
                        cached
                    } else repository.fetchForecastBlocking(location)
                    if (run == generation && !Thread.currentThread().isInterrupted) {
                        if (location != repository.lastLocation()) {
                            retry = true
                        } else {
                            if (checkAlerts) WeatherAlerts.evaluateAndNotify(this, location, snapshot, requireBackgroundPermission = true)
                            WeatherRefreshScheduler.pendingBriefing(this)?.let { day ->
                                if (DailyBriefingReceiver().showBriefing(this, WeatherRefreshScheduler.briefingScheduledAt(this))) {
                                    WeatherRefreshScheduler.deliveredBriefing(this, day)
                                }
                            }
                        }
                    }
                }
            } catch (error: Exception) {
                if (run == generation && !Thread.currentThread().isInterrupted) {
                    Log.w("WeatherRefresh", "Weather refresh will retry", error)
                    retry = true
                }
            } finally {
                // Warning checks must still run when the independent forecast provider is down.
                val location = resolvedLocation
                if (location != null && checkAlerts && WeatherAlertScheduler.enabled(this) && run == generation && !Thread.currentThread().isInterrupted &&
                    WeatherAlertSettings.load(this).officialWarningsEnabled) {
                    try {
                        val result = runBlocking { WeatherWarningsRepository(applicationContext).fetch(location, AppLocale.languageTag(this@WeatherRefreshJob)) }
                        if (run == generation && !Thread.currentThread().isInterrupted && location == repository.lastLocation()) {
                            OfficialWarningNotifications.publish(this, location, result, requireBackgroundPermission = true)
                        }
                    } catch (error: Exception) {
                        if (run == generation && !Thread.currentThread().isInterrupted) Log.w("WeatherWarnings", "Warning check failed", error)
                    }
                }
                if (run == generation && !Thread.currentThread().isInterrupted) jobFinished(params, retry)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        generation++
        task?.cancel(true)
        task = null
        return true
    }

    override fun onDestroy() {
        generation++
        worker.shutdownNow()
        super.onDestroy()
    }
}

internal fun isPendingBriefingForToday(day: String?, today: LocalDate): Boolean = day == today.toString()

// Android can run two job IDs in one service concurrently. A separate component isolates their lifecycle state.
class WeatherAlertJob : WeatherRefreshJob()
