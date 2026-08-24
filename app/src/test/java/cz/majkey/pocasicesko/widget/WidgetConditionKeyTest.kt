package cz.majkey.pocasicesko.widget

import cz.majkey.pocasicesko.data.WeatherConditionKey
import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetConditionKeyTest {
    @Test
    fun `uses stored condition key and falls back to unknown`() {
        assertEquals(WeatherConditionKey.RAIN, widgetConditionKey("RAIN"))
        assertEquals(WeatherConditionKey.UNKNOWN, widgetConditionKey("old translated value"))
        assertEquals(WeatherConditionKey.UNKNOWN, widgetConditionKey(null))
    }
}
