package cz.majkey.pocasicesko.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherRepositoryTest {
    @Test
    fun requestsSevenPastDaysAndFourteenForecastDays() {
        val url = WeatherRepository.forecastUrl(CzechLocation("Praha", REGION_PRAGUE, 50.0755, 14.4378))

        assertTrue(url.contains("past_days=7"))
        assertTrue(url.contains("forecast_days=14"))
        assertFalse(url.contains("models="))
    }
}
