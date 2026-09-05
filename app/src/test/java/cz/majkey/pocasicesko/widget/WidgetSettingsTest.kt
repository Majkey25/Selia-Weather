package cz.majkey.pocasicesko.widget

import cz.majkey.pocasicesko.data.WeatherKind
import cz.majkey.pocasicesko.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.util.Locale
import androidx.compose.runtime.saveable.SaverScope

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
        assertEquals(WidgetFontStyle.SYSTEM, settings.fontStyle)
        assertEquals(WidgetAlignment.LEFT, settings.alignment)
        assertEquals(WidgetCorners.ROUND, settings.corners)
        assertEquals(12, settings.contentPaddingDp)
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
        assertFalse(settings.showDewPoint)
        assertFalse(settings.showPressure)
        assertFalse(settings.showVisibility)
        assertFalse(settings.showWindGusts)
        assertFalse(settings.showMoon)
        assertFalse(settings.showUpdatedAt)
    }

    @Test
    fun presetsApplyDistinctSafeStylesAndPreserveUserContent() {
        val current = WidgetSettings(
            customLabel = "My field",
            imageUri = "content://example/background",
        )
        val minimal = widgetPresetSettings(WidgetPreset.MINIMAL, current)
        val material = widgetPresetSettings(WidgetPreset.MATERIAL, current)
        val pixel = widgetPresetSettings(WidgetPreset.PIXEL, current)
        val cupertino = widgetPresetSettings(WidgetPreset.CUPERTINO, current)

        assertEquals(WidgetBackgroundMode.TRANSPARENT, minimal.backgroundMode)
        assertEquals(WidgetFontStyle.LIGHT, minimal.fontStyle)
        assertFalse(minimal.showIcon)
        assertFalse(minimal.showHourly)
        assertEquals(WidgetFontStyle.MATERIAL, material.fontStyle)
        assertEquals(WidgetBackgroundMode.AUTOMATIC, material.backgroundMode)
        assertEquals(WidgetFontStyle.ROUNDED, pixel.fontStyle)
        assertEquals(WidgetBackgroundMode.GRADIENT, pixel.backgroundMode)
        assertEquals(WidgetFontStyle.LIGHT, cupertino.fontStyle)
        assertEquals(WidgetAlignment.CENTER, cupertino.alignment)
        WidgetPreset.entries.forEach { preset ->
            val settings = widgetPresetSettings(preset, current)
            assertEquals("My field", settings.customLabel)
            assertEquals("content://example/background", settings.imageUri)
        }
    }

    @Test
    fun unknownStoredFontFallsBackToSystem() {
        assertEquals(WidgetFontStyle.ROUNDED, widgetFontStyle("ROUNDED"))
        assertEquals(WidgetFontStyle.SYSTEM, widgetFontStyle("future-font"))
        assertEquals(WidgetFontStyle.SYSTEM, widgetFontStyle(null))
    }

    @Test
    fun everyWidgetFontAndAlignmentUsesADistinctXmlLayout() {
        val layouts = WidgetFontStyle.entries.flatMap { font ->
            WidgetAlignment.entries.map { alignment -> widgetFontLayout(font, alignment) }
        }

        assertEquals(WidgetFontStyle.entries.size * WidgetAlignment.entries.size, layouts.distinct().size)
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
    fun resizedHostUsesCurrentBounds() {
        assertEquals(WidgetHostSize(467, 104), widgetHostSize(467, 104))
    }

    @Test
    fun hostDimensionsUseTheMatchingOrientationPairAndFallbackWhenMaxIsAbsent() {
        assertEquals(WidgetHostSize(144, 84), widgetHostSize(144, 58, 196, 84, landscape = false))
        assertEquals(WidgetHostSize(196, 58), widgetHostSize(144, 58, 196, 84, landscape = true))
        assertEquals(WidgetHostSize(144, 58), widgetHostSize(144, 58, 0, 0, landscape = false))
        assertEquals(WidgetHostSize(144, 58), widgetHostSize(144, 58, 0, 0, landscape = true))
        assertEquals(WidgetHostSize(144, 58), widgetHostSize(144, 58, -1, -1, landscape = false))
        assertEquals(WidgetHostSize(1, 1), widgetHostSize(0, 0, 0, 0, landscape = true))
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
    fun appliesOpacityToEveryWidgetBackgroundMode() {
        WidgetBackgroundMode.entries.forEach { mode ->
            assertEquals(
                mode.name,
                153,
                widgetBackgroundAlpha(WidgetSettings(backgroundMode = mode, opacity = 60), 255),
            )
        }
    }

    @Test
    fun formatsWidgetUpdateTimeWithTheSelectedLocale() {
        val epoch = 1_725_000_000_000L
        val english = widgetUpdatedAt(epoch, ZoneOffset.UTC, Locale.US)
        val french = widgetUpdatedAt(epoch, ZoneOffset.UTC, Locale.FRANCE)

        assertNotEquals(english, french)
    }

    @Test
    fun formatsWidgetDateWithTheSelectedLocaleAndClockLikeTheHost() {
        val date = LocalDate.of(2026, 8, 25)
        val time = LocalTime.of(13, 5)

        assertNotEquals(widgetDate(date, Locale.US), widgetDate(date, Locale.FRANCE))
        assertEquals("13:05", widgetClock(time, is24Hour = true))
        assertEquals("1:05", widgetClock(time, is24Hour = false))
    }

    @Test
    fun derivesPreviewAvailabilityFromTheSameCachedWidgetDataAsTheProvider() {
        assertEquals(
            WidgetDataAvailability(true, true, true, true, true),
            widgetDataAvailability(listOf("12:00", "13:00", "14:00"), listOf("10", "11", "12"), 20, 11f, 68, 1L),
        )
        assertEquals(
            WidgetDataAvailability(false, false, false, false, false),
            widgetDataAvailability(listOf("12:00", "13:00"), listOf("10", "11", "12"), -1, Float.NaN, -1, 0L),
        )
        assertEquals(
            WidgetDataAvailability(false, false, false, false, true),
            widgetDataAvailability(listOf("12:00", "13:00", ""), listOf("10", "11", "12"), 101, -1f, 101, 1L),
        )
    }

    @Test
    fun decodesPreviewBackgroundOnlyForBackgroundInputs() {
        val settings = WidgetSettings(opacity = 60)
        val key = widgetPreviewBackgroundKey(settings, WeatherKind.CLOUDY, isDay = true)

        assertEquals(key, widgetPreviewBackgroundKey(settings.copy(showClock = false, textScale = 140), WeatherKind.CLOUDY, true))
        assertNotEquals(key, widgetPreviewBackgroundKey(settings.copy(opacity = 61), WeatherKind.CLOUDY, true))
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

        val availability = WidgetDataAvailability(true, true, false, false, true)
        val wide = widgetContentVisibility(settings, WidgetSize.WIDE, availability)
        val tall = widgetContentVisibility(
            settings.copy(showPrecipitation = true, showWind = true, showHumidity = true),
            WidgetSize.TALL,
            availability,
        )

        assertEquals(153, widgetBackgroundAlpha(settings, 255))
        assertFalse(wide.showLocation)
        assertFalse(wide.showCondition)
        assertTrue(wide.showRange)
        assertTrue(wide.showHourly)
        assertFalse(wide.showMetrics)
        assertTrue(tall.showMetrics)
        assertTrue(tall.showPrecipitation)
        assertFalse(tall.showWind)
        assertFalse(tall.showHumidity)
    }

    @Test
    fun advancedLineUsesOnlyEnabledAvailableFieldsAndRespectsCompactSize() {
        val settings = WidgetSettings(showDewPoint = true, showPressure = true, showMoon = true)
        val text = widgetAdvancedText(
            settings,
            WidgetAdvancedData(
                dewPoint = "Dew point 8°",
                pressure = null,
                visibility = "Visibility 10 km",
                windGusts = null,
                moon = "Moon 95%",
            ),
        )

        assertEquals("Dew point 8° · Moon 95%", text)
        assertFalse(widgetAdvancedVisible(WidgetSize.STANDARD, text))
        assertTrue(widgetAdvancedVisible(WidgetSize.TALL, text))
        assertTrue(widgetAdvancedVisible(WidgetSize.WIDE, text))
        assertFalse(widgetAdvancedVisible(WidgetSize.COMPACT, text))
        assertFalse(widgetAdvancedVisible(WidgetSize.TALL, ""))
    }

    @Test
    fun temperatureFitKeepsRequestedSizeWhenItFitsAndShrinksOnlyToAvailableWidth() {
        assertEquals(34f, fitWidgetTemperatureSp(34f) { it * 5f <= 200f }, 0f)
        val compact = fitWidgetTemperatureSp(34f) { it * 5.8f <= 157f }
        assertTrue(compact * 5.8f <= 157f)
        assertEquals(157f / 5.8f, compact, 0.02f)
        val largeText = fitWidgetTemperatureSp(47.6f) { it * 5.8f <= 157f }
        assertEquals(compact, largeText, 0.02f)
        val nonlinear = fitWidgetTemperatureSp(47.6f) { it * it / 10f <= 100f }
        assertTrue(nonlinear * nonlinear / 10f <= 100f)
        assertEquals(31.622f, nonlinear, 0.02f)
        val narrow = fitWidgetTemperatureSp(34f) { it * 5f <= 40f }
        assertEquals(12f, narrow, 0f)
        assertEquals(12f, fitWidgetTemperatureSp(47.6f) { it * 5f <= 0f }, 0f)
        assertEquals(12f, fitWidgetTemperatureSp(47.6f) { it * 5f <= -40f }, 0f)
    }

    @Test
    fun compactHeaderHidesOptionalContentThenReducesPaddingWithoutChangingSettings() {
        val settings = WidgetSettings(contentPaddingDp = 24, textScale = 140)
        val visibility = widgetContentVisibility(settings, WidgetSize.COMPACT, WidgetDataAvailability(false, false, false, false, false))
        fun fit(width: Float) = fitWidgetHeader(
            47.6f, visibility, settings.contentPaddingDp,
            { visible, padding -> width - padding * 2 - (if (visible.showIcon) 50 else 0) - (if (visible.showClock || visible.showDate) 60 else 0) },
            { it * 5f },
        )
        val tiny = fit(90f)
        assertFalse(tiny.visibility.showIcon)
        assertFalse(tiny.visibility.showClock)
        assertFalse(tiny.visibility.showDate)
        assertEquals(15, tiny.contentPaddingDp)
        assertEquals(12f, tiny.textSizeSp, 0.02f)
        val compact = fit(180f)
        assertFalse(compact.visibility.showIcon)
        assertTrue(compact.visibility.showClock)
        assertEquals(24, compact.contentPaddingDp)
        assertTrue(compact.textSizeSp >= 12f)
        val expanded = fit(400f)
        assertTrue(expanded.visibility.showIcon)
        assertTrue(expanded.visibility.showClock)
        assertEquals(24, expanded.contentPaddingDp)
        assertEquals(47.6f, expanded.textSizeSp, 0f)
        assertEquals(24, settings.contentPaddingDp)
        val zero = fit(0f)
        assertEquals(0, zero.contentPaddingDp)
        assertEquals(12f, zero.textSizeSp, 0f)
    }

    @Test
    fun accessibilityFontFitsHeaderHeightAndRestoresRequestedScaleWhenExpanded() {
        val settings = WidgetSettings(contentPaddingDp = 24, textScale = 140)
        val visible = widgetContentVisibility(settings, WidgetSize.COMPACT, WidgetDataAvailability(false, false, false, false, false))
        fun fit(heightDp: Int) = fitWidgetHeader(
            47.6f, visible, 24, { _, _ -> 300f }, { it * 5f },
            availableHeight = { _, padding -> (heightDp - padding * 2) * 3f },
            measureHeight = { it * 7f },
        )
        val compact = fit(84)
        assertEquals(24, compact.contentPaddingDp)
        assertTrue(compact.textSizeSp >= 12f)
        assertTrue(compact.textSizeSp * 7f <= 108f)
        val short = fit(40)
        assertEquals(6, short.contentPaddingDp)
        assertEquals(12f, short.textSizeSp, 0.02f)
        assertFalse(short.visibility.showClock)
        val expanded = fit(200)
        assertEquals(47.6f, expanded.textSizeSp, 0f)
        assertEquals(24, expanded.contentPaddingDp)
        assertTrue(expanded.visibility.showClock)
    }

    @Test
    fun crowdedWideHeaderDropsLowerPriorityRowsAndRestoresThemWhenTall() {
        val settings = WidgetSettings(contentPaddingDp = 24, customLabel = "My field", showHourly = true,
            showPrecipitation = true, showWind = true, showHumidity = true, showUpdatedAt = true)
        val visible = widgetContentVisibility(settings, WidgetSize.WIDE,
            WidgetDataAvailability(true, true, true, true, true), 400, "Pressure 1015 hPa")
        fun fit(height: Float) = fitWidgetHeader(
            47.6f, visible, 24, { _, _ -> 1000f }, { it * 5f },
            availableHeight = { content, padding ->
                height - padding * 2 - (if (content.showAdvanced) 50 else 0) -
                    (if (content.showUpdatedAt) 8 else 0) - (if (content.showHourly) 45 else 0) -
                    (if (content.showMetrics) 35 else 0) - (if (content.showLabel) 12 else 0) -
                    (if (content.showLocation) 16 else 0)
            },
            measureHeight = { it * 1.5f },
        )
        val short = fit(40f)
        assertEquals(0, short.contentPaddingDp)
        assertFalse(short.visibility.showAdvanced)
        assertFalse(short.visibility.showHourly)
        assertFalse(short.visibility.showMetrics)
        assertFalse(short.visibility.showLabel)
        assertFalse(short.visibility.showLocation)
        assertTrue(short.textSizeSp >= 12f)
        assertTrue(short.textSizeSp * 1.5f <= 40f)
        val tall = fit(400f)
        assertEquals(visible, tall.visibility)
        assertEquals(24, tall.contentPaddingDp)
        assertEquals(47.6f, tall.textSizeSp, 0f)
        assertTrue(settings.showHourly)
    }

    @Test
    fun appStyleIsExplicitAndLegacyGeometryDefaultsRemainStable() {
        val current = WidgetSettings(customLabel = "My field", imageUri = "content://example/photo")
        val preset = widgetPresetSettings(WidgetPreset.APP_STYLE, current)
        assertEquals(WidgetBackgroundMode.APP_STYLE, preset.backgroundMode)
        assertEquals(WidgetFontStyle.MATERIAL, preset.fontStyle)
        assertEquals("My field", preset.customLabel)
        assertEquals(current.imageUri, preset.imageUri)
        assertEquals(WidgetBackgroundMode.AUTOMATIC, widgetBackgroundMode(null, null))
        assertEquals(WidgetCorners.ROUND, widgetCorners(null))
        assertEquals(WidgetCorners.ROUND, widgetCorners("future-shape"))
        assertEquals(0, current.copy(contentPaddingDp = -5).normalized().contentPaddingDp)
        assertEquals(24, current.copy(contentPaddingDp = 99).normalized().contentPaddingDp)
        assertEquals(0, widgetBackgroundAlpha(preset.copy(opacity = 0), 255))
    }

    @Test
    fun savedEditorStateRestoresGeometryAndMigratesOlderLists() {
        val scope = object : SaverScope { override fun canBeSaved(value: Any): Boolean = true }
        val settings = WidgetSettings(corners = WidgetCorners.SOFT, contentPaddingDp = 21, textScale = 130)
        val saved = requireNotNull(with(WidgetSettingsSaver) { scope.save(settings) })
        assertEquals(settings, WidgetSettingsSaver.restore(saved))
        val legacy = (saved as List<*>).take(29)
        val restored = requireNotNull(WidgetSettingsSaver.restore(legacy))
        assertEquals(WidgetCorners.ROUND, restored.corners)
        assertEquals(DEFAULT_WIDGET_PADDING_DP, restored.contentPaddingDp)
        assertEquals(130, restored.textScale)
    }

    @Test
    fun backgroundControlsOnlyValidateColorsThatAffectTheChosenMode() {
        val settings = WidgetSettings(backgroundStart = "invalid", backgroundEnd = "invalid")
        assertTrue(settings.copy(backgroundMode = WidgetBackgroundMode.APP_STYLE).editableBackgroundColors().isEmpty())
        assertEquals(1, settings.copy(backgroundMode = WidgetBackgroundMode.SOLID).editableBackgroundColors().size)
        assertEquals(2, settings.copy(backgroundMode = WidgetBackgroundMode.GRADIENT).editableBackgroundColors().size)
        assertEquals(2, settings.copy(backgroundMode = WidgetBackgroundMode.CUSTOM_IMAGE).editableBackgroundColors().size)
        assertNotEquals(
            widgetPreviewBackgroundKey(settings, WeatherKind.CLEAR, true, 320, 180),
            widgetPreviewBackgroundKey(settings, WeatherKind.CLEAR, true, 180, 320),
        )
        assertNotEquals(
            widgetPreviewBackgroundKey(settings.copy(corners = WidgetCorners.ROUND), WeatherKind.CLEAR, true),
            widgetPreviewBackgroundKey(settings.copy(corners = WidgetCorners.SQUARE), WeatherKind.CLEAR, true),
        )
    }

    @Test
    fun wideningTallWidgetsPreservesEnabledMetricsAndUsesExtraHeightForHours() {
        val settings = WidgetSettings(showPrecipitation = true, showWind = true, showHumidity = true)
        val available = WidgetDataAvailability(true, true, true, true, true)
        val tall = widgetContentVisibility(settings, widgetSize(240, 180), available, 180)
        val wide = widgetContentVisibility(settings, widgetSize(360, 180), available, 180)
        listOf(tall, wide).forEach {
            assertTrue(it.showPrecipitation)
            assertTrue(it.showWind)
            assertTrue(it.showHumidity)
        }
        assertTrue(wide.showHourly)
        val shorter = widgetContentVisibility(settings, widgetSize(360, 120), available, 120)
        assertTrue(shorter.showMetrics)
        assertFalse(shorter.showHourly)
        val short = widgetContentVisibility(settings, widgetSize(360, 90), available, 90)
        assertFalse(short.showMetrics)
        val missing = widgetContentVisibility(settings, WidgetSize.WIDE, WidgetDataAvailability(false, false, false, false, false), 180)
        assertFalse(missing.showMetrics)
        assertFalse(missing.showHourly)
    }

    @Test
    fun widgetIconsDistinguishClearCloudyAndNight() {
        assertEquals(R.drawable.ic_weather_moon, widgetIconFor(WeatherKind.CLEAR, false))
        assertEquals(R.drawable.ic_weather_partly_cloudy, widgetIconFor(WeatherKind.PARTLY_CLOUDY, true))
        assertEquals(R.drawable.ic_weather_partly_cloudy_night, widgetIconFor(WeatherKind.MAINLY_CLEAR, false))
        assertEquals(R.drawable.ic_weather_cloud, widgetIconFor(WeatherKind.CLOUDY, true))
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
        assertEquals(
            WidgetWorkAdmission.ENQUEUE,
            widgetWorkAdmission(WidgetWorkKind.APPLY, WidgetWorkKind.DELETE),
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

    @Test
    fun treatsDurableCommitAsApplySuccessEvenWhenRenderFallsBack() {
        assertTrue(widgetApplySucceeded(commitSucceeded = true, renderSucceeded = false))
        assertFalse(widgetApplySucceeded(commitSucceeded = false, renderSucceeded = true))
    }

    @Test
    fun keepsApplyActiveUntilItsWorkerCallbackCompletes() {
        val state = WidgetApplyState()

        assertTrue(state.start())
        assertTrue(state.isActive())
        state.cancel()
        assertFalse(state.isActive())
        assertFalse(state.commitIfActive { true })
    }

    @Test
    fun reconcilesOnlyUnreferencedPersistedImageUris() {
        val first = "content://example/first"
        val shared = "content://example/shared"
        val orphan = "content://example/orphan"
        val referenced = widgetImageUris(
            mapOf(
                "widget_settings_1_image_uri" to first,
                "widget_settings_2_image_uri" to shared,
                "widget_settings_3_image_uri" to shared,
                "widget_settings_3_label" to orphan,
            ),
        )

        assertEquals(setOf(first, shared), referenced)
        assertEquals(setOf(orphan), widgetOrphanImageUris(setOf(first, shared, orphan), referenced))
    }

    @Test
    fun mergesDuplicateDeleteIdsAndRejectsApplyForAnInactiveWidget() {
        assertEquals(intArrayOf(3, 7, 9).toList(), widgetDeleteIds(intArrayOf(3, 7), intArrayOf(7, 9)).toList())
        assertTrue(widgetApplyCanCommit(active = true, widgetExists = true))
        assertFalse(widgetApplyCanCommit(active = false, widgetExists = true))
        assertFalse(widgetApplyCanCommit(active = true, widgetExists = false))
        assertEquals(
            WidgetDeleteMergeTarget.QUEUED,
            widgetDeleteMergeTarget(activeComplete = true, queuedDelete = true),
        )
    }

    @Test
    fun clearsUiApplyStateBeforeIgnoringAWeakDestroyedTarget() {
        assertFalse(widgetCanFinishActivity(destroyed = true, finishing = false))
        assertFalse(widgetCanFinishActivity(destroyed = false, finishing = true))
        assertTrue(widgetCanFinishActivity(destroyed = false, finishing = false))
    }

    @Test
    fun completesEveryMergedDeleteCallbackAfterCleanupFailure() {
        var completed = false

        assertTrue(runCatching {
            finishWidgetDelete({ error("cleanup") }) { completed = true }
        }.isFailure)
        assertTrue(completed)
    }
}
