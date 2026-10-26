package cz.majkey.pocasicesko.data

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class WeatherCacheTest {
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private val context = object : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int) =
            base.getSharedPreferences("cache_test_$name", mode)
    }
    private val settings = context.getSharedPreferences("weather", Context.MODE_PRIVATE)
    private val cache = context.getSharedPreferences("weather_forecast", Context.MODE_PRIVATE)
    private val location = CzechLocation("Prague", "Prague", 50.0755, 14.4378)

    @Before
    @After
    fun clearTestData() {
        check(base.packageName.endsWith(".debug"))
        settings.edit().clear().commit()
        cache.edit().clear().commit()
    }

    @Test
    fun migratesLegacyForecastWithoutLosingSettingsOrAcceptingAnotherLocation() {
        settings.edit().putString("location_name", location.name).putString("favorite_locations", "[]")
            .putString("cache_json", FORECAST).putString("cache_latitude", location.latitude.toString())
            .putString("cache_longitude", location.longitude.toString()).putLong("cache_updated_at", 123L).commit()
        val repository = WeatherRepository(context)
        assertNull(repository.cachedForecast(location.copy(latitude = 51.0)))
        assertEquals(FORECAST, settings.getString("cache_json", null))
        val expected = WeatherParser.parseForecast(FORECAST, 123L)
        assertEquals(expected, repository.cachedForecast(location))
        assertFalse(settings.contains("cache_json"))
        assertFalse(settings.contains("cache_latitude"))
        assertEquals("[]", settings.getString("favorite_locations", null))
        assertEquals(location.name, repository.lastLocation().name)
        assertEquals(FORECAST, cache.getString("cache_json", null))
        assertEquals(expected, WeatherRepository(context).cachedForecast(location))
    }

    @Test
    fun writesOnlyForecastPayloadToSeparateCacheAndKeepsWidgetValuesSmall() {
        val repository = WeatherRepository(context)
        val snapshot = WeatherParser.parseForecast(FORECAST, 456L)
        val persist = WeatherRepository::class.java.getDeclaredMethod(
            "persist", CzechLocation::class.java, String::class.java, WeatherSnapshot::class.java,
        ).apply { isAccessible = true }
        persist.invoke(repository, location, FORECAST, snapshot)
        assertEquals(snapshot, repository.cachedForecast(location))
        assertFalse(settings.contains("cache_json"))
        assertEquals(location.name, settings.getString(WeatherRepository.KEY_WIDGET_CITY, null))
        assertEquals(456L, settings.getLong(WeatherRepository.KEY_WIDGET_UPDATED_AT, 0L))
        assertNull(repository.cachedForecast(location.copy(longitude = 15.0)))
        cache.edit().putString("cache_json", "{}").commit()
        assertNull(repository.cachedForecast(location))
    }

    private companion object {
        val FORECAST = """
            {"timezone":"Europe/Prague",
            "current":{"time":"2026-10-26T12:00","temperature_2m":19,"apparent_temperature":18,
              "relative_humidity_2m":48,"precipitation":0,"weather_code":3,"cloud_cover":100,
              "pressure_msl":1023,"wind_speed_10m":8,"wind_direction_10m":34,"wind_gusts_10m":16,"is_day":1},
            "hourly":{"time":["2026-10-26T12:00"],"temperature_2m":[19],"relative_humidity_2m":[48],
              "precipitation_probability":[5],"precipitation":[0],"weather_code":[3],
              "pressure_msl":[1023],"wind_speed_10m":[8],"wind_direction_10m":[34],"is_day":[1]},
            "daily":{"time":["2026-10-26"],"weather_code":[3],"temperature_2m_max":[22],"temperature_2m_min":[10],
              "sunrise":["2026-10-26T07:00"],"sunset":["2026-10-26T18:00"],"precipitation_sum":[0],
              "precipitation_probability_max":[5],"wind_speed_10m_max":[16]}}
        """.trimIndent()
    }
}
