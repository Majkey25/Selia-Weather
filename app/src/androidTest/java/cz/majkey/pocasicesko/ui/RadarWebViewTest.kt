package cz.majkey.pocasicesko.ui

import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
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
                        "status:document.getElementById('status')?.textContent})",
                ) { result ->
                    (JSONTokener(result).nextValue() as? String)?.let { state.set(JSONObject(it)) }
                }
            }
        }
        compose.waitUntil(30_000) { readState(); state.get().optBoolean("ready") }
        assertEquals("https://appassets.androidplatform.net", state.get().getString("origin"))
        assertEquals("observed", state.get().getString("mode"))
        compose.runOnIdle {
            assertFalse(view.settings.allowFileAccess)
            view.evaluateJavascript("document.getElementById('forecast').click()", null)
        }
        compose.waitUntil(30_000) {
            readState()
            state.get().optString("mode") == "forecast" && state.get().optBoolean("ready")
        }
        assertTrue(state.get().getString("time").contains("–"))
        assertEquals("", state.get().getString("status"))
    }

    private fun findWebView(view: View): WebView? = when (view) {
        is WebView -> view
        is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { findWebView(view.getChildAt(it)) }
        else -> null
    }
}
