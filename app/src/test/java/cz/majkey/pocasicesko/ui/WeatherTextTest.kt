package cz.majkey.pocasicesko.ui

import cz.majkey.pocasicesko.R
import cz.majkey.pocasicesko.data.WeatherCondition
import cz.majkey.pocasicesko.data.WeatherConditionKey
import cz.majkey.pocasicesko.data.WeatherKind
import cz.majkey.pocasicesko.data.conditionFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class WeatherTextTest {
    @Test
    fun snowIntensityAndSnowGrainsHaveDistinctLabels() {
        val codes = listOf(71, 73, 75, 77, 85, 86)
        assertEquals(codes.size, codes.map { conditionFor(it).labelResource() }.distinct().size)
        codes.forEach { code ->
            assertEquals(conditionFor(code).labelResource(), conditionFor(code, false).labelResource())
            assertNotEquals(R.string.condition_snow, conditionFor(code).labelResource())
        }
    }

    @Test
    fun sourceCodesKeepDrizzleRainShowersAndFreezingIntensity() {
        val labels = mapOf(
            51 to R.string.condition_light_drizzle,
            53 to R.string.condition_moderate_drizzle,
            55 to R.string.condition_dense_drizzle,
            56 to R.string.condition_light_freezing_drizzle,
            57 to R.string.condition_dense_freezing_drizzle,
            58 to R.string.condition_light_drizzle_and_rain,
            59 to R.string.condition_drizzle_and_rain,
            61 to R.string.condition_light_rain,
            63 to R.string.condition_moderate_rain,
            65 to R.string.condition_heavy_rain,
            66 to R.string.condition_light_freezing_rain,
            67 to R.string.condition_heavy_freezing_rain,
            80 to R.string.condition_light_showers,
            81 to R.string.condition_moderate_showers,
            82 to R.string.condition_violent_showers,
        )
        labels.forEach { (code, label) ->
            assertEquals("code=$code", label, conditionFor(code).labelResource())
            assertEquals("night code=$code", label, conditionFor(code, false).labelResource())
        }
    }

    @Test
    fun genericConditionsKeepTheirExistingLabels() {
        listOf(
            WeatherConditionKey.DRIZZLE to R.string.condition_drizzle,
            WeatherConditionKey.RAIN to R.string.condition_rain,
            WeatherConditionKey.SHOWERS to R.string.condition_showers,
        ).forEach { (key, label) ->
            assertEquals(label, WeatherCondition(key, WeatherKind.RAIN).labelResource())
        }
        assertEquals(R.string.condition_unknown, conditionFor(-1).labelResource())
        assertEquals(R.string.condition_clear_night, conditionFor(0, false).labelResource())
    }
}
