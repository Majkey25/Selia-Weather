package cz.majkey.pocasicesko.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import cz.majkey.pocasicesko.data.WeatherConditionKey
import cz.majkey.pocasicesko.data.WeatherKind
import cz.majkey.pocasicesko.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetConditionKeyTest {
    @Test
    fun obscuredSkyKeepsItsLabelAndNeverUsesASunIcon() {
        val condition = cz.majkey.pocasicesko.data.conditionFor(cz.majkey.pocasicesko.data.SKY_OBSCURED_WEATHER_CODE)
        assertEquals(WeatherKind.UNKNOWN, condition.kind)
        assertEquals(condition.key, widgetConditionKey(condition.key.name))
        assertEquals(R.string.condition_sky_obscured,
            widgetConditionLabelResource(condition.key.name, condition.kind, condition.weatherCode))
        assertEquals(R.drawable.ic_weather_cloud,
            widgetIconFor(condition.kind, true, condition.key, condition.weatherCode))
    }

    @Test
    fun preciseConditionLabelsUseCachedWeatherCode() {
        assertEquals(R.string.condition_light_rain, widgetConditionLabelResource("RAIN", WeatherKind.RAIN, 61))
        assertEquals(R.string.condition_heavy_rain, widgetConditionLabelResource("RAIN", WeatherKind.RAIN, 65))
        assertEquals(R.string.condition_light_freezing_drizzle,
            widgetConditionLabelResource("DRIZZLE", WeatherKind.RAIN, 56))
    }

    @Test
    fun oldWidgetCacheKeepsGenericLabelsAndPlaceholder() {
        assertEquals(R.string.condition_rain, widgetConditionLabelResource("RAIN", WeatherKind.RAIN))
        assertEquals(R.string.condition_drizzle, widgetConditionLabelResource("DRIZZLE", WeatherKind.RAIN))
        assertEquals(R.string.condition_showers, widgetConditionLabelResource("SHOWERS", WeatherKind.RAIN))
        assertEquals(R.string.condition_rain, widgetConditionLabelResource("RAIN", WeatherKind.RAIN, -1))
        assertEquals(R.string.condition_unknown, widgetConditionLabelResource("old translated value", WeatherKind.RAIN))
        assertEquals(R.string.widget_placeholder_condition, widgetConditionLabelResource(null, WeatherKind.UNKNOWN))
    }

    @Test
    fun `recognizes locale broadcasts that require widget rerendering`() {
        assertTrue(isWidgetDisplayChange(Intent.ACTION_APPLICATION_LOCALE_CHANGED))
        assertTrue(isWidgetDisplayChange(Intent.ACTION_LOCALE_CHANGED))
        assertFalse(isWidgetDisplayChange(AppWidgetManager.ACTION_APPWIDGET_UPDATE))
        assertFalse(isWidgetDisplayChange(Intent.ACTION_TIME_TICK))
        assertFalse(isWidgetDisplayChange(Intent.ACTION_DATE_CHANGED))
        assertFalse(isWidgetDisplayChange(null))
    }

    @Test
    fun `recognizes manual time changes that require widget rerendering`() {
        assertTrue(isWidgetDisplayChange(Intent.ACTION_TIME_CHANGED))
    }

    @Test
    fun `recognizes time zone changes that require widget rerendering`() {
        assertTrue(isWidgetDisplayChange(Intent.ACTION_TIMEZONE_CHANGED))
    }

    @Test
    fun `uses stored condition key and falls back to unknown`() {
        assertEquals(WeatherConditionKey.RAIN, widgetConditionKey("RAIN"))
        assertEquals(WeatherConditionKey.UNKNOWN, widgetConditionKey("old translated value"))
        assertEquals(WeatherConditionKey.UNKNOWN, widgetConditionKey(null))
    }
}
