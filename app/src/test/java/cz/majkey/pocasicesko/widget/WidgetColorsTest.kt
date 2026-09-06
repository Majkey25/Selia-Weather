package cz.majkey.pocasicesko.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import androidx.compose.runtime.saveable.SaverScope

class WidgetColorsTest {
    @Test
    fun acceptsPastedHexWithOrWithoutHashAndPreservesAlpha() {
        assertTrue(isWidgetColor("abcdef"))
        assertEquals("#ABCDEF", normalizedWidgetColor(" abcdef ", "#000000"))
        assertEquals("#80112233", normalizedWidgetColor("80112233", "#000000"))
        assertFalse(isWidgetColor("#12345"))
        assertFalse(isWidgetColor("white"))
        assertFalse(isWidgetColor("1122334455"))
        assertFalse(isWidgetColor("１２３４５６"))
    }

    @Test
    fun lightAndWhiteSolidBackgroundsHaveReadableTextWithoutChangingSavedColors() {
        for (settings in listOf(WidgetSettings(backgroundMode = WidgetBackgroundMode.LIGHT),
            WidgetSettings(backgroundMode = WidgetBackgroundMode.SOLID, backgroundStart = "#FFFFFF"))) {
            val rendered = settings.renderedTextColors()
            assertEquals("#FF000000", rendered.primaryColor)
            for (color in listOf(rendered.primaryColor, rendered.secondaryColor)) {
                assertTrue(widgetContrastRatio(requireNotNull(widgetArgbOrNull(color)), -1) >= 4.5)
            }
            assertEquals("#FFFFFFFF", settings.primaryColor)
            assertEquals(settings.accentColor, rendered.accentColor)
        }
        val gray = WidgetSettings(backgroundMode = WidgetBackgroundMode.SOLID, backgroundStart = "#777777").renderedTextColors()
        assertTrue(widgetContrastRatio(requireNotNull(widgetArgbOrNull(gray.secondaryColor)), 0xFF777777.toInt()) >= 4.5)
        assertEquals("#FFFFFFFF", WidgetSettings(backgroundMode = WidgetBackgroundMode.DARK).renderedTextColors().primaryColor)
    }

    @Test
    fun manualColorsAndUnknownImageBackgroundsRemainUnderUserControl() {
        val manual = WidgetSettings(backgroundMode = WidgetBackgroundMode.LIGHT, primaryColor = "#FF123456", automaticTextColors = false)
        assertEquals(manual, manual.renderedTextColors())
        assertEquals(manual.copy(backgroundMode = WidgetBackgroundMode.CUSTOM_IMAGE, automaticTextColors = true),
            manual.copy(backgroundMode = WidgetBackgroundMode.CUSTOM_IMAGE, automaticTextColors = true).renderedTextColors())
        assertTrue(defaultAutomaticWidgetTextColors("#FFFFFFFF", "#D9FFFFFF"))
        assertFalse(defaultAutomaticWidgetTextColors("#FF123456", "#CCFFFFFF"))
        assertFalse(defaultAutomaticWidgetTextColors("#FFFFFFFF", "#00FFFFFF"))
        val transparent = WidgetSettings(backgroundMode = WidgetBackgroundMode.SOLID, backgroundStart = "#00FFFFFF")
        assertFalse(transparent.automaticTextColorsAvailable())
        assertEquals(transparent, transparent.renderedTextColors())
        assertFalse(transparent.copy(backgroundMode = WidgetBackgroundMode.GRADIENT).automaticTextColorsAvailable())
        assertFalse(WidgetSettings(backgroundMode = WidgetBackgroundMode.LIGHT, opacity = 50).automaticTextColorsAvailable())
    }

    @Test
    fun channelPickerPreservesOtherChannelsAndBoundsAlpha() {
        assertEquals("#801122FF", widgetColorChannel("#80112233", 0, 255))
        assertEquals("#00112233", widgetColorChannel("#80112233", 24, -1))
        assertEquals("#FFFF2233", widgetColorChannel("112233", 16, 500))
        assertEquals(21.0, widgetContrastRatio(0xFF000000.toInt(), -1), 0.0001)
        assertEquals(1.0, widgetContrastRatio(-1, -1), 0.0001)
    }

    @Test
    fun editorPreservesExplicitManualModeAndMigratesLegacyCustomColors() {
        val scope = object : SaverScope { override fun canBeSaved(value: Any): Boolean = true }
        val manual = WidgetSettings(automaticTextColors = false)
        val saved = requireNotNull(with(WidgetSettingsSaver) { scope.save(manual) })
        assertEquals(manual, WidgetSettingsSaver.restore(saved))
        val draft = manual.copy(primaryColor = "#12")
        assertEquals(draft, WidgetSettingsSaver.restore(requireNotNull(with(WidgetSettingsSaver) { scope.save(draft) })))
        val custom = manual.copy(primaryColor = "#FF102030")
        val old = requireNotNull(with(WidgetSettingsSaver) { scope.save(custom) }) as List<*>
        assertFalse(requireNotNull(WidgetSettingsSaver.restore(old.take(31))).automaticTextColors)
        val defaults = requireNotNull(with(WidgetSettingsSaver) { scope.save(WidgetSettings()) }) as List<*>
        assertTrue(requireNotNull(WidgetSettingsSaver.restore(defaults.take(31))).automaticTextColors)
    }
}
