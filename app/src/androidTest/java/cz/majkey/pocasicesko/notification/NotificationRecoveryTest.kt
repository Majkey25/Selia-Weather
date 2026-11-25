package cz.majkey.pocasicesko.notification

import android.app.job.JobScheduler
import android.app.job.JobInfo
import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.res.Configuration
import android.os.SystemClock
import cz.majkey.pocasicesko.data.CzechLocation
import cz.majkey.pocasicesko.data.CurrentWeather
import cz.majkey.pocasicesko.data.DailyWeather
import cz.majkey.pocasicesko.data.DeviceLocationFix
import cz.majkey.pocasicesko.data.DeviceLocationRepository
import cz.majkey.pocasicesko.data.HourlyWeather
import cz.majkey.pocasicesko.data.WeatherRepository
import cz.majkey.pocasicesko.data.WeatherSnapshot
import cz.majkey.pocasicesko.data.WeatherWarning
import cz.majkey.pocasicesko.data.WeatherWarningSeverity
import cz.majkey.pocasicesko.data.WeatherWarningsResult
import cz.majkey.pocasicesko.data.WeatherWarningsStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.Locale
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

class NotificationRecoveryTest {
    private val base get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val context get() = object : ContextWrapper(base) {
        override fun getSharedPreferences(name: String, mode: Int) =
            super.getSharedPreferences("notification_recovery_test_$name", mode)
    }

