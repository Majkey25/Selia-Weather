package cz.majkey.pocasicesko.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import cz.majkey.pocasicesko.data.WeatherConditionKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetConditionKeyTest {
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
