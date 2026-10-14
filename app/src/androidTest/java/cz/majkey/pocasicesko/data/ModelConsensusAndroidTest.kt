package cz.majkey.pocasicesko.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelConsensusAndroidTest {
    @Test
    fun providerWetOrMissingSkyVetoSurvivesSmallPositiveBlends() {
        val times = listOf("2026-10-14T12:00", "2026-10-14T13:00")
        val hourly = JSONObject().put("time", JSONArray(times))
        repeat(4) { index ->
            val id = if (index == 3) "chmi_aladin_seamless" else "m$index"
            for ((field, value) in mapOf("temperature_2m" to 20,
                "weather_code" to if (index == 3) 0 else 3,
                "cloud_cover" to if (index == 3) 0 else 100,
                "precipitation" to if (index == 3) 0.0 else 0.05)) {
                hourly.put("${field}_$id", JSONArray(listOf(value, value)))
            }
        }
        val location = CzechLocation("Praha", "Praha", 50.0755, 14.4378, "CZ")
        for (wetIndex in 0..1) for (amount in listOf(0.1, 0.3, JSONObject.NULL)) {
            val base = JSONObject().put("timezone", "UTC").put("utc_offset_seconds", 0)
                .put("current", JSONObject().put("time", times.first()).put("weather_code", 3).put("cloud_cover", 100))
                .put("hourly", JSONObject().put("time", JSONArray(times))
                    .put("weather_code", JSONArray(listOf(3, 3)))
                    .put("cloud_cover", JSONArray(listOf(100, 100)))
                    .put("precipitation", JSONArray(listOf(0.0, 0.0)).put(wetIndex, amount)))
                .put("daily", JSONObject().put("time", JSONArray(listOf("2026-10-14"))))
            val result = JSONObject(blendModelForecast(base.toString(),
                JSONObject().put("utc_offset_seconds", 0).put("hourly", hourly).toString(), location).json)
                .getJSONObject("hourly")
            assertTrue(result.getJSONArray(SKY_SOURCE_MODEL_KEY).isNull(0))
            assertEquals(3, result.getJSONArray("weather_code").getInt(0))
            assertEquals(100, result.getJSONArray("cloud_cover").getInt(0))
            assertEquals(0.05, result.getJSONArray("precipitation").getDouble(0), 0.0)
        }
    }

    @Test
    fun mixedDrizzleRainAndShowersDoNotBecomeDrySky() {
        for (amount in listOf(0.0, 0.2)) {
            val base = JSONObject().put("current", JSONObject().put("time", "2026-10-13T12:00")
                .put("weather_code", 3).put("cloud_cover", 100))
                .put("hourly", JSONObject().put("time", JSONArray(listOf("2026-10-13T12:00")))
                    .put("weather_code", JSONArray(listOf(3)))
                    .put("cloud_cover", JSONArray(listOf(100)))
                    .put("precipitation", JSONArray(listOf(amount))))
                .put("daily", JSONObject().put("time", JSONArray(listOf("2026-10-13"))))
            val hourly = JSONObject().put("time", JSONArray(listOf("2026-10-13T12:00")))
            listOf(51, 51, 61, 80).forEachIndexed { index, code ->
                for ((field, value) in mapOf("temperature_2m" to 20, "weather_code" to code,
                    "cloud_cover" to 100, "precipitation" to amount)) {
                    hourly.put("${field}_m$index", JSONArray(listOf(value)))
                }
            }
            val result = JSONObject(blendModelForecast(base.toString(), JSONObject().put("hourly", hourly).toString()).json)
            assertEquals(3, result.getJSONObject("current").getInt("weather_code"))
            assertEquals(51, result.getJSONObject("hourly").getJSONArray("weather_code").getInt(0))
            assertEquals(amount, result.getJSONObject("hourly").getJSONArray("precipitation").getDouble(0), 0.0)
        }
    }

    @Test
    fun precedingHourRainDoesNotInventAnInstantaneousRainCondition() {
        for (providerCode in listOf(3, 61)) {
            val base = JSONObject().put("current", JSONObject().put("time", "2026-10-13T12:00")
                .put("weather_code", providerCode).put("cloud_cover", 100))
                .put("hourly", JSONObject().put("time", JSONArray(listOf("2026-10-13T12:00")))
                    .put("weather_code", JSONArray(listOf(providerCode)))
                    .put("cloud_cover", JSONArray(listOf(100)))
                    .put("precipitation", JSONArray(listOf(0.2))))
                .put("daily", JSONObject().put("time", JSONArray(listOf("2026-10-13"))))
            val hourly = JSONObject().put("time", JSONArray(listOf("2026-10-13T12:00")))
            repeat(3) { index ->
                for ((field, value) in mapOf("temperature_2m" to 20, "weather_code" to 3,
                    "cloud_cover" to 100, "precipitation" to 0.2)) {
                    hourly.put("${field}_m$index", JSONArray(listOf(value)))
                }
            }
            val result = JSONObject(blendModelForecast(base.toString(), JSONObject().put("hourly", hourly).toString()).json)
            assertEquals(providerCode, result.getJSONObject("current").getInt("weather_code"))
            assertEquals(providerCode, result.getJSONObject("hourly").getJSONArray("weather_code").getInt(0))
            assertEquals(0.2, result.getJSONObject("hourly").getJSONArray("precipitation").getDouble(0), 0.0)
        }
    }

    @Test
    fun opposingWindDirectionsKeepSpeedAndMissingDirectionsKeepProviderFallback() {
        val base = """{"current":{"time":"2026-10-13T12:00","wind_speed_10m":5,"wind_direction_10m":180},"hourly":{"time":["2026-10-13T12:00"],"wind_speed_10m":[5],"wind_direction_10m":[180]},"daily":{"time":["2026-10-13"],"wind_speed_10m_max":[5]}}"""
        for (missingDirections in listOf(false, true)) {
            val hourly = JSONObject().put("time", JSONArray(listOf("2026-10-13T12:00")))
            listOf(0, 90, 180, 270).forEachIndexed { index, direction ->
                hourly.put("temperature_2m_m$index", JSONArray(listOf(20)))
                    .put("wind_speed_10m_m$index", JSONArray(listOf(10)))
                    .put("wind_direction_10m_m$index", JSONArray(listOf(
                        if (missingDirections && index >= 2) JSONObject.NULL else direction,
                    )))
            }
            val result = JSONObject(blendModelForecast(
                base, JSONObject().put("hourly", hourly).toString(),
            ).json)
            val expected = if (missingDirections) 5.0 else 10.0
            assertEquals(expected, result.getJSONObject("hourly").getJSONArray("wind_speed_10m").getDouble(0), 1e-9)
            assertEquals(expected, result.getJSONObject("current").getDouble("wind_speed_10m"), 1e-9)
            assertEquals(expected, result.getJSONObject("daily").getJSONArray("wind_speed_10m_max").getDouble(0), 1e-9)
        }
    }
}
