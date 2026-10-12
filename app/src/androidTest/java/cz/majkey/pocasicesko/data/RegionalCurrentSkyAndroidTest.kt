package cz.majkey.pocasicesko.data

import org.junit.Assert.assertEquals
import org.junit.Test

class RegionalCurrentSkyAndroidTest {
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
