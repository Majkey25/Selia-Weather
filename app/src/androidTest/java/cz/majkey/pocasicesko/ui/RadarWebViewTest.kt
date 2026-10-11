package cz.majkey.pocasicesko.ui

import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
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
                    "JSON.stringify({origin:location.origin," +
                        "time:document.getElementById('time')?.textContent," +
                        "ready:typeof radarLayer!=='undefined'&&!!radarLayer," +
                        "type:typeof radarLayer==='undefined'||!radarLayer?'':radarLayer.frame.type," +
                        "displayedTime:typeof radarLayer==='undefined'||!radarLayer?0:radarLayer.frame.time," +
                        "frame:typeof frameIndex==='undefined'?-1:frameIndex," +
                        "count:typeof frames==='undefined'?0:frames.length," +
                        "forecastCount:typeof frames==='undefined'?0:frames.filter(function(frame){return frame.type==='forecast';}).length," +
                        "firstTime:typeof frames==='undefined'||!frames.length?0:frames[0].time," +
                        "secondTime:typeof frames==='undefined'||frames.length<2?0:frames[1].time," +
                        "lastTime:typeof frames==='undefined'||!frames.length?0:frames[frames.length-1].time," +
                        "sliderMin:Number(document.getElementById('slider').min)," +
                        "sliderMax:Number(document.getElementById('slider').max)," +
                        "sliderTime:Number(document.getElementById('slider').value)," +
                        "sliderFocused:document.activeElement===document.getElementById('slider')," +
                        "geometry:(function(){" +
                            "var panel=document.querySelector('.panel'),rect=panel.getBoundingClientRect();" +
                            "return {left:rect.left+panel.clientLeft,top:rect.top+panel.clientTop," +
                                "right:rect.left+panel.clientLeft+panel.clientWidth,bottom:rect.top+panel.clientTop+panel.clientHeight," +
                                "visible:getComputedStyle(panel).visibility==='visible'," +
                                "controls:['play','slider'].map(function(id){" +
                                    "var element=document.getElementById(id),bounds=element.getBoundingClientRect(),style=getComputedStyle(element);" +
                                    "return {id:id,rect:bounds,visible:style.visibility==='visible'&&style.display!=='none'," +
                                        "hit:element.contains(document.elementFromPoint(bounds.left+bounds.width/2,bounds.top+bounds.height/2))};" +
                                "})};" +
                        "})()," +
                        "detailsOpen:!document.getElementById('details').hidden," +
                        "detailsOverPanel:document.body.classList.contains('details-over-panel')," +
                        "detailsRect:document.getElementById('details').getBoundingClientRect()," +
                        "summary:document.getElementById('frame-summary').textContent," +
                        "modeButtons:!!document.getElementById('observed')||!!document.getElementById('forecast')," +
                        "horizon:typeof frames==='undefined'||!frames.length?0:(frames[frames.length-1].time-Date.now()/1000)/3600," +
                        "model:typeof forecastCache==='undefined'||!forecastCache?'':forecastCache.key," +
                        "width:innerWidth,panelTop:document.querySelector('.panel').getBoundingClientRect().top," +
                        "panelBottom:document.querySelector('.panel').getBoundingClientRect().bottom," +
                        "toolsBottom:document.getElementById('radar-tools').getBoundingClientRect().bottom," +
                        "creditsBottom:map.attributionControl.getContainer().getBoundingClientRect().bottom," +
                        "short:document.body.classList.contains('short')," +
                        "pending:typeof pendingLayer!=='undefined'&&!!pendingLayer," +
                        "status:document.getElementById('status')?.textContent})",
                ) { result ->
                    (JSONTokener(result).nextValue() as? String)?.let { state.set(JSONObject(it)) }
                }
            }
        }
        fun assertTimelineVisible() {
            val snapshot = state.get()
            val panel = snapshot.getJSONObject("geometry")
            val nativeHeight = view.height.toDouble() * snapshot.getDouble("width") / view.width
            val controls = panel.getJSONArray("controls")
            assertTrue("Timeline panel must be visible: $snapshot", panel.getBoolean("visible"))
            for (index in 0 until controls.length()) {
                val control = controls.getJSONObject(index)
                val rect = control.getJSONObject("rect")
                assertTrue("${control.getString("id")} must be visible and reachable: $snapshot",
                    control.getBoolean("visible") && control.getBoolean("hit") &&
                        rect.getDouble("width") > 0 && rect.getDouble("height") >= 48 &&
                        rect.getDouble("left") >= panel.getDouble("left") &&
                        rect.getDouble("right") <= panel.getDouble("right") + 1 &&
                        rect.getDouble("top") >= panel.getDouble("top") &&
                        rect.getDouble("bottom") <= panel.getDouble("bottom") + 1 &&
                        rect.getDouble("left") >= 0 && rect.getDouble("right") <= snapshot.getDouble("width") + 1 &&
                        rect.getDouble("top") >= 0 && rect.getDouble("bottom") <= nativeHeight + 1)
            }
        }
        compose.waitUntil(30_000) {
            readState()
            state.get().optBoolean("ready") && !state.get().optBoolean("pending") &&
                state.get().optString("type") == "observed"
        }
        val visibleHeight = view.height.toDouble() * state.get().getDouble("width") / view.width
        assertTrue("Radar controls must stay onscreen: ${state.get()}, height=$visibleHeight",
            state.get().getDouble("panelTop") >= 0 && state.get().getDouble("panelBottom") <= visibleHeight + 1)
        assertTimelineVisible()
        assertEquals(visibleHeight <= 400, state.get().getBoolean("short"))
        compose.runOnIdle { fullscreen.value = false }
        compose.waitUntil(10_000) {
            readState()
            val height = view.height.toDouble() * state.get().optDouble("width") / view.width
            state.get().optDouble("panelTop") >= 0 && state.get().optDouble("panelBottom") <= height + 1
        }
        assertTimelineVisible()
        compose.runOnIdle { fullscreen.value = true }
        compose.waitUntil(10_000) {
            readState()
            state.get().optDouble("panelBottom") > visibleHeight - 20 && state.get().optDouble("panelBottom") <= visibleHeight + 1
        }
        assertTimelineVisible()
        assertEquals("https://appassets.androidplatform.net", state.get().getString("origin"))
        assertEquals("observed", state.get().getString("type"))
        assertFalse("Timeline must not need a separate source mode", state.get().getBoolean("modeButtons"))
        assertEquals(state.get().getLong("displayedTime"), state.get().getLong("sliderTime"))
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
        }
        compose.waitUntil(30_000) { readState(); state.get().optInt("forecastCount") > 0 }
        assertEquals(state.get().getLong("firstTime"), state.get().getLong("sliderMin"))
        assertEquals(state.get().getLong("lastTime"), state.get().getLong("sliderMax"))
        compose.runOnIdle {
            view.evaluateJavascript("""
                var slider = document.getElementById('slider');
                slider.value = frames.find(function(frame) { return frame.type === 'forecast'; }).time;
                slider.dispatchEvent(new Event('input'));
            """.trimIndent(), null)
        }
        compose.waitUntil(30_000) {
            readState()
            state.get().optString("type") == "forecast" && state.get().optBoolean("ready") &&
                !state.get().optBoolean("pending")
        }
        assertEquals(state.get().getLong("displayedTime"), state.get().getLong("sliderTime"))
        assertTrue(state.get().getString("time").contains("–"))
        assertEquals("", state.get().getString("status"))
        assertTrue("Controls must not cover each other: ${state.get()}",
            state.get().getDouble("panelTop") >= state.get().getDouble("toolsBottom") &&
                state.get().getDouble("panelTop") >= state.get().getDouble("creditsBottom"))
        assertTimelineVisible()
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
                      slider.value = frames[i].time;
                      slider.dispatchEvent(new Event('input'));
                    }
                """.trimIndent(), null)
            }
            val report = AtomicReference<String?>(null)
            compose.waitUntil(30_000) {
                compose.runOnIdle {
                    view.evaluateJavascript("""
                        !pendingLayer && radarLayer && radarLayer.frame === frames[frames.length - 1] &&
                          Number(document.getElementById('slider').value) === radarLayer.frame.time ?
                          JSON.stringify({layers:radarProfile.layers, tiles:radarProfile.tiles,
                            tileSize:radarLayer.options.tileSize, status:document.getElementById('status').textContent}) : null
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
        compose.runOnIdle {
            view.evaluateJavascript("var slider=document.getElementById('slider');slider.value=slider.max;slider.dispatchEvent(new Event('input'))", null)
        }
        compose.waitUntil(30_000) {
            readState()
            state.get().optBoolean("ready") && !state.get().optBoolean("pending") &&
                state.get().optInt("frame") == state.get().optInt("count") - 1
        }
        assertEquals(state.get().getLong("lastTime"), state.get().getLong("displayedTime"))
        assertEquals(state.get().getLong("displayedTime"), state.get().getLong("sliderTime"))
        assertEquals("", state.get().getString("status"))
        assertEquals("eu", state.get().getString("model"))
        compose.runOnIdle { view.evaluateJavascript("map.setView([35.6762,139.6503],6)", null) }
        compose.waitUntil(30_000) {
            readState()
            state.get().optString("model") == "global" && state.get().optString("type") == "forecast" &&
                state.get().optBoolean("ready") && !state.get().optBoolean("pending")
        }
        assertTrue(state.get().getDouble("horizon") >= 48.0)
        compose.runOnIdle {
            view.evaluateJavascript("var slider=document.getElementById('slider');slider.value=slider.max;slider.dispatchEvent(new Event('input'))", null)
        }
        compose.waitUntil(30_000) {
            readState()
            state.get().optBoolean("ready") && !state.get().optBoolean("pending") &&
                state.get().optInt("frame") == state.get().optInt("count") - 1
        }
        assertEquals(state.get().getLong("lastTime"), state.get().getLong("displayedTime"))
        assertEquals(state.get().getLong("displayedTime"), state.get().getLong("sliderTime"))
        assertEquals("", state.get().getString("status"))
        compose.runOnIdle {
            view.evaluateJavascript("""
                var slider = document.getElementById('slider');
                slider.value = observedCache.frames[observedCache.frames.length - 1].time;
                slider.dispatchEvent(new Event('input'));
            """.trimIndent(), null)
        }
        compose.waitUntil(30_000) {
            readState()
            state.get().optString("type") == "observed" && state.get().optBoolean("ready") &&
                !state.get().optBoolean("pending")
        }
        assertEquals(state.get().getLong("displayedTime"), state.get().getLong("sliderTime"))
        assertEquals("", state.get().getString("status"))
        val selectedTime = state.get().getLong("displayedTime")
        val selectedLabel = state.get().getString("time")
        compose.runOnIdle { view.evaluateJavascript("document.getElementById('details-toggle').click()", null) }
        compose.waitUntil(10_000) { readState(); state.get().optBoolean("detailsOpen") }
        val details = state.get().getJSONObject("detailsRect")
        val nativeHeight = view.height.toDouble() * state.get().getDouble("width") / view.width
        assertTrue("Details must stay onscreen: ${state.get()}",
            details.getDouble("width") > 0 && details.getDouble("height") > 0 &&
                details.getDouble("top") >= state.get().getDouble("creditsBottom") &&
                details.getDouble("bottom") <= nativeHeight + 1 &&
                details.getDouble("left") >= 0 && details.getDouble("right") <= state.get().getDouble("width") + 1)
        if (visibleHeight <= 400) assertTrue("Short viewport details must replace the timeline", state.get().getBoolean("detailsOverPanel"))
        assertEquals(!state.get().getBoolean("detailsOverPanel"), state.get().getJSONObject("geometry").getBoolean("visible"))
        assertEquals(selectedTime, state.get().getLong("displayedTime"))
        assertEquals(selectedTime, state.get().getLong("sliderTime"))
        assertTrue("Details must retain the selected timestamp", state.get().getString("summary").contains(selectedLabel))
        compose.runOnIdle { view.evaluateJavascript("document.getElementById('details-toggle').click()", null) }
        compose.waitUntil(10_000) {
            readState()
            !state.get().optBoolean("detailsOpen") && state.get().getJSONObject("geometry").getBoolean("visible")
        }
        assertTimelineVisible()
        assertEquals(selectedTime, state.get().getLong("displayedTime"))
        compose.runOnIdle {
            assertTrue("WebView must receive keyboard focus", view.requestFocus())
            view.evaluateJavascript("document.getElementById('slider').focus()", null)
        }
        compose.waitUntil(10_000) { readState(); state.get().optBoolean("sliderFocused") }
        val keyboardTargets = listOf(
            KeyEvent.KEYCODE_MOVE_HOME to state.get().getLong("firstTime"),
            KeyEvent.KEYCODE_DPAD_RIGHT to state.get().getLong("secondTime"),
            KeyEvent.KEYCODE_MOVE_END to state.get().getLong("lastTime"),
        )
        for ((key, expectedTime) in keyboardTargets) {
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(key)
            compose.waitUntil(30_000) {
                readState()
                state.get().optBoolean("ready") && !state.get().optBoolean("pending") &&
                    state.get().optLong("displayedTime") == expectedTime
            }
            assertEquals(expectedTime, state.get().getLong("sliderTime"))
            assertEquals("", state.get().getString("status"))
            assertTimelineVisible()
        }
    }

    private fun findWebView(view: View): WebView? = when (view) {
        is WebView -> view
        is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { findWebView(view.getChildAt(it)) }
        else -> null
    }
}
