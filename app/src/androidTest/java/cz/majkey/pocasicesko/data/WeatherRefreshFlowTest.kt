package cz.majkey.pocasicesko.data

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

class WeatherRefreshFlowTest {
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private val context = object : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int) =
            base.getSharedPreferences("refresh_flow_test_$name", mode)
    }
    private val location = CzechLocation("London", REGION_WORLD, 51.5074, -0.1278, "GB")

    @Before
    @After
    fun clearTestData() {
        check(base.packageName.endsWith(".debug"))
        for (name in listOf("weather", "weather_forecast")) {
            context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    @Test
    fun liveRefreshShowsProviderDataBeforeEnrichmentWithoutInventingModelAgreement() = runBlocking {
        check(base.packageName.endsWith(".debug"))
        val partials = mutableListOf<WeatherSnapshot>()
        val repository = WeatherRepository(context)
        val result = repository.fetchForecast(location) { partials.add(it) }
        assertEquals(1, partials.size)
        assertNull(partials.single().calculation)
        assertEquals(true, partials.single().hourly.all { it.modelAgreement == null })
        assertNotNull(result.calculation)
        assertEquals(partials.single().timezone, result.timezone)
        assertEquals(partials.single().hourly.map { it.time }, result.hourly.map { it.time })
        assertNull(repository.cachedForecast(location))
    }

    @Test
    fun cancellingAfterTheFirstResponseDoesNotPersistPartialWeather() {
        check(base.packageName.endsWith(".debug"))
        val repository = WeatherRepository(context)
        repository.selectLocation(location)
        assertThrows(CancellationException::class.java) {
            runBlocking {
                repository.fetchForecast(location) { throw CancellationException("Screen closed") }
            }
        }
        assertNull(repository.cachedForecast(location))
    }
}
