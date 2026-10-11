package cz.majkey.pocasicesko.notification

import android.app.job.JobScheduler
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.res.Configuration
import cz.majkey.pocasicesko.data.DailyWeather
import cz.majkey.pocasicesko.data.WeatherRepository
import java.util.Locale
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationRecoveryTest {
    private val base get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val context get() = object : ContextWrapper(base) {
        override fun getSharedPreferences(name: String, mode: Int) =
            super.getSharedPreferences("notification_recovery_test_$name", mode)
    }

    @Test
    fun disabledRainSchedulingDoesNotReadForecastPreferences() {
        var forecastReads = 0
        val context = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences {
                if (name == WeatherRepository.PREFERENCES_NAME) forecastReads++
                return super.getSharedPreferences(name, mode)
            }
        }
        check(base.packageName.endsWith(".debug"))
        val scheduler = base.getSystemService(JobScheduler::class.java)
        val previous = scheduler.getPendingJob(WeatherAlertScheduler.JOB_ID)
        val previousSettings = WeatherAlertSettings.load(context)
        try {
            for (settings in listOf(
                WeatherAlertSettings(backgroundAlertsAllowed = false),
                WeatherAlertSettings(backgroundAlertsAllowed = true, rainEnabled = false),
            )) {
                settings.save(context)
                WeatherAlertScheduler.sync(context)
                assertEquals(0, forecastReads)
            }
        } finally {
            previousSettings.save(context)
            scheduler.cancel(WeatherAlertScheduler.JOB_ID)
            previous?.let(scheduler::schedule)
        }
    }

    @Test
    fun rainyClothingAdviceIsOneSentenceInEverySupportedLanguage() {
        val day = DailyWeather("2026-10-11", 61, 8.0, 3.0, "06:00", "18:00", 2.0, 70, 10.0)
        val expected = mapOf(
            "en" to "Wear a warm coat and take an umbrella.",
            "cs" to "Oblečte si teplý kabát a vezměte si deštník.",
            "de" to "Einen warmen Mantel anziehen und einen Regenschirm mitnehmen.",
            "es" to "Ponte un abrigo cálido y lleva paraguas.",
            "fr" to "Portez un manteau chaud et prenez un parapluie.",
        )
        for ((language, text) in expected) {
            val config = Configuration(base.resources.configuration).apply { setLocale(Locale.forLanguageTag(language)) }
            val localized = base.createConfigurationContext(config)
            assertEquals(text, dailyBriefingText(localized, day))
            assertFalse(dailyBriefingText(localized, day.copy(precipitationSum = 0.0, precipitationProbability = null,
                weatherCode = 0)).contains(localized.getString(cz.majkey.pocasicesko.R.string.daily_briefing_no_umbrella)))
        }
    }

    @Test
    fun missedMorningBriefingExpiresInsteadOfArrivingWhenAppOpensLater() {
        val context = context
        WeatherAlertSettings(backgroundAlertsAllowed = true).save(context)
        context.getSharedPreferences("daily_briefing", Context.MODE_PRIVATE).edit().putBoolean("enabled", true).commit()
        val preferences = context.getSharedPreferences("weather_refresh", Context.MODE_PRIVATE)
        preferences.edit().putString("briefing_day", LocalDate.now().toString())
            .putLong("briefing_scheduled_at", System.currentTimeMillis() - 2 * 60 * 60 * 1000L).commit()
        assertNull(WeatherRefreshScheduler.pendingBriefing(context))
    }

    @Test
    fun changingBriefingTimeRejectsTheOldPendingDelivery() {
        val context = context
        WeatherAlertSettings(backgroundAlertsAllowed = true).save(context)
        val scheduledAt = System.currentTimeMillis() / 60_000 * 60_000
        val oldTime = Instant.ofEpochMilli(scheduledAt).atZone(ZoneId.systemDefault()).toLocalTime()
        val briefing = context.getSharedPreferences("daily_briefing", Context.MODE_PRIVATE)
        briefing.edit().putBoolean("enabled", true)
            .putInt("time_minutes", (oldTime.hour * 60 + oldTime.minute + 60) % 1440).commit()
        val refresh = context.getSharedPreferences("weather_refresh", Context.MODE_PRIVATE)
        refresh.edit().putString("briefing_day", LocalDate.now().toString())
            .putLong("briefing_scheduled_at", scheduledAt).commit()

        assertNull(WeatherRefreshScheduler.pendingBriefing(context))
        assertFalse(refresh.contains("briefing_scheduled_at"))
    }

    @Test
    fun bootAndUpdateRestoreAlertsWithMorningBriefingOff() {
        val context = context
        check(base.packageName.endsWith(".debug"))
        val scheduler = base.getSystemService(JobScheduler::class.java)
        val previous = scheduler.getPendingJob(WeatherAlertScheduler.JOB_ID)
        context.getSharedPreferences("daily_briefing", Context.MODE_PRIVATE).edit().putBoolean("enabled", false).commit()
        WeatherAlertSettings(backgroundAlertsAllowed = true, rainEnabled = true, officialWarningsEnabled = true).save(context)
        try {
            for (action in listOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)) {
                scheduler.cancel(WeatherAlertScheduler.JOB_ID)
                DailyBriefingReceiver().onReceive(context, Intent(action))
                val job = scheduler.getPendingJob(WeatherAlertScheduler.JOB_ID)
                assertNotNull("$action must restore alerts without opening an activity", job)
                assertTrue(requireNotNull(job).isPersisted)
                assertTrue(job.isPeriodic)
                assertFalse(DailyBriefingScheduler.isEnabled(context))
            }
        } finally {
            scheduler.cancel(WeatherAlertScheduler.JOB_ID)
            previous?.let(scheduler::schedule)
        }
    }

    @Test
    fun recoveryDoesNotEnableNotificationsTheUserTurnedOff() {
        val context = context
        check(base.packageName.endsWith(".debug"))
        val scheduler = base.getSystemService(JobScheduler::class.java)
        val previous = scheduler.getPendingJob(WeatherAlertScheduler.JOB_ID)
        context.getSharedPreferences("daily_briefing", Context.MODE_PRIVATE).edit().putBoolean("enabled", false).commit()
        WeatherAlertSettings(backgroundAlertsAllowed = true, rainEnabled = false, officialWarningsEnabled = false).save(context)
        try {
            scheduler.cancel(WeatherAlertScheduler.JOB_ID)
            DailyBriefingReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
            assertNull(scheduler.getPendingJob(WeatherAlertScheduler.JOB_ID))
            assertFalse(DailyBriefingScheduler.isEnabled(context))
        } finally {
            scheduler.cancel(WeatherAlertScheduler.JOB_ID)
            previous?.let(scheduler::schedule)
        }
    }

    @Test
    fun existingPermissionAndAlertPreferencesCannotReplaceBackgroundConsent() {
        val context = context
        check(base.packageName.endsWith(".debug"))
        val scheduler = base.getSystemService(JobScheduler::class.java)
        val previous = scheduler.getPendingJob(WeatherAlertScheduler.JOB_ID)
        context.getSharedPreferences("weather_alert_settings", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("daily_briefing", Context.MODE_PRIVATE).edit().putBoolean("enabled", true).commit()
        try {
            assertTrue(WeatherAlertSettings.needsBackgroundDisclosure(context))
            assertFalse(WeatherAlertSettings.load(context).backgroundAlertsAllowed)
            for (action in listOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)) {
                DailyBriefingReceiver().onReceive(context, Intent(action))
                assertNull(scheduler.getPendingJob(WeatherAlertScheduler.JOB_ID))
                assertFalse(DailyBriefingScheduler.isEnabled(context))
            }
            val accepted = WeatherAlertSettings.load(context).copy(backgroundAlertsAllowed = true, rainLeadHours = 3)
            accepted.save(context)
            WeatherAlertScheduler.sync(context)
            assertNotNull(scheduler.getPendingJob(WeatherAlertScheduler.JOB_ID))
            assertTrue(DailyBriefingScheduler.isEnabled(context))
            accepted.copy(backgroundAlertsAllowed = false).save(context)
            WeatherAlertScheduler.sync(context)
            assertFalse(WeatherAlertSettings.needsBackgroundDisclosure(context))
            assertNull(scheduler.getPendingJob(WeatherAlertScheduler.JOB_ID))
            assertFalse(DailyBriefingScheduler.isEnabled(context))
            assertTrue(WeatherAlertSettings.load(context).rainLeadHours == 3)
        } finally {
            scheduler.cancel(WeatherAlertScheduler.JOB_ID)
            previous?.let(scheduler::schedule)
            context.getSharedPreferences("daily_briefing", Context.MODE_PRIVATE).edit().clear().commit()
        }
    }
}
