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
        assertTrue(isWidgetLocaleChange(Intent.ACTION_APPLICATION_LOCALE_CHANGED))
        assertTrue(isWidgetLocaleChange(Intent.ACTION_LOCALE_CHANGED))
        assertFalse(isWidgetLocaleChange(AppWidgetManager.ACTION_APPWIDGET_UPDATE))
        assertFalse(isWidgetLocaleChange(null))
    }

    @Test
    fun `uses stored condition key and falls back to unknown`() {
        assertEquals(WeatherConditionKey.RAIN, widgetConditionKey("RAIN"))
        assertEquals(WeatherConditionKey.UNKNOWN, widgetConditionKey("old translated value"))
        assertEquals(WeatherConditionKey.UNKNOWN, widgetConditionKey(null))
    }
}
