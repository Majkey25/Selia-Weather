package cz.majkey.pocasicesko.ui

import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
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
        compose.setContent { ChmiWebScreen(localizedRadarUrl("en", 50.0755, 14.4378, true, "Europe/Prague")) }
        val view = compose.runOnIdle { findWebView(compose.activity.window.decorView)!! }
        val state = AtomicReference(JSONObject())
        fun readState() {
            compose.runOnIdle {
                view.evaluateJavascript(
                    "JSON.stringify({origin:location.origin, mode:typeof mode==='undefined'?'':mode," +
                        "time:document.getElementById('time')?.textContent," +
                        "ready:typeof radarLayer!=='undefined'&&!!radarLayer," +
                        "frame:typeof frameIndex==='undefined'?-1:frameIndex," +
                        "pending:typeof pendingLayer!=='undefined'&&!!pendingLayer," +
                        "status:document.getElementById('status')?.textContent})",
                ) { result ->
                    (JSONTokener(result).nextValue() as? String)?.let { state.set(JSONObject(it)) }
                }
            }
        }
        compose.waitUntil(30_000) { readState(); state.get().optBoolean("ready") }
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
    }

    private fun findWebView(view: View): WebView? = when (view) {
        is WebView -> view
        is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { findWebView(view.getChildAt(it)) }
        else -> null
    }
}
