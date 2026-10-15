package cz.majkey.pocasicesko.data

import org.junit.Assert.assertEquals
import org.junit.Test

class RegionalCurrentSkyAndroidTest {
    @Test
    fun invalidCurrentCloudUsesTheExactWindowButNeverHidesReportedRain() {
        val location = CzechLocation("Březnice", "Zlín", 49.18643, 17.66277, "CZ")
        val fallback = ModelBlendResult(
            """{"current":{"time":"2026-10-14T19:15","weather_code":0,"cloud_cover":0,"precipitation":0}}""",
            ForecastCalculationMode.BEST_MATCH, emptyList(), ForecastFallbackReason.PROVIDER_UNAVAILABLE,
        )
        for (cloud in listOf("null", "101")) {
            val regional = """{"current":{"time":"2026-10-14T19:15","weather_code":3,"cloud_cover":$cloud},"minutely_15":{"time":["2026-10-14T19:15"],"weather_code":[3],"cloud_cover":[100]}}"""
            val result = applyRegionalCurrentSky(fallback, location, regional)
            assertEquals(100, org.json.JSONObject(result.json).getJSONObject("current").getInt("cloud_cover"))
            assertEquals("chmi_aladin_seamless", result.currentSkyModelId)
            assertEquals(fallback, applyRegionalCurrentSky(fallback, location,
                regional.replaceFirst("\"weather_code\":3", "\"weather_code\":61")))
        }
        // Android rejects non-finite object values before any fallback sample can be read.
        val unparseable = """{"current":{"time":"2026-10-14T19:15","weather_code":3,"cloud_cover":1e999},"minutely_15":{"time":["2026-10-14T19:15"],"weather_code":[3],"cloud_cover":[100]}}"""
        assertEquals(fallback, applyRegionalCurrentSky(fallback, location, unparseable))
    }

    @Test
    fun optionalRegionalNonFiniteArrayValuesNeverAbortTheForecast() {
        val location = CzechLocation("Praha", REGION_PRAGUE, 50.0755, 14.4378, "CZ")
        val fallback = ModelBlendResult(
            """{"current":{"time":"2026-10-12T19:15","weather_code":0,"cloud_cover":0,"precipitation":0}}""",
            ForecastCalculationMode.BEST_MATCH, emptyList(), ForecastFallbackReason.PROVIDER_UNAVAILABLE,
        )
        for ((code, cloud) in listOf("3" to "1e999", "1e999" to "100")) {
            val regional = """{"minutely_15":{"time":["2026-10-12T19:15"],"weather_code":[$code],"cloud_cover":[$cloud]}}"""
            assertEquals(fallback, applyRegionalCurrentSky(fallback, location, regional))
        }
    }
}
