package cz.majkey.pocasicesko.data

import androidx.test.platform.app.InstrumentationRegistry
import cz.majkey.pocasicesko.R
import cz.majkey.pocasicesko.ui.labelResource
import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class WeatherParserAndroidTest {
    @Test
    fun combinedMetarPrecipitationSurvivesFusionAndNativeJsonRoundTrip() {
        val now = Instant.parse("2026-10-13T10:05:00Z")
        val root = JSONObject(FORECAST)
        root.getJSONObject("current").put("weather_code", 0).put("cloud_cover", 0)
        val model = WeatherParser.parseForecast(root.toString(), 1L).current
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for ((encoded, expected) in mapOf(
            "-DZRA" to 58, "DZRA" to 59, "+DZRA" to 59,
            "-RADZ" to 58, "RADZ" to 59, "+RADZ" to 59,
            "-TSRAGR" to 96, "TSRAGR" to 96, "+TSRAGR" to 96,
        )) {
            val report = JSONObject().put("icaoId", "LKPR").put("obsTime", now.epochSecond - 300)
                .put("qcField", 16).put("lat", 50.0).put("lon", 14.0)
                .put("wxString", encoded).put("cover", "BKN").put("temp", 12)
            val observed = parseMetarCurrentConditions(JSONArray().put(report).toString()).single()
            val fused = fuseCurrentConditions(model, CzechLocation("Point", REGION_PRAGUE, 50.0, 14.0),
                listOf(observed), now)
            val saved = applyCurrentConditionsToForecastJson(root.toString(), fused)
            val current = WeatherParser.parseForecast(saved, 1L).current
            assertEquals(encoded, expected, current.weatherCode)
            assertEquals(encoded, 0.0, current.precipitation, 0.0)
            assertEquals(encoded, 63, current.cloudCover)
            val label = conditionFor(current.weatherCode).labelResource()
            val expectedLabel = when (expected) {
                58 -> R.string.condition_light_drizzle_and_rain
                59 -> R.string.condition_drizzle_and_rain
                else -> R.string.condition_storm
            }
            assertEquals(encoded, context.getString(expectedLabel), context.getString(label))
        }
    }

    @Test
    fun totalCloudBoundsUseTheRawNumberOnAndroid() {
        val values = listOf(-1.0, -0.1, 100.1, 255.0, 0.0, 12.8, 100.0, 19.5, 49.5, 79.5)
        val expected = listOf(null, null, null, null, 0, 13, 100, 20, 50, 80)
        values.forEachIndexed { index, value ->
            val root = JSONObject(FORECAST)
            root.getJSONObject("hourly").put("cloud_cover", JSONArray(listOf(value)))
            val snapshot = WeatherParser.parseForecast(root.toString(), 1L)
            assertEquals(expected[index], snapshot.hourly.single().cloudCover)
            root.getJSONObject("current").put("cloud_cover", value)
            if (index < 4) {
                assertThrows(JSONException::class.java) { WeatherParser.parseForecast(root.toString(), 1L) }
            } else {
                assertEquals(expected[index], WeatherParser.parseForecast(root.toString(), 1L).current.cloudCover)
            }
        }
    }

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
