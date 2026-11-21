package cz.majkey.pocasicesko.ui

import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import cz.majkey.pocasicesko.data.WeatherKind
import cz.majkey.pocasicesko.data.WeatherConditionKey
import cz.majkey.pocasicesko.data.conditionFor
import cz.majkey.pocasicesko.R
import cz.majkey.pocasicesko.widget.widgetContrastRatio
import org.junit.Assert.assertEquals
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
    fun mainlyClearKeepsASmallerCloudThanPartlyCloudy() {
        assertEquals(0.50f, compositeCloudFraction(WeatherKind.MAINLY_CLEAR), 0.001f)
        assertEquals(0.64f, compositeCloudFraction(WeatherKind.PARTLY_CLOUDY), 0.001f)
    }

    @Test
    fun symbolsKeepTheirColorsAndContrastAcrossAppThemes() {
        for (theme in AppAppearance.entries) for (dark in listOf(true, false)) {
            for (kind in WeatherKind.entries) for (isDay in listOf(true, false)) {
                if (kind == WeatherKind.CLEAR && isDay) continue
                val palette = appearancePalette(theme, kind, isDay, dark)
                val colors = appearanceColors(palette, theme, dark)
                val appearance = RenderedAppearance(palette, colors, colors.onBackground.luminance() > 0.5f)
                val tint = weatherIconTint(kind, appearance)
                val label = "$theme dark=$dark $kind day=$isDay"
                val backgrounds = gradientContrastSamples(palette.background) + listOf(colors.surface, colors.surfaceVariant)
                assertTrue("$label contrast", backgrounds.all { widgetContrastRatio(tint.toArgb(), it.toArgb()) >= 3.0 })
                assertEquals("$label opacity", 1f, tint.alpha, 0f)
                when (kind) {
                    WeatherKind.CLEAR ->
                        assertTrue("$label lavender moon", tint.blue > tint.red && tint.blue > tint.green)
                    WeatherKind.MAINLY_CLEAR, WeatherKind.PARTLY_CLOUDY, WeatherKind.CLOUDY, WeatherKind.UNKNOWN -> {
                        assertTrue("$label blue-grey cloud", tint.blue > tint.green && tint.green > tint.red)
                        assertEquals("$label shared cloud", weatherIconTint(WeatherKind.CLOUDY, appearance), tint)
                    }
                    WeatherKind.RAIN, WeatherKind.SNOW ->
                        assertTrue("$label blue precipitation", tint.blue > tint.red && tint.blue > tint.green)
                    WeatherKind.STORM -> assertTrue("$label purple storm", tint.blue > tint.green && tint.red > tint.green)
                    WeatherKind.FOG -> assertTrue("$label teal fog", tint.green > tint.red && tint.blue > tint.red)
                }
            }
        }
    }

    @Test
    fun cloudStaysBlueGreyWithCustomGreenText() {
        val custom = CustomAppearance(
            backgroundStart = "#858585",
            backgroundEnd = "#858585",
            surface = "#858585",
            primaryText = "#001F00",
            secondaryText = "#001F00",
            automaticText = false,
        )
        assertTrue(customAppearanceReadable(custom))
        val kind = WeatherKind.CLOUDY
        val theme = AppAppearance.CUSTOM
        val palette = appearancePalette(theme, kind, true, false, custom)
        val appearance = RenderedAppearance(palette, appearanceColors(palette, theme, false, custom), false)
        val tint = weatherIconTint(kind, appearance)
        assertTrue("Custom text must not make the cloud green", tint.blue >= tint.green && tint.green >= tint.red)
        assertTrue(widgetContrastRatio(tint.toArgb(), appearance.colors.surface.toArgb()) >= 3.0)
    }
}
