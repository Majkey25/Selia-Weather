package cz.majkey.pocasicesko.notification

import android.content.BroadcastReceiver
import android.util.Log
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

private val notificationWorker = ThreadPoolExecutor(
    1, 1, 30L, TimeUnit.SECONDS, ArrayBlockingQueue(4),
    { work -> Thread(work, "weather-notification-receiver") }, ThreadPoolExecutor.AbortPolicy(),
).apply { allowCoreThreadTimeOut(true) }

internal fun BroadcastReceiver.runNotificationWork(onFailure: () -> Unit, work: () -> Unit) {
    val pendingResult = goAsync()
    submitNotificationWork(work, finish = { pendingResult?.finish() }, onFailure = onFailure)
}

internal fun submitNotificationWork(
    work: () -> Unit,
    finish: () -> Unit,
    onFailure: () -> Unit,
    executor: Executor = notificationWorker,
) {
    fun recover(error: Exception) {
        Log.w("WeatherNotifications", "Notification broadcast failed", error)
        try {
            onFailure()
        } catch (retryError: Exception) {
            Log.w("WeatherNotifications", "Unable to schedule notification retry", retryError)
        }
    }
    try {
        executor.execute {
            try {
                work()
            } catch (error: Exception) {
                recover(error)
            } finally {
                finish()
            }
        }
    } catch (error: RejectedExecutionException) {
        try {
            recover(error)
        } finally {
            finish()
        }
    }
}
