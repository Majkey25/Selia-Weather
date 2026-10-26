package cz.majkey.pocasicesko.notification

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Looper
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationBroadcastWorkTest {
    @Test
    fun completionRunsOnceOnSuccessFailureAndRejectedAdmission() {
        for (failure in 0..2) {
            var completed = 0
            var retried = 0
            var ran = false
            submitNotificationWork(
                work = {
                    ran = true
                    if (failure == 1) throw IllegalStateException("local read failed")
                },
                finish = { completed++ },
                onFailure = { retried++ },
                executor = Executor {
                    if (failure == 2) throw RejectedExecutionException("queue full")
                    it.run()
                },
            )
            assertEquals(1, completed)
            assertEquals(if (failure == 0) 0 else 1, retried)
            assertEquals(failure != 2, ran)
        }
    }

    @Test
    fun retryFailureStillFinishesTheBroadcast() {
        var completed = 0
        submitNotificationWork(
            work = { throw IllegalStateException("local read failed") },
            finish = { completed++ },
            onFailure = { throw IllegalStateException("scheduler unavailable") },
            executor = Executor { it.run() },
        )
        assertEquals(1, completed)
    }

    @Test
    fun alertReceiverReturnsBeforeReadingSettingsOnItsWorker() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val base = instrumentation.targetContext
        check(base.packageName.endsWith(".debug"))
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val readCompleted = CountDownLatch(1)
        val firstRead = AtomicBoolean(true)
        val preferencesName = "notification_broadcast_test_weather_alert_settings"
        val preferences = base.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
        preferences.edit().clear().putBoolean("background_alerts_allowed", false).commit()
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences {
                assertNotEquals(Looper.getMainLooper().thread, Thread.currentThread())
                if (firstRead.compareAndSet(true, false)) {
                    started.countDown()
                    assertTrue(release.await(5, TimeUnit.SECONDS))
                    readCompleted.countDown()
                }
                return super.getSharedPreferences("notification_broadcast_test_$name", mode)
            }
        }
        try {
            instrumentation.runOnMainSync { WeatherAlertReceiver().onReceive(context, Intent()) }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            assertFalse(readCompleted.await(50, TimeUnit.MILLISECONDS))
        } finally {
            release.countDown()
            assertTrue(readCompleted.await(5, TimeUnit.SECONDS))
            preferences.edit().clear().commit()
        }
    }
}
