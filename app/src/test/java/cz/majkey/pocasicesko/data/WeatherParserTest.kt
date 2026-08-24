package cz.majkey.pocasicesko.data

import org.json.JSONException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class WeatherParserTest {
    @Test
    fun parsesForecastShape() {
        val snapshot = WeatherParser.parseForecast(VALID_FORECAST, updatedAtEpochMillis = 123L)

        assertEquals("Europe/Prague", snapshot.timezone)
        assertEquals(19.1, snapshot.current.temperature, 0.0)
        assertEquals(2, snapshot.hourly.size)
        assertEquals(1, snapshot.daily.size)
        assertEquals(123L, snapshot.updatedAtEpochMillis)
    }

    @Test
    fun rejectsMismatchedHourlyArrays() {
        val broken = VALID_FORECAST.replace(
            "\"temperature_2m\":[19.0,20.0]",
            "\"temperature_2m\":[19.0]",
        )

        assertThrows(JSONException::class.java) {
            WeatherParser.parseForecast(broken, updatedAtEpochMillis = 123L)
        }
    }

    @Test
    fun mapsWeatherCodesToTypedConditions() {
        assertEquals(WeatherConditionKey.CLEAR_DAY, conditionFor(0, true).key)
        assertEquals(WeatherConditionKey.DRIZZLE, conditionFor(51, true).key)
        assertEquals(WeatherConditionKey.SHOWERS, conditionFor(80, true).key)
        assertEquals(WeatherConditionKey.STORM, conditionFor(95, true).key)
        assertEquals(WeatherConditionKey.UNKNOWN, conditionFor(999, true).key)
    }

    companion object {
        private val VALID_FORECAST = """
            {
              "timezone":"Europe/Prague",
              "current":{
                "time":"2026-08-24T12:00",
                "temperature_2m":19.1,
                "apparent_temperature":18.2,
                "relative_humidity_2m":48,
                "precipitation":0.0,
                "weather_code":0,
                "cloud_cover":2,
                "pressure_msl":1023.0,
                "wind_speed_10m":8.3,
                "wind_direction_10m":34,
                "wind_gusts_10m":16.2,
                "is_day":1
              },
              "hourly":{
                "time":["2026-08-24T12:00","2026-08-24T13:00"],
                "temperature_2m":[19.0,20.0],
                "relative_humidity_2m":[48,46],
                "precipitation_probability":[0,5],
                "precipitation":[0.0,0.0],
                "weather_code":[0,1],
                "pressure_msl":[1023.0,1022.5],
                "wind_speed_10m":[8.3,9.0],
                "wind_direction_10m":[34,40],
                "is_day":[1,1]
              },
              "daily":{
                "time":["2026-08-24"],
                "weather_code":[1],
                "temperature_2m_max":[22.2],
                "temperature_2m_min":[10.9],
                "sunrise":["2026-08-24T06:05"],
                "sunset":["2026-08-24T20:03"],
                "precipitation_sum":[0.0],
                "precipitation_probability_max":[5],
                "wind_speed_10m_max":[16.6]
              }
            }
        """.trimIndent()
    }
}
