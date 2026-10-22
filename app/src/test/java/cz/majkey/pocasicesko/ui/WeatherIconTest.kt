package cz.majkey.pocasicesko.ui

import androidx.compose.ui.graphics.Color
import cz.majkey.pocasicesko.data.WeatherKind
import cz.majkey.pocasicesko.data.WeatherConditionKey
import cz.majkey.pocasicesko.data.conditionFor
import cz.majkey.pocasicesko.R
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherIconTest {
    @Test
    fun confidenceBarsDoNotInventANumericAccuracyPercentage() {
        val levels = cz.majkey.pocasicesko.data.ConfidenceLevel.entries
        assertEquals(listOf(3, 2, 1, 0), levels.map(::confidenceBarCount))
    }
    @Test
    fun drizzleRainAndSnowHaveDistinctShapesAndIntensityInAppAndWidget() {
        assertEquals(R.drawable.ic_weather_drizzle, precipitationIconResource(WeatherConditionKey.DRIZZLE))
        assertEquals(R.drawable.ic_weather_rain, precipitationIconResource(WeatherConditionKey.RAIN))
        assertEquals(R.drawable.ic_weather_rain, precipitationIconResource(WeatherConditionKey.SHOWERS))
        assertEquals(R.drawable.ic_weather_drizzle,
            cz.majkey.pocasicesko.widget.widgetIconFor(WeatherKind.RAIN, true, WeatherConditionKey.DRIZZLE))
        val resources = listOf(51, 61, 63, 65, 71, 73, 75).map { code ->
            val condition = conditionFor(code)
            val resource = if (condition.kind == WeatherKind.SNOW) snowIconResource(code)
                else precipitationIconResource(condition.key, code)
            assertEquals(resource,
                cz.majkey.pocasicesko.widget.widgetIconFor(condition.kind, true, condition.key, code))
            resource
        }
        assertEquals(resources.size, resources.distinct().size)
        assertEquals(snowIconResource(71), snowIconResource(85))
        assertEquals(snowIconResource(75), snowIconResource(86))
        assertNotEquals(precipitationIconResource(WeatherConditionKey.DRIZZLE, 51), snowIconResource(77))
        assertEquals(R.drawable.ic_weather_rain_snow, snowIconResource(68))
        assertEquals(snowIconResource(68), snowIconResource(69))
        for (code in listOf(83, 84)) {
            assertEquals(WeatherKind.SNOW, conditionFor(code).kind)
            assertEquals(snowIconResource(68), snowIconResource(code))
            assertEquals(snowIconResource(code), cz.majkey.pocasicesko.widget.widgetIconFor(
                conditionFor(code).kind, true, conditionFor(code).key, code))
        }
        assertNotEquals(snowIconResource(68), snowIconResource(71))
        assertEquals(snowIconResource(68), cz.majkey.pocasicesko.widget.widgetIconFor(
            conditionFor(68).kind, true, conditionFor(68).key, 68))
    }
    @Test
    fun mainlyClearKeepsCloudSmallAndBlue() {
        assertEquals(0.50f, compositeCloudFraction(WeatherKind.MAINLY_CLEAR), 0.001f)
        assertEquals(Color(0xFF9FB7FF), compositeCloudTint(WeatherKind.MAINLY_CLEAR, Color.Yellow))
    }

    @Test
    fun partlyCloudyKeepsLargerRequestedCloudTint() {
        val requested = Color(0xFF9ED9EA)

        assertEquals(0.64f, compositeCloudFraction(WeatherKind.PARTLY_CLOUDY), 0.001f)
        assertEquals(requested, compositeCloudTint(WeatherKind.PARTLY_CLOUDY, requested))
    }

    @Test
    fun mainlyClearUsesSunOrMoonWithSmallCloud() {
        val source = File(
            System.getProperty("user.dir"),
            "src/main/java/cz/majkey/pocasicesko/ui/WeatherIcons.kt",
        ).readText()

        assertTrue(source.contains("kind == WeatherKind.MAINLY_CLEAR"))
        assertTrue(source.contains("Icons.Outlined.WbSunny"))
        assertTrue(source.contains("Icons.Outlined.DarkMode"))
        assertTrue(source.contains("Icons.Outlined.Cloud"))
    }

    @Test
    fun partlyCloudyUsesSunOrMoonBehindACloud() {
        val source = File(
            System.getProperty("user.dir"),
            "src/main/java/cz/majkey/pocasicesko/ui/WeatherIcons.kt",
        ).readText()

        assertTrue(source.contains("kind == WeatherKind.PARTLY_CLOUDY"))
        assertTrue(source.contains("sunOrMoonTint"))
        assertTrue(source.contains("cloudFraction"))
        assertFalse(source.contains("Icons.Rounded.FilterDrama"))
    }
}
