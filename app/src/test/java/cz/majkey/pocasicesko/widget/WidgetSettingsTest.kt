package cz.majkey.pocasicesko.widget

import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetSettingsTest {
    @Test
    fun classifiesEverySupportedWidgetSize() {
        assertEquals(WidgetSize.COMPACT, widgetSize(110, 40))
        assertEquals(WidgetSize.STANDARD, widgetSize(180, 80))
        assertEquals(WidgetSize.TALL, widgetSize(180, 120))
        assertEquals(WidgetSize.WIDE, widgetSize(320, 100))
    }
}
