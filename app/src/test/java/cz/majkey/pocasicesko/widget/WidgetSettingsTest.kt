package cz.majkey.pocasicesko.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset
import java.util.Locale

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

    @Test
    fun migratesLegacyDetailsAndLightForegroundsOnlyWhenNewValuesAreAbsent() {
        assertTrue(migratedWidgetVisibility(newValue = null, legacyDetails = true, defaultValue = false))
        assertFalse(migratedWidgetVisibility(newValue = false, legacyDetails = true, defaultValue = true))
        assertEquals(
            "#FF173042",
            migratedWidgetColor(WidgetBackgroundMode.LIGHT, hasStoredColor = false, storedColor = null, fallback = DEFAULT_PRIMARY_COLOR),
        )
        assertEquals(
            "#B3173042",
            migratedWidgetColor(WidgetBackgroundMode.LIGHT, hasStoredColor = false, storedColor = null, fallback = DEFAULT_SECONDARY_COLOR),
        )
        assertEquals(
            DEFAULT_PRIMARY_COLOR,
            migratedWidgetColor(WidgetBackgroundMode.DARK, hasStoredColor = false, storedColor = null, fallback = DEFAULT_PRIMARY_COLOR),
        )
        assertEquals(
            "#FFABCDEF",
            migratedWidgetColor(WidgetBackgroundMode.LIGHT, hasStoredColor = true, storedColor = "#FFABCDEF", fallback = DEFAULT_PRIMARY_COLOR),
        )
    }

    @Test
    fun derivesConfiguredBackgroundAlphaWithoutAndroidGraphics() {
        assertEquals(0, widgetOpacityAlpha(255, 0))
        assertEquals(153, widgetOpacityAlpha(255, 60))
        assertEquals(122, widgetOpacityAlpha(204, 60))
        assertEquals(255, widgetOpacityAlpha(999, 999))
    }

    @Test
    fun formatsWidgetUpdateTimeWithTheSelectedLocale() {
        val epoch = 1_725_000_000_000L
        val english = widgetUpdatedAt(epoch, ZoneOffset.UTC, Locale.US)
        val french = widgetUpdatedAt(epoch, ZoneOffset.UTC, Locale.FRANCE)

        assertNotEquals(english, french)
    }

    @Test
    fun sharesPreviewVisibilityAndBitmapOpacityWithTheProvider() {
        val settings = WidgetSettings(
            backgroundMode = WidgetBackgroundMode.GRADIENT,
            opacity = 60,
            showLocation = false,
            showCondition = false,
            showRange = true,
            showHourly = true,
        )

        val wide = widgetContentVisibility(settings, WidgetSize.WIDE, true, true, true)

        assertEquals(153, widgetBackgroundAlpha(settings, 255))
        assertFalse(wide.showLocation)
        assertFalse(wide.showCondition)
        assertTrue(wide.showRange)
        assertTrue(wide.showHourly)
        assertFalse(wide.showMetrics)
    }

    @Test
    fun tracksOnlyWidgetImageReferences() {
        val image = "content://example/image"

        assertTrue(widgetImageUriReferenced(mapOf("widget_settings_7_image_uri" to image), image))
        assertFalse(widgetImageUriReferenced(mapOf("widget_settings_7_city" to image), image))
        assertFalse(widgetImageUriReferenced(mapOf("widget_settings_7_image_uri" to image), "content://other"))
    }

    @Test
    fun boundsInvalidColorInputAndRequiresGrantOnlyForANewImage() {
        val original = "content://example/original"

        assertEquals("#12345678", widgetColorInput("#123456789"))
        assertFalse(requiresWidgetImageGrant(original, original))
        assertFalse(requiresWidgetImageGrant(original, ""))
        assertTrue(requiresWidgetImageGrant(original, "content://example/new"))
    }

    @Test
    fun coalescesQueuedUpdatesButNeverDropsQueuedApply() {
        assertEquals(
            WidgetWorkAdmission.ENQUEUE,
            widgetWorkAdmission(null, WidgetWorkKind.UPDATE),
        )
        assertEquals(
            WidgetWorkAdmission.REPLACE_PENDING,
            widgetWorkAdmission(WidgetWorkKind.UPDATE, WidgetWorkKind.UPDATE),
        )
        assertEquals(
            WidgetWorkAdmission.REPLACE_PENDING,
            widgetWorkAdmission(WidgetWorkKind.UPDATE, WidgetWorkKind.APPLY),
        )
        assertEquals(
            WidgetWorkAdmission.DROP_INCOMING,
            widgetWorkAdmission(WidgetWorkKind.APPLY, WidgetWorkKind.UPDATE),
        )
        assertEquals(
            WidgetWorkAdmission.REJECT_INCOMING,
            widgetWorkAdmission(WidgetWorkKind.APPLY, WidgetWorkKind.APPLY),
        )
    }

    @Test
    fun defersImageGrantUntilApplyAndKeepsSharedUrisReferenced() {
        val original = "content://example/original"
        val replacement = "content://example/replacement"

        assertFalse(requiresWidgetImageGrant(original, original))
        assertTrue(requiresWidgetImageGrant(original, replacement))
        assertTrue(
            widgetImageUriReferenced(
                mapOf("widget_settings_8_image_uri" to original),
                original,
            ),
        )
    }
}
