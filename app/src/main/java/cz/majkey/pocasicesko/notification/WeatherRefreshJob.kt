package cz.majkey.pocasicesko.notification

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.util.Log
import cz.majkey.pocasicesko.data.WeatherRepository
import cz.majkey.pocasicesko.widget.WeatherWidgetProvider
import java.time.LocalDate
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Future
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

internal object WeatherRefreshScheduler {
    private const val JOB_ID = 7003
    private const val PREFERENCES = "weather_refresh"
    private const val KEY_BRIEFING_DAY = "briefing_day"

    @Synchronized
    fun request(context: Context, briefing: Boolean = false) {
        if (briefing) {
            preferences(context).edit().putString(KEY_BRIEFING_DAY, LocalDate.now().toString()).apply()
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

    fun pendingBriefing(context: Context): String? = preferences(context)
        .getString(KEY_BRIEFING_DAY, null)
        ?.takeIf { isPendingBriefingForToday(it, LocalDate.now()) && DailyBriefingScheduler.isEnabled(context) }

    @Synchronized
    fun deliveredBriefing(context: Context, day: String) {
        val preferences = preferences(context)
        if (preferences.getString(KEY_BRIEFING_DAY, null) == day) {
            preferences.edit().remove(KEY_BRIEFING_DAY).apply()
        }
    }

    private fun preferences(context: Context) = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
}

class WeatherRefreshJob : JobService() {
    private val worker = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(1), ThreadPoolExecutor.DiscardOldestPolicy(),
    )
    private var task: Future<*>? = null
    @Volatile private var generation = 0

    override fun onStartJob(params: JobParameters): Boolean {
        val briefingDay = WeatherRefreshScheduler.pendingBriefing(this)
        val hasWidgets = AppWidgetManager.getInstance(this)
            .getAppWidgetIds(ComponentName(this, WeatherWidgetProvider::class.java)).isNotEmpty()
        if (!hasWidgets && briefingDay == null) return false
        val run = ++generation
        task = worker.submit {
            var retry = false
            try {
                val repository = WeatherRepository(applicationContext)
                val location = repository.lastLocation()
                repository.fetchForecastBlocking(location)
                if (run == generation && !Thread.currentThread().isInterrupted) {
                    if (location != repository.lastLocation()) {
                        retry = true
                    } else WeatherRefreshScheduler.pendingBriefing(this)?.let { day ->
                        if (DailyBriefingReceiver().showBriefing(this)) {
                            WeatherRefreshScheduler.deliveredBriefing(this, day)
                        }
                    }
                }
            } catch (error: Exception) {
                if (run == generation && !Thread.currentThread().isInterrupted) {
                    Log.w("WeatherRefresh", "Weather refresh will retry", error)
                    retry = true
                }
            } finally {
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
