package cz.majkey.pocasicesko.ui

import android.content.ComponentCallbacks
import android.content.Context
import android.content.ContextWrapper
import android.view.KeyEvent
import android.view.InputDevice
import android.view.MotionEvent
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import cz.majkey.pocasicesko.R
import cz.majkey.pocasicesko.data.CzechLocation
import cz.majkey.pocasicesko.data.WeatherRepository
import cz.majkey.pocasicesko.notification.WeatherAlertSettings
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONTokener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class MainNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun capsuleKeepsAllTargetsAccessibleWithDoubleTextScale() {
        val context = compose.activity
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                WeatherTheme(config = AppearanceConfig()) {
                    var destination by remember { mutableStateOf(Destination.WEATHER) }
                    FloatingNavigation(destination, onDestination = { destination = it }, onAskAi = { destination = Destination.AI })
                }
            }
        }
        val minimumSize = 48f * context.resources.displayMetrics.density
        listOf(R.string.nav_weather, R.string.nav_maps, R.string.home_ask_ai).forEach { label ->
            val item = compose.onNodeWithContentDescription(context.getString(label))
            item.assertIsDisplayed()
            val bounds = item.fetchSemanticsNode().boundsInRoot
            assertTrue("Navigation width must be at least 48dp", bounds.width >= minimumSize - 0.5f)
            assertTrue("Navigation height must be at least 48dp", bounds.height >= minimumSize - 0.5f)
            item.performClick().assertIsSelected()
        }
        compose.onNodeWithText(context.getString(R.string.nav_ai)).assertIsDisplayed()
        compose.onNodeWithTag("main-navigation").assertIsDisplayed()
    }

    @Test
    fun timezoneChangesKeepWebViewAndSelectedTimelineFrame() {
        var timezone by mutableStateOf<String?>("Europe/Prague")
        compose.setContent {
            WeatherTheme(config = AppearanceConfig()) {
                MapHubScreen(
                    location = CzechLocation("Praha", "Praha", 50.0755, 14.4378),
                    padding = PaddingValues(),
                    fullscreen = false,
                    timezone = timezone,
                    onToggleFullscreen = {},
                    active = false,
                )
            }
        }
        val map = compose.runOnIdle { requireNotNull(findWebView(compose.activity.window.decorView)) }
        longitude(map)
        assertEquals("true", javascript(map, """
            (function() {
              var now = Math.floor(Date.now() / 1000);
              frames = [{type:'observed',time:now-1800,path:'/v2/radar/navigation-previous'},
                {type:'observed',time:now-1200,path:'/v2/radar/navigation-latest'}];
              frameIndex = 0; selectedTime = frames[0].time; selectionTouched = true;
              updateTimeline(); updateFrameInfo(frames[0]); updateSlider(frames[0]);
              return selectedTime === frames[0].time && frameIndex === 0;
            })()
        """.trimIndent()))
        val selectedFrame = javascript(map, "JSON.stringify([selectedTime,frameIndex,frames[frameIndex].path])")
        listOf("America/New_York", null, "Europe/Prague").forEach { selectedTimezone ->
            compose.runOnIdle { timezone = selectedTimezone }
            compose.runOnIdle {
                assertSame("Timezone changes must retain WebView", map, findWebView(compose.activity.window.decorView))
            }
            compose.waitUntil(10_000) {
                JSONTokener(javascript(map, "timezone")).nextValue() == (selectedTimezone ?: "UTC")
            }
            assertEquals(selectedFrame, javascript(map, "JSON.stringify([selectedTime,frameIndex,frames[frameIndex].path])"))
        }
    }

    @Test
    fun swipesFollowTabOrderAndRapidTapsFinishOnTheLastDestination() {
        val context = NavigationContext(compose.activity)
        WeatherAlertSettings(backgroundAlertsAllowed = false).save(context)
        val repository = WeatherRepository(context)
        compose.setContent {
            CompositionLocalProvider(LocalContext provides context, LocalActivityResultRegistryOwner provides compose.activity) {
                WeatherApp(repository, null, null, false, {})
            }
        }
        val weather = compose.onNodeWithContentDescription(context.getString(R.string.nav_weather))
        val radar = compose.onNodeWithContentDescription(context.getString(R.string.nav_maps))
        val ai = compose.onNodeWithContentDescription(context.getString(R.string.home_ask_ai))
        weather.assertIsSelected()
        compose.onNodeWithTag("main-pages").performTouchInput { swipeLeft() }
        radar.assertIsSelected()
        val map = compose.runOnIdle { requireNotNull(findWebView(compose.activity.window.decorView)) }
        val before = longitude(map)
        panMap(map)
        radar.assertIsSelected()
        assertNotEquals("Map pan must reach the WebView, not change the tab", before, longitude(map))
        compose.onNodeWithTag("radar-header").performTouchInput { swipeLeft() }
        ai.assertIsSelected()
        compose.onNodeWithTag("main-pages").performTouchInput { swipeRight() }
        radar.assertIsSelected()
        compose.runOnIdle { assertSame(map, findWebView(compose.activity.window.decorView)) }
        compose.onNodeWithTag("radar-header").performTouchInput { swipeRight() }
        weather.assertIsSelected()
        val aiClick = requireNotNull(ai.fetchSemanticsNode().config[SemanticsActions.OnClick].action)
        val weatherClick = requireNotNull(weather.fetchSemanticsNode().config[SemanticsActions.OnClick].action)
        val radarClick = requireNotNull(radar.fetchSemanticsNode().config[SemanticsActions.OnClick].action)
        compose.runOnUiThread {
            repeat(4) {
                aiClick()
                weatherClick()
                radarClick()
            }
        }
        radar.assertIsSelected()
        compose.onNodeWithTag("radar-header").assertIsDisplayed()
        compose.onNodeWithContentDescription(context.getString(R.string.radar_fullscreen)).performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.radar_exit_fullscreen)).assertIsDisplayed()
        compose.onNodeWithTag("main-navigation").assertDoesNotExist()
        compose.runOnIdle { assertSame(map, findWebView(compose.activity.window.decorView)) }
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        radar.assertIsSelected()
        compose.onNodeWithContentDescription(context.getString(R.string.radar_fullscreen)).assertIsDisplayed()
    }

    private fun panMap(view: WebView) {
        val origin = IntArray(2)
        var width = 0
        var height = 0
        compose.runOnIdle { view.getLocationOnScreen(origin); width = view.width; height = view.height }
        val started = SystemClock.uptimeMillis()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        fun send(action: Int, fraction: Float) {
            val event = MotionEvent.obtain(started, SystemClock.uptimeMillis(), action,
                origin[0] + width * fraction, origin[1] + height * 0.45f, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try { check(automation.injectInputEvent(event, true)) } finally { event.recycle() }
        }
        send(MotionEvent.ACTION_DOWN, 0.8f)
        for (step in 1..20) {
            SystemClock.sleep(20)
            send(MotionEvent.ACTION_MOVE, 0.8f - step * 0.03f)
        }
        send(MotionEvent.ACTION_UP, 0.2f)
    }

    private fun longitude(view: WebView): String {
        val value = AtomicReference<String?>(null)
        compose.waitUntil(10_000) {
            compose.runOnIdle {
                view.evaluateJavascript("typeof map === 'undefined' ? null : map.getCenter().lng.toFixed(5)") {
                    if (it != "null") value.set(it)
                }
            }
            value.get() != null
        }
        return requireNotNull(value.get())
    }

    private fun javascript(view: WebView, script: String): String {
        val value = AtomicReference<String?>(null)
        compose.runOnIdle { view.evaluateJavascript(script) { value.set(it) } }
        compose.waitUntil(10_000) { value.get() != null }
        return requireNotNull(value.get())
    }

    private fun findWebView(view: View): WebView? = when (view) {
        is WebView -> view
        is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { findWebView(view.getChildAt(it)) }
        else -> null
    }

    private class NavigationContext(base: Context) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun registerComponentCallbacks(callback: ComponentCallbacks) = baseContext.registerComponentCallbacks(callback)
        override fun unregisterComponentCallbacks(callback: ComponentCallbacks) = baseContext.unregisterComponentCallbacks(callback)
        override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("navigation_test_$name", mode)
        override fun getCacheDir(): File = File(super.getCacheDir(), "navigation-test").apply { mkdirs() }
    }
}
