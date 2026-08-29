package cz.majkey.pocasicesko.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherRepositoryTest {
    @Test
    fun requestsSevenPastDaysAndFourteenForecastDays() {
        val url = WeatherRepository.forecastUrl(CzechLocation("Praha", REGION_PRAGUE, 50.0755, 14.4378))

        assertTrue(url.contains("past_days=7"))
        assertTrue(url.contains("forecast_days=14"))
        assertTrue(url.contains("timezone=auto"))
        assertFalse(url.contains("Europe%2FPrague"))
        assertFalse(url.contains("models="))
    }

    @Test
    fun searchesAndParsesLocationsWorldwide() {
        val url = WeatherRepository.geocodingUrl("Berlin", "en")
        val results = parseLocationSearchResults(
            """
                {
                  "results":[
                    {"name":"Berlin","country_code":"DE","country":"Germany","admin1":"Berlin","latitude":52.52,"longitude":13.405},
                    {"name":"Praha","country_code":"CZ","country":"Czechia","admin1_id":3067695,"admin1":"Capital City of Prague","latitude":50.0755,"longitude":14.4378}
                  ]
                }
            """.trimIndent(),
        )

        assertFalse(url.contains("countryCode="))
        assertEquals(CzechLocation("Berlin", "Berlin", 52.52, 13.405, "DE"), results[0])
        assertEquals(CzechLocation("Praha", REGION_PRAGUE, 50.0755, 14.4378, "CZ"), results[1])
    }
}
