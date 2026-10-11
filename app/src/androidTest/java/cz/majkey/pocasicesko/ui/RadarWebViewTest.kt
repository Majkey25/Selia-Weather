package cz.majkey.pocasicesko.ui

import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import cz.majkey.pocasicesko.data.CzechLocation
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

/** Network smoke test on the device's real WebView, not the desktop browser. */
class RadarWebViewTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun secureAssetOriginLoadsObservedAndForecastFrames() {
        val arguments = InstrumentationRegistry.getArguments()
        val testHeight = arguments.getString("radarHeightDp")?.toIntOrNull()?.takeIf { it in 300..800 }
        val testFont = arguments.getString("radarFontScale")?.toFloatOrNull()?.takeIf { it in 1f..2.5f }
        val fullscreen = mutableStateOf(true)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, testFont ?: density.fontScale)) {
                WeatherTheme {
                    Box(if (testHeight == null) Modifier else Modifier.height(testHeight.dp)) {
                        MapHubScreen(CzechLocation("Prague", "", 50.0755, 14.4378, "CZ"), PaddingValues(),
                            fullscreen.value, "Europe/Prague", { fullscreen.value = !fullscreen.value })
                    }
                }
            }
        }
        val view = compose.runOnIdle { findWebView(compose.activity.window.decorView)!! }
        val state = AtomicReference(JSONObject())
        fun readState() {
            compose.runOnIdle {
                view.evaluateJavascript(
                    "JSON.stringify({origin:location.origin, mode:typeof mode==='undefined'?'':mode," +
                        "time:document.getElementById('time')?.textContent," +
                        "ready:typeof radarLayer!=='undefined'&&!!radarLayer," +
                        "frame:typeof frameIndex==='undefined'?-1:frameIndex," +
                        "count:typeof frames==='undefined'?0:frames.length," +
                        "horizon:typeof frames==='undefined'||!frames.length?0:(frames[frames.length-1].time-Date.now()/1000)/3600," +
                        "model:typeof forecastCache==='undefined'||!forecastCache?'':forecastCache.key," +
                        "width:innerWidth,panelTop:document.querySelector('.panel').getBoundingClientRect().top," +
                        "panelBottom:document.querySelector('.panel').getBoundingClientRect().bottom," +
                        "modesBottom:document.getElementById('radar-modes').getBoundingClientRect().bottom," +
                        "creditsBottom:map.attributionControl.getContainer().getBoundingClientRect().bottom," +
                        "short:document.body.classList.contains('short')," +
                        "pending:typeof pendingLayer!=='undefined'&&!!pendingLayer," +
                        "status:document.getElementById('status')?.textContent})",
                ) { result ->
                    (JSONTokener(result).nextValue() as? String)?.let { state.set(JSONObject(it)) }
                }
            }
        }
        compose.waitUntil(30_000) { readState(); state.get().optBoolean("ready") }
        val visibleHeight = view.height.toDouble() * state.get().getDouble("width") / view.width
        assertTrue("Radar controls must stay onscreen: ${state.get()}, height=$visibleHeight",
            state.get().getDouble("panelTop") >= 0 && state.get().getDouble("panelBottom") <= visibleHeight + 1)
        assertEquals(visibleHeight <= 400, state.get().getBoolean("short"))
        compose.runOnIdle { fullscreen.value = false }
        compose.waitUntil(10_000) {
            readState()
            val height = view.height.toDouble() * state.get().optDouble("width") / view.width
            state.get().optDouble("panelTop") >= 0 && state.get().optDouble("panelBottom") <= height + 1
        }
        compose.runOnIdle { fullscreen.value = true }
        compose.waitUntil(10_000) {
            readState()
            state.get().optDouble("panelBottom") > visibleHeight - 20 && state.get().optDouble("panelBottom") <= visibleHeight + 1
        }
        assertEquals("https://appassets.androidplatform.net", state.get().getString("origin"))
        assertEquals("observed", state.get().getString("mode"))
        val profiling = InstrumentationRegistry.getArguments().getString("profileRadar") == "true"
        val tileSize = InstrumentationRegistry.getArguments().getString("radarTileSize")
            ?.toIntOrNull()?.takeIf { it == 256 || it == 512 }
        val forecastStarted = SystemClock.elapsedRealtime()
        compose.runOnIdle {
            assertFalse(view.settings.allowFileAccess)
            if (profiling) view.evaluateJavascript("""
                window.radarProfile = {layers:0, tiles:0};
                var originalFrame = newFrameLayer;
                newFrameLayer = function(index) {
                  var layer = originalFrame(index);
                  radarProfile.layers++;
                  layer.on('tileloadstart', function() { radarProfile.tiles++; });
                  return layer;
                };
            """.trimIndent(), null)
            if (tileSize != null) view.evaluateJavascript("""
                var originalWms = L.tileLayer.wms;
                L.tileLayer.wms = function(url, options) {
                  options.tileSize = $tileSize;
                  return originalWms(url, options);
                };
            """.trimIndent(), null)
            view.evaluateJavascript("document.getElementById('forecast').click()", null)
        }
        compose.waitUntil(30_000) {
            readState()
            state.get().optString("mode") == "forecast" && state.get().optBoolean("ready")
        }
        assertTrue(state.get().getString("time").contains("–"))
        assertEquals("", state.get().getString("status"))
        assertTrue("Controls must not cover each other: ${state.get()}",
            state.get().getDouble("panelTop") >= state.get().getDouble("modesBottom") &&
                state.get().getDouble("panelTop") >= state.get().getDouble("creditsBottom"))
        assertTrue("Forecast must actually cover +48h: ${state.get()}", state.get().getDouble("horizon") >= 48.0)
        if (profiling) {
            InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                putString("stream", "\nRADAR_LOAD elapsedMs=${SystemClock.elapsedRealtime() - forecastStarted}\n")
            })
            val started = SystemClock.elapsedRealtime()
            compose.runOnIdle {
                view.evaluateJavascript("""
                    radarProfile.layers = radarProfile.tiles = 0;
                    var slider = document.getElementById('slider');
                    for (var i = 0; i < frames.length; i++) {
                      slider.value = i;
                      slider.dispatchEvent(new Event('input'));
                    }
                """.trimIndent(), null)
            }
            val report = AtomicReference<String?>(null)
            compose.waitUntil(30_000) {
                compose.runOnIdle {
                    view.evaluateJavascript("""
                        !pendingLayer && radarLayer && frameIndex === frames.length - 1 ?
                          JSON.stringify({layers:radarProfile.layers, tiles:radarProfile.tiles,
                            tileSize:radarLayer.options.tileSize, status:statusMessage}) : null
                    """.trimIndent()) { result ->
                        (JSONTokener(result).nextValue() as? String)?.let { report.set(it) }
                    }
                }
                report.get() != null
            }
            InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                putString("stream", "\nRADAR_SCRUB ${report.get()} elapsedMs=${SystemClock.elapsedRealtime() - started}\n")
            })
            assertEquals("", JSONObject(requireNotNull(report.get())).getString("status"))
        }
        compose.runOnIdle { view.evaluateJavascript("showFrame(frames.length-1)", null) }
        compose.waitUntil(30_000) {
            readState()
            state.get().optBoolean("ready") && !state.get().optBoolean("pending") &&
                state.get().optInt("frame") == state.get().optInt("count") - 1
        }
        assertEquals("", state.get().getString("status"))
        assertEquals("eu", state.get().getString("model"))
        compose.runOnIdle { view.evaluateJavascript("map.setView([35.6762,139.6503],6)", null) }
        compose.waitUntil(30_000) {
            readState()
            state.get().optString("model") == "global" && state.get().optBoolean("ready") && !state.get().optBoolean("pending")
        }
        assertTrue(state.get().getDouble("horizon") >= 48.0)
        compose.runOnIdle { view.evaluateJavascript("showFrame(frames.length-1)", null) }
        compose.waitUntil(30_000) {
            readState()
            state.get().optBoolean("ready") && !state.get().optBoolean("pending") &&
                state.get().optInt("frame") == state.get().optInt("count") - 1
        }
        assertEquals("", state.get().getString("status"))
    }

    private fun findWebView(view: View): WebView? = when (view) {
        is WebView -> view
        is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { findWebView(view.getChildAt(it)) }
        else -> null
    }
}
