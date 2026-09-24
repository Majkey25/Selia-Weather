package cz.majkey.pocasicesko.ui

import android.content.Context
import android.content.ComponentCallbacks
import android.content.ContextWrapper
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performScrollToIndex
import androidx.test.platform.app.InstrumentationRegistry
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import cz.majkey.pocasicesko.R
import cz.majkey.pocasicesko.data.CzechLocation
import cz.majkey.pocasicesko.data.WeatherRepository
import cz.majkey.pocasicesko.units.MeasurementSystem
import cz.majkey.pocasicesko.units.WeatherUnitFormatter
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Opt-in store capture run. Public Prague data, separate preferences/cache, no external sharing. */
class StoreScreenshotsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private var originalConfiguration: Configuration? = null

    @Before
    @Suppress("DEPRECATION")
    fun localizeCaptureActivity() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("captureStore") == "true")
        val tag = InstrumentationRegistry.getArguments().getString("storeLocale", "en-US")
        require(tag in setOf("en-US", "cs-CZ"))
        val resources = compose.activity.resources
        originalConfiguration = Configuration(resources.configuration)
        // Dialogs read their activity resources. This changes only the test process, not app language preferences.
        resources.updateConfiguration(Configuration(resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) }, resources.displayMetrics)
    }

    @After
    @Suppress("DEPRECATION")
    fun restoreCaptureActivity() {
        originalConfiguration?.let {
            val resources = compose.activity.resources
            resources.updateConfiguration(it, resources.displayMetrics)
        }
    }

    @Test
    fun capturePublicPragueScreens() {
        val tag = InstrumentationRegistry.getArguments().getString("storeLocale", "en-US")
        require(tag in setOf("en-US", "cs-CZ"))
        val locale = Locale.forLanguageTag(tag)
        val configuration = Configuration(compose.activity.resources.configuration).apply { setLocale(locale) }
        val context = CaptureContext(compose.activity.createConfigurationContext(configuration))
        val location = CzechLocation(if (tag == "cs-CZ") "Praha" else "Prague", "", 50.0755, 14.4378, "CZ")
        val repository = WeatherRepository(context)
        repository.selectLocation(location)
        val snapshot = runBlocking { repository.fetchForecast(location) }
        val archive = runBlocking { repository.fetchHistory(location) }
        assertTrue("Expected a five-year public archive", archive.days.size > 1500)
        val units = WeatherUnitFormatter(MeasurementSystem.METRIC, locale)
        val now = LocalDateTime.now(ZoneId.of(snapshot.timezone))
        val scene = mutableIntStateOf(0)
        compose.setContent {
            CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides configuration,
                LocalResources provides context.resources, LocalActivityResultRegistryOwner provides compose.activity) {
                WeatherTheme {
                    WeatherApp(repository, null, null, false, {})
                    when (scene.intValue) {
                        1 -> DayDetailSheet(snapshot.daily, snapshot.hourly,
                            forecastStartIndex(snapshot.daily, now.toLocalDate().toString()), now, units,
                            { scene.intValue = 0 })
                        2 -> WeatherDetailSheet(snapshot, location, units, { archive },
                            initialHistory = true, currentTime = now, onDismiss = { scene.intValue = 0 })
                    }
                }
            }
        }
        compose.waitUntil(60_000) {
            compose.onAllNodesWithContentDescription(context.getString(R.string.refresh_forecast)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(location.name).assertIsDisplayed()
        capture(context, tag, "01-weather.png")

        compose.runOnIdle { scene.intValue = 1 }
        val hour = now.withMinute(0).withSecond(0).withNano(0).toLocalTime().toString()
        compose.onAllNodes(hasScrollToNodeAction()).onLast().performScrollToNode(hasText(hour))
        compose.onNodeWithText(hour).performClick()
        val selectedDay = snapshot.daily[forecastStartIndex(snapshot.daily, now.toLocalDate().toString())]
        val hourIndex = hourlyForDay(snapshot.hourly, selectedDay.date).indexOfFirst { it.time.substringAfter('T').take(5) == hour }
        assertTrue(hourIndex >= 0)
        compose.onAllNodes(hasScrollToIndexAction()).onLast().performScrollToIndex(hourIndex + 1)
        compose.onNodeWithText(context.getString(R.string.uv_index)).assertExists()
        capture(context, tag, "04-hourly.png")

        compose.runOnIdle { scene.intValue = 2 }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText(context.getString(R.string.history_show_days)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(context.getString(R.string.history_show_days)).performScrollTo()
        capture(context, tag, "05-history.png")
        compose.onNodeWithText(context.getString(R.string.history_choose_ai_action)).performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.history_choose_ai_title)).assertIsDisplayed()
        capture(context, tag, "06-ask-ai.png")
        // Never confirm this dialog: store capture must not start an external recipient.
        File(requireNotNull(context.getExternalFilesDir("store-captures")), "$tag/provenance.txt").writeText(
            "Public Prague: 50.0755, 14.4378\n" +
                "Forecast fetched: ${snapshot.updatedAtEpochMillis}\n" +
                "NASA POWER version: ${archive.sourceVersion}\n" +
                "Archive accessed: ${archive.accessedAtEpochMillis}\n" +
                "Archive days: ${archive.days.size}, ${archive.days.first().date} to ${archive.days.last().date}\n" +
                "Actual app composables. System status/navigation bars cropped; app pixels unchanged. No AI recipient opened.\n",
        )
    }

    @Test
    fun capturePublicPragueMap() {
        val tag = InstrumentationRegistry.getArguments().getString("storeLocale", "en-US")
        require(tag in setOf("en-US", "cs-CZ"))
        val configuration = Configuration(compose.activity.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) }
        val context = compose.activity.createConfigurationContext(configuration)
        val location = CzechLocation(if (tag == "cs-CZ") "Praha" else "Prague", "", 50.0755, 14.4378, "CZ")
        compose.setContent {
            CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides configuration,
                LocalResources provides context.resources) {
                WeatherTheme {
                    Surface(color = Color(0xFF101820), contentColor = Color.White) {
                        MapHubScreen(location, PaddingValues(), true, "Europe/Prague", {})
                    }
                }
            }
        }
        val view = compose.runOnIdle { requireNotNull(findWebView(compose.activity.window.decorView)) }
        val state = AtomicReference(JSONObject())
        fun readState() {
            compose.runOnIdle {
                view.evaluateJavascript("""
                    JSON.stringify({ready:typeof radarLayer!=='undefined'&&!!radarLayer,
                        mode:typeof mode==='undefined'?'':mode,
                        frame:typeof frameIndex==='undefined'?-1:frameIndex,
                        pending:typeof pendingLayer!=='undefined'&&!!pendingLayer,
                        status:document.getElementById('status')?.textContent})
                """.trimIndent()) { result ->
                    (JSONTokener(result).nextValue() as? String)?.let { state.set(JSONObject(it)) }
                }
            }
        }
        compose.waitUntil(45_000) { readState(); state.get().optBoolean("ready") }
        compose.runOnIdle { view.evaluateJavascript("document.getElementById('forecast').click()", null) }
        compose.waitUntil(45_000) {
            readState()
            state.get().optString("mode") == "forecast" && state.get().optBoolean("ready") && !state.get().optBoolean("pending")
        }
        assertEquals("", state.get().getString("status"))
        compose.runOnIdle {
            view.evaluateJavascript("var slider=document.getElementById('slider');slider.value=3;slider.dispatchEvent(new Event('input'))", null)
        }
        compose.waitUntil(45_000) {
            readState()
            state.get().optInt("frame") == 3 && !state.get().optBoolean("pending")
        }
        assertEquals("", state.get().getString("status"))
        capture(context, tag, "03-forecast-map.png")
    }

    private fun capture(context: Context, tag: String, name: String) {
        compose.waitForIdle()
        SystemClock.sleep(350)
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        val insets = requireNotNull(ViewCompat.getRootWindowInsets(compose.activity.window.decorView))
            .getInsets(WindowInsetsCompat.Type.systemBars())
        val top = insets.top
        val bottom = insets.bottom
        val content = Bitmap.createBitmap(bitmap, 0, top, bitmap.width, bitmap.height - top - bottom)
        val directory = File(requireNotNull(context.getExternalFilesDir("store-captures")), tag)
        check(directory.isDirectory || directory.mkdirs())
        File(directory, name).outputStream().use { check(content.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        content.recycle()
        bitmap.recycle()
    }

    private fun findWebView(view: View): WebView? = when (view) {
        is WebView -> view
        is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { findWebView(view.getChildAt(it)) }
        else -> null
    }

    private class CaptureContext(base: Context) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun registerComponentCallbacks(callback: ComponentCallbacks) = baseContext.registerComponentCallbacks(callback)
        override fun unregisterComponentCallbacks(callback: ComponentCallbacks) = baseContext.unregisterComponentCallbacks(callback)
        override fun getSharedPreferences(name: String, mode: Int) =
            super.getSharedPreferences("store_capture_$name", mode)
        override fun getCacheDir(): File = File(super.getCacheDir(), "store-captures").apply { mkdirs() }
    }
}
