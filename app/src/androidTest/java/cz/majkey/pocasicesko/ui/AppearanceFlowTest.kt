package cz.majkey.pocasicesko.ui

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.test.platform.app.InstrumentationRegistry
import cz.majkey.pocasicesko.R
import cz.majkey.pocasicesko.data.ConfidenceLevel
import cz.majkey.pocasicesko.data.ConfidenceReason
import cz.majkey.pocasicesko.data.ForecastConfidence
import cz.majkey.pocasicesko.data.WeatherKind
import cz.majkey.pocasicesko.data.WeatherRepository
import cz.majkey.pocasicesko.units.MeasurementSystem
import cz.majkey.pocasicesko.units.WeatherUnitFormatter
import java.util.Locale
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AppearanceFlowTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun legacySelectionMigratesAndNewSettingsPersistTogether() {
        val isolated = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("appearance_flow_$name", mode)
        }
        isolated.getSharedPreferences(WeatherRepository.PREFERENCES_NAME, Context.MODE_PRIVATE).edit().clear()
            .putString("app_appearance", "SUNSET").commit()
        val legacy = AppearanceSettings.load(isolated)
        assertEquals(AppAppearance.SUNSET, legacy.lightTheme)
        assertEquals(AppAppearance.SUNSET, legacy.darkTheme)
        val selected = legacy.copy(mode = AppearanceMode.DARK, lightTheme = AppAppearance.SAND,
            darkTheme = AppAppearance.AMOLED, font = AppearanceFont.SERIF, textScale = 1.2f, cornerRadius = 12)
        AppearanceSettings.save(isolated, selected)
        assertEquals(selected, AppearanceSettings.load(isolated))
    }

    @Test
    fun lightAndDarkPreviewSelectionsStayIndependent() {
        val config = mutableStateOf(AppearanceConfig(mode = AppearanceMode.LIGHT))
        compose.setContent { WeatherTheme(config.value) {
            AppearanceSheet(config.value, { config.value = it }, {})
        } }
        val ocean = context.getString(R.string.appearance_ocean)
        val light = context.getString(R.string.appearance_light)
        val dark = context.getString(R.string.appearance_dark)
        compose.onNodeWithContentDescription("$ocean · $light").performScrollTo().performClick()
        assertEquals(AppAppearance.OCEAN, config.value.lightTheme)
        assertEquals(AppAppearance.WEATHER, config.value.darkTheme)
        compose.onNodeWithContentDescription("$ocean · $light").assertIsSelected()
        compose.onNodeWithContentDescription("$ocean · $dark").performClick()
        assertEquals(AppAppearance.OCEAN, config.value.darkTheme)
    }

    @Test
    fun materialPreviewsMatchRenderedLightAndDarkBackgrounds() {
        val config = AppearanceConfig(mode = AppearanceMode.LIGHT)
        compose.setContent { WeatherTheme(config) { AppearanceSheet(config, {}, {}) } }
        val material = context.getString(R.string.appearance_material)
        listOf(false, true).forEach { dark ->
            val mode = if (dark) AppearanceMode.DARK else AppearanceMode.LIGHT
            val label = context.getString(if (dark) R.string.appearance_dark else R.string.appearance_light)
            val preview = compose.onNodeWithContentDescription("$material · $label").performScrollTo()
                .captureToImage().asAndroidBitmap()
            val expected = renderedAppearance(context,
                config.copy(mode = mode, lightTheme = AppAppearance.MATERIAL, darkTheme = AppAppearance.MATERIAL),
                WeatherKind.CLEAR, true)
            assertEquals(expected.colors.background.toArgb(), preview.getPixel(preview.width / 2, preview.height / 5))
        }
    }

    @Test
    fun automaticTextHasOneLabeledSwitchWithFullRowAction() {
        compose.setContent { WeatherTheme { AppearanceSheet(AppearanceConfig(), {}, {}) } }
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(context.getString(R.string.appearance_edit_custom)))
        compose.onNodeWithText(context.getString(R.string.appearance_edit_custom)).performClick()
        val toggle = compose.onNodeWithText(context.getString(R.string.appearance_auto_text)).performScrollTo()
        toggle.assertIsOn()
        assertEquals(Role.Switch, toggle.fetchSemanticsNode().config[SemanticsProperties.Role])
        assertTrue(toggle.fetchSemanticsNode().boundsInRoot.height >= (48 * context.resources.displayMetrics.density).roundToInt())
        compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch), useUnmergedTree = true)
            .assertCountEquals(1)
        toggle.performClick().assertIsOff()
        toggle.performClick().assertIsOn()
    }

    @Test
    fun unsavedCustomPaletteSurvivesStateRestoration() {
        val restoration = StateRestorationTester(compose)
        var applied: AppearanceConfig? = null
        restoration.setContent { WeatherTheme {
            AppearanceSheet(AppearanceConfig(), { applied = it }, {})
        } }
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(context.getString(R.string.appearance_edit_custom)))
        compose.onNodeWithText(context.getString(R.string.appearance_edit_custom)).performClick()
        compose.onNodeWithText(context.getString(R.string.appearance_auto_text)).performScrollTo().performClick().assertIsOff()
        val startColor = context.getString(R.string.appearance_start_color)
        compose.onNodeWithText(startColor).performScrollTo().performTextReplacement("#123456")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText(context.getString(R.string.appearance_auto_text)).performScrollTo().assertIsOff()
        compose.onNodeWithText(startColor).performScrollTo().assertTextContains("#123456")
        compose.onNodeWithText(context.getString(R.string.appearance_apply)).performScrollTo().performClick()
        assertEquals(false, applied?.custom?.automaticText)
        assertEquals("#123456", applied?.custom?.backgroundStart)
    }

    @Test
    fun customPaletteCannotApplyInvisibleWhiteText() {
        val invalid = AppearanceConfig(custom = CustomAppearance("#FFFFFF", "#FFFFFF", "#FFFFFF", "#336699", "#FFFFFF", "#FFFFFF", false))
        var applies = 0
        compose.setContent { WeatherTheme {
            AppearanceSheet(invalid, { applies++ }, {})
        } }
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(context.getString(R.string.appearance_edit_custom)))
        compose.onNodeWithText(context.getString(R.string.appearance_edit_custom)).performClick()
        compose.onNodeWithText(context.getString(R.string.appearance_apply)).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText(context.getString(R.string.appearance_contrast_help)).assertIsDisplayed()
        assertEquals(0, applies)
    }

    @Test
    fun confidenceExplainsThatAgreementIsNotAccuracyProbability() {
        val units = WeatherUnitFormatter(MeasurementSystem.METRIC, Locale.ENGLISH)
        compose.setContent { WeatherTheme {
            ForecastConfidenceBadge(ForecastConfidence(ConfidenceLevel.HIGH, ConfidenceReason.AGREEMENT), units)
        } }
        compose.onNodeWithContentDescription(context.getString(R.string.confidence_label, context.getString(R.string.confidence_high))).performClick()
        compose.onNodeWithText(context.getString(R.string.confidence_method)).performScrollTo().assertIsDisplayed()
    }
}