    @Test
    fun ongoingRainDoesNotNotifyAgainAfterCooldown() {
        check(base.packageName.endsWith(".debug"))
        assumeTrue(WeatherAlertScheduler.notificationsAllowed(base))
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int) =
                super.getSharedPreferences("ongoing_rain_test_$name", mode)
        }
        val repository = WeatherRepository(context)
        val place = CzechLocation("Fixture rain", "", 50.0755, 14.4378, "CZ")
        repository.selectLocation(place)
        val category = WeatherAlertCategory.RAIN
        val deliveries = context.getSharedPreferences("weather_alert_deliveries", Context.MODE_PRIVATE)
        val now = Instant.now()
        val nextHour = now.truncatedTo(ChronoUnit.HOURS).plus(2, ChronoUnit.HOURS)
        val earlier = nextHour.minus(8, ChronoUnit.HOURS).toEpochMilli()
        val previous = "${place.latitude},${place.longitude}|$earlier"
        val ongoing = HourlyWeather(nextHour.plus(1, ChronoUnit.HOURS).atOffset(ZoneOffset.UTC).toLocalDateTime().toString(),
            10.0, 50, 80, 2.0, 61, 1010.0, 8.0, 180, true)
        val snapshot = WeatherSnapshot("UTC", CurrentWeather("", 10.0, 10.0, 50, 0.0, 0, 0,
            1010.0, 8.0, 180, 10.0, true), listOf(ongoing), emptyList(), now.toEpochMilli())
        try {
            WeatherAlertSettings(backgroundAlertsAllowed = true, rainLeadHours = 2).save(context)
            deliveries.edit().clear().putString(category.channelId, previous)
                .putLong("${category.channelId}_posted_at", now.minus(8, ChronoUnit.HOURS).toEpochMilli())
                .putLong("${category.channelId}_last_valid_at", nextHour.toEpochMilli()).commit()

            WeatherAlerts.evaluateAndNotify(context, place, snapshot)

            assertEquals(previous, deliveries.getString(category.channelId, null))

            deliveries.edit().putLong("${category.channelId}_last_valid_at", earlier).commit()
            WeatherAlerts.evaluateAndNotify(context, place, snapshot)

            assertEquals("${place.latitude},${place.longitude}|${nextHour.toEpochMilli()}",
                deliveries.getString(category.channelId, null))
        } finally {
            WeatherAlertSettings().save(context)
            WeatherAlertScheduler.scheduleRainCheck(context, null)
            deliveries.edit().clear().commit()
        }
    }

    @Test
    fun officialWarningRemovalAndReappearanceAreSilentButEscalationAlerts() = runBlocking {
        check(base.packageName.endsWith(".debug"))
        assumeTrue(WeatherAlertScheduler.notificationsAllowed(base))
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int) =
                super.getSharedPreferences("warning_revisions_test_$name", mode)
        }
        val place = CzechLocation("Fixture warnings", "", 50.0755, 14.4378, "CZ")
        WeatherRepository(context).selectLocation(place)
        val delivery = context.getSharedPreferences("official_warning_delivery", Context.MODE_PRIVATE)
        val manager = context.getSystemService(NotificationManager::class.java)
        val id = WeatherAlerts.NOTIFICATION_ID_OFFICIAL
        val checkedAt = Instant.now().minusSeconds(10)
        val rain = WeatherWarning("rain", "ČHMÚ", "Heavy rain", "", "Avoid flood water",
            WeatherWarningSeverity.SEVERE, checkedAt, checkedAt.plusSeconds(3600), "https://vystrahy-cr.chmi.cz/")
        val wind = rain.copy(id = "wind", headline = "Strong wind", instruction = "Secure loose items")
        fun publish(offset: Long, vararg warnings: WeatherWarning) {
            OfficialWarningNotifications.publish(context, place, WeatherWarningsResult(WeatherWarningsStatus.AVAILABLE,
                warnings.toList(), checkedAt.plusSeconds(offset), "ČHMÚ", rain.webUrl))
        }
        suspend fun shown(text: String): Notification = withTimeout(5_000) {
            var notification: Notification? = null
            while (notification == null) {
                notification = manager.activeNotifications.firstOrNull { it.id == id &&
                    it.notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString() == text }?.notification
                if (notification == null) delay(25)
            }
            notification
        }
        val flag = Notification.FLAG_ONLY_ALERT_ONCE
        val rainText = "Heavy rain\nAvoid flood water\nČHMÚ"
        val both = "$rainText\n\nStrong wind\nSecure loose items\nČHMÚ"
        try {
            delivery.edit().clear().commit()
            WeatherAlertSettings(backgroundAlertsAllowed = true).save(context)
            publish(0, rain, wind)
            assertEquals(0, shown(both).flags and flag)
            publish(1, rain)
            assertEquals(flag, shown(rainText).flags and flag)
            publish(2, rain, wind)
            assertEquals(flag, shown(both).flags and flag)
            publish(3, rain.copy(severity = WeatherWarningSeverity.EXTREME, instruction = "Evacuate low areas"))
            assertEquals(0, shown("Heavy rain\nEvacuate low areas\nČHMÚ").flags and flag)
        } finally {
            WeatherAlertSettings().save(context)
            manager.cancel(id)
            delivery.edit().clear().commit()
        }
    }

    @Test
    fun followedOfficialWarningSurvivesGpsDriftAndProviderFailure() = runBlocking {
        check(base.packageName.endsWith(".debug"))
        val device = DeviceLocationRepository(base)
        assumeTrue(device.hasLocationPermission() && device.isLocationEnabled() &&
            WeatherAlertScheduler.notificationsAllowed(base))
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int) =
                super.getSharedPreferences("notification_official_test_$name", mode)
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        val repository = WeatherRepository(context)
        val notificationId = WeatherAlerts.NOTIFICATION_ID_OFFICIAL
        val delivery = context.getSharedPreferences("official_warning_delivery", Context.MODE_PRIVATE)
        val place = CzechLocation("Fixture A", "", 50.0755, 14.4378, "CZ")
        val checkedAt = Instant.now().minusSeconds(10)
        val warning = WeatherWarning("fixture", "ČHMÚ", "Heavy rain", "", "Avoid flood water",
            WeatherWarningSeverity.SEVERE, checkedAt, checkedAt.plusSeconds(3600), "https://vystrahy-cr.chmi.cz/")
        val report = WeatherWarningsResult(WeatherWarningsStatus.AVAILABLE, listOf(warning), checkedAt, "ČHMÚ", warning.webUrl)

        fun publish(location: CzechLocation, result: WeatherWarningsResult) {
            repository.selectCurrentFix(DeviceLocationFix(location, System.currentTimeMillis(),
                SystemClock.elapsedRealtimeNanos()), followDevice = true)
            OfficialWarningNotifications.publish(context, location, result)
        }
        fun activeWarning() = manager.activeNotifications.firstOrNull {
            it.id == notificationId && it.notification.extras.getCharSequence(Notification.EXTRA_TITLE)
                ?.contains(place.name) == true
        }
        try {
            delivery.edit().clear().commit()
            WeatherAlertSettings(backgroundAlertsAllowed = true, officialWarningsEnabled = true).save(context)
            publish(place, report)
            val original = withTimeout(5_000) {
                var active = activeWarning()
                while (active == null) {
                    delay(25)
                    active = activeWarning()
                }
                active
            }
            val nextFix = place.copy(name = "Fixture B", latitude = 50.0756)
            publish(nextFix, report.copy(status = WeatherWarningsStatus.UNAVAILABLE,
                warnings = emptyList(), checkedAt = checkedAt.plusSeconds(1)))
            publish(nextFix, report.copy(checkedAt = checkedAt.plusSeconds(2)))
            val recovered = requireNotNull(activeWarning())
            assertEquals(original.postTime, recovered.postTime)
        } finally {
            manager.cancel(notificationId)
            delivery.edit().clear().commit()
        }
    }

    @Test
    fun delayedPreparationDoesNotRescheduleTheMorningDelivery() {
        check(base.packageName.endsWith(".debug"))
        var alarmReads = 0
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int) =
                super.getSharedPreferences("notification_prepare_test_$name", mode)
            override fun getSystemService(name: String): Any? {
                if (name == Context.ALARM_SERVICE) {
                    alarmReads++
                    assertEquals("PREPARE must not reschedule the morning alarm after rain sync", 1, alarmReads)
                }
                return super.getSystemService(name)
            }
        }
        val scheduler = base.getSystemService(JobScheduler::class.java)
        val jobIds = intArrayOf(7003, WeatherAlertScheduler.JOB_ID)
        val previousJobs = jobIds.map(scheduler::getPendingJob).filterNotNull()
        val preferences = listOf("weather_alert_settings", "daily_briefing", "weather_refresh")
            .map { context.getSharedPreferences(it, Context.MODE_PRIVATE) }
        try {
            preferences.forEach { it.edit().clear().commit() }
            WeatherAlertSettings(backgroundAlertsAllowed = true, rainEnabled = false,
                officialWarningsEnabled = false).save(context)
            val time = LocalTime.now().minusMinutes(1)
            context.getSharedPreferences("daily_briefing", Context.MODE_PRIVATE).edit()
                .putBoolean("enabled", true).putInt("time_minutes", time.hour * 60 + time.minute).commit()
            assertEquals(JobScheduler.RESULT_SUCCESS, scheduler.schedule(
                JobInfo.Builder(7003, ComponentName(base, WeatherRefreshJob::class.java))
                    .setMinimumLatency(TimeUnit.DAYS.toMillis(1)).build(),
            ))

            DailyBriefingReceiver().handleReceive(context, Intent(DailyBriefingScheduler.ACTION_REFRESH))

            assertEquals(1, alarmReads)
        } finally {
            preferences.forEach { it.edit().clear().commit() }
            jobIds.forEach(scheduler::cancel)
            previousJobs.forEach { scheduler.schedule(it) }
            WeatherAlertScheduler.sync(base)
        }
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
                DailyBriefingReceiver().handleReceive(context, Intent(action))
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
            DailyBriefingReceiver().handleReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
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
                DailyBriefingReceiver().handleReceive(context, Intent(action))
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
