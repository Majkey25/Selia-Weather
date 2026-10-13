package cz.majkey.pocasicesko.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class WeatherParserAndroidTest {
    @Test
    fun cloudLayerBoundsUseTheRawNumberOnAndroid() {
        val values = listOf(-1.0, -0.1, 100.1, 255.0, 0.0, 12.8, 100.0)
        val expected = listOf(null, null, null, null, 0, 12, 100)
        values.forEachIndexed { index, value ->
            val root = JSONObject(FORECAST)
            for (field in listOf("cloud_cover_low", "cloud_cover_mid", "cloud_cover_high")) {
                root.getJSONObject("current").put(field, value)
                root.getJSONObject("hourly").put(field, JSONArray(listOf(value)))
            }
            val snapshot = WeatherParser.parseForecast(root.toString(), 1L)
            assertEquals(listOf(expected[index], expected[index], expected[index]), listOf(
                snapshot.current.cloudCoverLow, snapshot.current.cloudCoverMid, snapshot.current.cloudCoverHigh,
            ))
            assertEquals(listOf(expected[index], expected[index], expected[index]), listOf(
                snapshot.hourly.single().cloudCoverLow, snapshot.hourly.single().cloudCoverMid,
                snapshot.hourly.single().cloudCoverHigh,
            ))
            assertEquals(19.0, snapshot.hourly.single().temperature, 0.0)
            assertEquals(1, snapshot.daily.size)
        }
    }

    private companion object {
        val FORECAST = """
            {
              "timezone":"Europe/Prague",
              "current":{"time":"2026-10-13T12:00","temperature_2m":19,"apparent_temperature":18,
                "relative_humidity_2m":48,"precipitation":0,"weather_code":3,"cloud_cover":100,
                "pressure_msl":1023,"wind_speed_10m":8,"wind_direction_10m":34,"wind_gusts_10m":16,"is_day":1},
              "hourly":{"time":["2026-10-13T12:00"],"temperature_2m":[19],"relative_humidity_2m":[48],
                "precipitation_probability":[5],"precipitation":[0],"weather_code":[3],
                "pressure_msl":[1023],"wind_speed_10m":[8],"wind_direction_10m":[34],"is_day":[1]},
              "daily":{"time":["2026-10-13"],"weather_code":[3],"temperature_2m_max":[22],"temperature_2m_min":[10],
                "sunrise":["2026-10-13T07:00"],"sunset":["2026-10-13T18:00"],"precipitation_sum":[0],
                "precipitation_probability_max":[5],"wind_speed_10m_max":[16]}
            }
        """.trimIndent()
    }
}
