package cz.majkey.pocasicesko.ui

import android.app.NotificationManager
import androidx.test.platform.app.InstrumentationRegistry
import cz.majkey.pocasicesko.data.CzechLocation
import cz.majkey.pocasicesko.data.WeatherWarningsRepository
import cz.majkey.pocasicesko.data.WeatherWarningsStatus
import cz.majkey.pocasicesko.data.parseWeatherInstant
import java.time.Instant
import cz.majkey.pocasicesko.notification.WeatherAlertCategory
import cz.majkey.pocasicesko.notification.WeatherAlerts
import cz.majkey.pocasicesko.notification.DailyBriefingScheduler
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class WeatherWarningsDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun weatherSourceTimestampsSupportZuluAndPositiveAndNegativeOffsets() {
        val expected = Instant.parse("2026-09-22T07:39:00Z")
        assertEquals(expected, parseWeatherInstant("2026-09-22T09:39:00+02:00"))
        assertEquals(expected, parseWeatherInstant("2026-09-22T07:39:00+00:00"))
        assertEquals(expected, parseWeatherInstant("2026-09-22T03:39:00-04:00"))
        assertEquals(expected, parseWeatherInstant("2026-09-22T07:39:00Z"))
    }

    @Test
    fun officialCzechFeedParsesOnAndroidAndMatchesTheSelectedPlace() = runBlocking {
        val result = WeatherWarningsRepository(context).fetch(CzechLocation("Prague", "", 50.0755, 14.4378, "CZ"))
        assertEquals("ČHMÚ", result.sourceName)
        assertEquals(result.toString(), WeatherWarningsStatus.AVAILABLE, result.status)
    }

    @Test
    fun officialUsCoordinatesWithoutCountryLoadOnAndroid() = runBlocking {
        val result = WeatherWarningsRepository(context).fetch(CzechLocation("New York", "", 40.7484, -73.9857))
        assertEquals(result.toString(), WeatherWarningsStatus.AVAILABLE, result.status)
    }

    @Test
    fun notificationCategoriesHaveIndependentAndroidChannels() {
        WeatherAlerts.ensureChannels(context)
        DailyBriefingScheduler.ensureChannel(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        assertEquals(WeatherAlertCategory.entries.size, WeatherAlertCategory.entries.map { it.channelId }.distinct().size)
        WeatherAlertCategory.entries.forEach { assertNotNull(it.channelId, manager.getNotificationChannel(it.channelId)) }
        assertNotNull(manager.getNotificationChannel("daily_weather_briefing"))
    }
}
