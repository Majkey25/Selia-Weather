package cz.majkey.pocasicesko.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelConsensusTest {
    @Test
    fun selectsLocationSpecificModelInputs() {
        val prague = WeatherRepository.modelForecastUrl(
            CzechLocation("Praha", REGION_PRAGUE, 50.0755, 14.4378, "CZ"),
        )
        val newYork = WeatherRepository.modelForecastUrl(
            CzechLocation("New York", "New York", 40.7128, -74.006, "US"),
        )

        assertTrue(prague.contains("chmi_aladin_seamless"))
        assertTrue(prague.contains("icon_seamless"))
        assertTrue(prague.contains("ecmwf_ifs025"))
        assertTrue(prague.contains("gfs_seamless"))
        assertFalse(newYork.contains("chmi_aladin_seamless"))
        assertTrue(newYork.contains("gem_seamless"))
        assertTrue(newYork.contains("&models=${forecastApiModelsFor(
            CzechLocation("New York", "New York", 40.7128, -74.006, "US"),
        ).joinToString(",")}"))
        assertFalse(newYork.contains("kma_seamless"))
    }

    @Test
    fun blendsContinuousValuesAndDerivesConditionsLocally() {
        val result = blendModelForecast(BASE, MODELS)
        val root = JSONObject(result.json)
        val current = root.getJSONObject("current")
        val hourly = root.getJSONObject("hourly")
        val daily = root.getJSONObject("daily")

        assertEquals(22.0, current.getDouble("temperature_2m"), 0.0)
        assertEquals(0, current.getInt("weather_code"))
        assertEquals(10, current.getInt("cloud_cover"))
        assertEquals(0, current.getInt("wind_direction_10m"))
        assertEquals(67, hourly.getJSONArray("precipitation_probability").getInt(1))
        assertEquals(61, hourly.getJSONArray("weather_code").getInt(1))
        assertEquals(22.0, daily.getJSONArray("temperature_2m_max").getDouble(0), 0.0)
        assertEquals(20.0, daily.getJSONArray("temperature_2m_min").getDouble(0), 0.0)
        assertEquals(0.2, daily.getJSONArray("precipitation_sum").getDouble(0), 0.0)
        assertEquals(180, daily.getJSONArray("wind_direction_10m_dominant").getInt(0))
        assertEquals(ForecastCalculationMode.DIAGNOSTIC_MEDIAN, result.mode)
        assertEquals(listOf("a", "b", "c"), result.contributorIds)
        assertEquals(null, result.fallbackReason)
    }

    @Test
    fun keepsBestMatchWhenFewerThanThreeModelsArePresent() {
        val result = blendModelForecast(BASE, ONE_MODEL)
        val root = JSONObject(result.json)

        assertEquals(99.0, root.getJSONObject("current").getDouble("temperature_2m"), 0.0)
        assertEquals(
            99.0,
            root.getJSONObject("hourly").getJSONArray("temperature_2m").getDouble(0),
            0.0,
        )
        assertEquals(ForecastCalculationMode.BEST_MATCH, result.mode)
        assertEquals(listOf("a"), result.contributorIds)
        assertEquals(ForecastFallbackReason.INSUFFICIENT_CONTRIBUTORS, result.fallbackReason)
    }

    @Test
    fun derivesClearSkyWhenModelCodesAreMissing() {
        val models = JSONObject(MODELS).also { root ->
            val hourly = root.getJSONObject("hourly")
            listOf("a", "b", "c").forEach { suffix -> hourly.remove("weather_code_$suffix") }
        }

        val result = JSONObject(blendModelForecast(BASE, models.toString()).json)

        assertEquals(0, result.getJSONObject("current").getInt("weather_code"))
    }

    @Test
    fun rejectsNegativeProviderPrecipitationInsteadOfBlendingIt() {
        val models = JSONObject(MODELS).also { root ->
            root.getJSONObject("hourly").getJSONArray("precipitation_a").put(1, -0.2)
        }

        val result = JSONObject(blendModelForecast(BASE, models.toString()).json)

        assertEquals(0.0, result.getJSONObject("hourly").getJSONArray("precipitation").getDouble(1), 0.0)
    }

    companion object {
        private val BASE = """
            {
              "timezone":"Europe/Prague",
              "current":{"time":"2026-08-29T19:15","temperature_2m":99,"weather_code":3,"cloud_cover":100,"relative_humidity_2m":50,"precipitation":0,"pressure_msl":1010,"wind_speed_10m":5,"wind_direction_10m":180,"wind_gusts_10m":8,"apparent_temperature":99},
              "hourly":{"time":["2026-08-29T19:00","2026-08-29T20:00"],"temperature_2m":[99,99],"relative_humidity_2m":[50,50],"precipitation_probability":[0,0],"precipitation":[0,0],"weather_code":[3,3],"cloud_cover":[100,100],"pressure_msl":[1010,1010],"wind_speed_10m":[5,5],"wind_direction_10m":[180,180],"wind_gusts_10m":[8,8],"apparent_temperature":[99,99],"is_day":[1,1]},
              "daily":{"time":["2026-08-29"],"weather_code":[3],"temperature_2m_max":[99],"temperature_2m_min":[99],"precipitation_sum":[0],"precipitation_probability_max":[0],"wind_speed_10m_max":[5],"apparent_temperature_max":[99],"apparent_temperature_min":[99],"wind_gusts_10m_max":[8],"wind_direction_10m_dominant":[90]}
            }
        """.trimIndent()

        private val MODELS = """
            {
              "hourly":{
                "time":["2026-08-29T19:00","2026-08-29T20:00"],
                "temperature_2m_a":[20,18],"temperature_2m_b":[22,20],"temperature_2m_c":[40,22],"temperature_2m_d":[null,null],
                "relative_humidity_2m_a":[40,60],"relative_humidity_2m_b":[50,70],"relative_humidity_2m_c":[60,80],
                "precipitation_a":[0,0],"precipitation_b":[0,0.2],"precipitation_c":[0,0.4],
                "weather_code_a":[0,0],"weather_code_b":[0,61],"weather_code_c":[1,61],
                "cloud_cover_a":[0,100],"cloud_cover_b":[10,100],"cloud_cover_c":[20,100],
                "pressure_msl_a":[1012,1011],"pressure_msl_b":[1014,1013],"pressure_msl_c":[1016,1015],
                "wind_speed_10m_a":[10,12],"wind_speed_10m_b":[10,12],"wind_speed_10m_c":[10,12],
                "wind_direction_10m_a":[350,180],"wind_direction_10m_b":[0,180],"wind_direction_10m_c":[10,180],
                "wind_gusts_10m_a":[15,18],"wind_gusts_10m_b":[16,19],"wind_gusts_10m_c":[17,20],
                "apparent_temperature_a":[20,18],"apparent_temperature_b":[22,20],"apparent_temperature_c":[40,22]
              }
            }
        """.trimIndent()

        private val ONE_MODEL = """
            {"hourly":{"time":["2026-08-29T19:00","2026-08-29T20:00"],"temperature_2m_a":[20,18]}}
        """.trimIndent()
    }
}
