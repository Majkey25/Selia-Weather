package cz.majkey.pocasicesko.ui

import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import cz.majkey.pocasicesko.R
import cz.majkey.pocasicesko.data.CzechLocation
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.maplibre.android.maps.MapView
import java.io.File

class NativeRadarTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun nativeMapLoadsForecastAndObservedPlaybackWithoutAWebView() {
        val context = compose.activity
        val appearance = mutableStateOf(AppearanceConfig(mode = AppearanceMode.LIGHT))
        compose.setContent { WeatherTheme(config = appearance.value) {
            MapHubScreen(CzechLocation("Prague", "", 50.0755, 14.4378, "CZ"), PaddingValues(), true,
                "Europe/Prague", {})
        } }
        try {
            compose.waitUntil(60_000) {
                compose.onAllNodesWithTag("radar-timeline").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("radar-play").assertIsDisplayed().assertIsEnabled()
            compose.runOnIdle {
                assertTrue(descendants(context.window.decorView).any { it is MapView })
                assertFalse(descendants(context.window.decorView).any { it is WebView })
            }
            fun shownTime(): String = compose.onNodeWithTag("radar-frame-time").fetchSemanticsNode()
                .config[SemanticsProperties.Text].joinToString { it.text }
            compose.waitUntil(60_000) { shownTime().contains("·") }
            val first = shownTime()
            compose.onNodeWithText(context.getString(R.string.native_radar_forecast)).performClick()
            assertTrue("Selecting the active mode must keep its frame", shownTime() == first)
            compose.onNodeWithTag("radar-play").performClick()
            compose.waitUntil(60_000) { shownTime() != first && !shownTime().contains("…") }
            compose.onNodeWithTag("radar-play").performClick()
            assertNotEquals(first, shownTime())
            val timeline = compose.onNodeWithTag("radar-timeline")
            val last = timeline.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].range.endInclusive
            val beforeTail = shownTime()
            timeline.performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(last)) }
            compose.waitUntil(60_000) { shownTime() != beforeTail }
            compose.onNodeWithText("GDPS · mm / 6 h").assertIsDisplayed()
            compose.runOnIdle { appearance.value = AppearanceConfig(mode = AppearanceMode.DARK) }
            compose.waitUntil(60_000) { shownTime().contains("·") }
            compose.onNodeWithTag("radar-play").assertIsEnabled()
            compose.onNodeWithText(context.getString(R.string.native_radar_observed)).performClick()
            compose.waitUntil(60_000) { compose.onAllNodesWithTag("radar-timeline").fetchSemanticsNodes().isNotEmpty() }
            compose.waitUntil(60_000) { shownTime().contains("·") }
            val observed = shownTime()
            compose.onNodeWithTag("radar-play").performClick()
            compose.waitUntil(60_000) { shownTime() != observed && !shownTime().contains("…") }
            compose.onNodeWithTag("radar-play").performClick()
            compose.waitForIdle()
        } finally {
            val screen = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            File(context.getExternalFilesDir(null), "native-radar.png").outputStream().use {
                screen.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            screen.recycle()
        }
    }

    @Test fun compactMapKeepsPlaybackAndTimelineReachableWithLargeText() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                WeatherTheme(config = AppearanceConfig()) {
                    Box(Modifier.height(360.dp)) {
                        NativeRadarScreen(CzechLocation("Prague", "", 50.0755, 14.4378, "CZ"), topInsetDp = 64)
                    }
                }
            }
        }
        compose.waitUntil(60_000) { compose.onAllNodesWithTag("radar-timeline").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("radar-play").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("radar-timeline").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("radar-range-end").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("radar-range-start").assertIsDisplayed()
        val start = compose.onNodeWithTag("radar-range-start").fetchSemanticsNode().boundsInRoot
        val end = compose.onNodeWithTag("radar-range-end").fetchSemanticsNode().boundsInRoot
        assertTrue("Timeline endpoints must not overlap: $start / $end", start.width > 0 && end.width > 0 && start.right < end.left)
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
}
