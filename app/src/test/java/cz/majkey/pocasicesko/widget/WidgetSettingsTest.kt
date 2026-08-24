package cz.majkey.pocasicesko.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetSettingsTest {
    @Test
    fun classifiesEverySupportedWidgetSize() {
        assertEquals(WidgetSize.COMPACT, widgetSize(110, 40))
        assertEquals(WidgetSize.STANDARD, widgetSize(180, 80))
        assertEquals(WidgetSize.TALL, widgetSize(180, 120))
        assertEquals(WidgetSize.WIDE, widgetSize(320, 100))
    }

    @Test
    fun respectsBoundariesAndCurrentHostSize() {
        assertEquals(WidgetSize.COMPACT, widgetSize(179, 119))
        assertEquals(WidgetSize.STANDARD, widgetSize(319, 119))
        assertEquals(WidgetSize.TALL, widgetSize(179, 120))
        assertEquals(WidgetSize.WIDE, widgetSize(320, 120))
        assertEquals(WidgetSize.WIDE, widgetSize(467, 104))
    }

    @Test
    fun defaultsProvideTheFullSafeTemplate() {
        val settings = WidgetSettings()

        assertEquals(WidgetBackgroundMode.AUTOMATIC, settings.backgroundMode)
        assertEquals("#0C1922", settings.backgroundStart)
        assertEquals("#28758D", settings.backgroundEnd)
        assertEquals("#FFFFFFFF", settings.primaryColor)
        assertEquals("#CCFFFFFF", settings.secondaryColor)
        assertEquals("#FF66C9DF", settings.accentColor)
        assertEquals(100, settings.opacity)
        assertEquals(100, settings.textScale)
        assertEquals(WidgetAlignment.LEFT, settings.alignment)
        assertTrue(settings.showClock)
        assertTrue(settings.showDate)
        assertTrue(settings.showLocation)
        assertTrue(settings.showTemperature)
        assertTrue(settings.showIcon)
        assertTrue(settings.showCondition)
        assertTrue(settings.showRange)
        assertTrue(settings.showHourly)
        assertFalse(settings.showPrecipitation)
        assertFalse(settings.showWind)
        assertFalse(settings.showHumidity)
        assertFalse(settings.showUpdatedAt)
    }

    @Test
    fun normalizesOnlyValidHexColorsAndBoundedFields() {
        val normalized = WidgetSettings(
            backgroundStart = " #112233 ",
            backgroundEnd = "not-a-color",
            primaryColor = "#7F010203",
            opacity = -5,
            textScale = 999,
            customLabel = "  ${"x".repeat(48)}  ",
        ).normalized()

        assertTrue(isWidgetColor("#112233"))
        assertTrue(isWidgetColor("#7F010203"))
        assertFalse(isWidgetColor("#12345"))
        assertFalse(isWidgetColor("#GG1122"))
        assertEquals("#112233", normalized.backgroundStart)
        assertEquals("#28758D", normalized.backgroundEnd)
        assertEquals("#7F010203", normalized.primaryColor)
        assertEquals(0, normalized.opacity)
        assertEquals(140, normalized.textScale)
        assertEquals(40, normalized.customLabel.length)
    }

    @Test
    fun resolvesStoredModesAndKeepsWidgetKeysIsolated() {
        assertEquals(WidgetBackgroundMode.DARK, widgetBackgroundMode(null, "DARK"))
        assertEquals(WidgetBackgroundMode.AUTOMATIC, widgetBackgroundMode("not-a-mode", "DARK"))
        assertEquals(WidgetBackgroundMode.CUSTOM_IMAGE, widgetBackgroundMode("CUSTOM_IMAGE", null))
        assertEquals(
            "widget_settings_4_background_mode",
            widgetPreferenceKey(4, "background_mode"),
        )
        assertFalse(widgetPreferenceKey(4, "image_uri") == widgetPreferenceKey(40, "image_uri"))
    }

    @Test
    fun boundsBackgroundBeforeRemoteViewsReceivesIt() {
        assertEquals(WidgetBitmapSize(512, 256), backgroundBitmapSize(2_048, 1_024))
        assertEquals(WidgetBitmapSize(26, 256), backgroundBitmapSize(100, 1_000))
        assertEquals(WidgetBitmapSize(200, 100), backgroundBitmapSize(200, 100))
    }

    @Test
    fun alignsContentWithOnlyApi29SafeSpacerVisibility() {
        assertEquals(WidgetAlignmentSpacers(showLeft = false, showRight = true), widgetAlignmentSpacers(WidgetAlignment.LEFT))
        assertEquals(WidgetAlignmentSpacers(showLeft = true, showRight = true), widgetAlignmentSpacers(WidgetAlignment.CENTER))
        assertEquals(WidgetAlignmentSpacers(showLeft = true, showRight = false), widgetAlignmentSpacers(WidgetAlignment.RIGHT))
    }
}
