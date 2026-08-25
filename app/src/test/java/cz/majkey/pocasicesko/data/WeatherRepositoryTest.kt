package cz.majkey.pocasicesko.data

import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherRepositoryTest {
    @Test
    fun requestsPastTwentyFourHoursWithFourteenForecastDays() {
        val url = WeatherRepository.forecastUrl(CzechLocation("Praha", REGION_PRAGUE, 50.0755, 14.4378))

        assertTrue(url.contains("past_hours=24"))
        assertTrue(url.contains("forecast_days=14"))
    }
}
