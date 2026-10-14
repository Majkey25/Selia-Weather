package cz.majkey.pocasicesko.ui

import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.ComposeTimeoutException
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
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Network smoke test on the device's real WebView, not the desktop browser. */
class RadarWebViewTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun secureAssetOriginLoadsObservedAndForecastFrames() {
        val arguments = InstrumentationRegistry.getArguments()
        val testHeight = arguments.getString("radarHeightDp")?.toIntOrNull()?.takeIf { it in 300..800 }
        val testFont = arguments.getString("radarFontScale")?.toFloatOrNull()?.takeIf { it in 1f..2.5f }
        val incompleteRun = arguments.getString("incompleteRadarRun") == "true"
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
            val received = CountDownLatch(1)
            compose.runOnIdle {
                view.evaluateJavascript(
                    "JSON.stringify({origin:location.origin," +
                        "time:document.getElementById('time')?.textContent," +
                        "ready:typeof radarLayer!=='undefined'&&!!radarLayer&&radarLayer.ready&&!radarLayer.failed&&radarLayer.contentSeen&&map.isSourceLoaded(radarLayer.id)," +
                        "sourceId:typeof radarLayer==='undefined'||!radarLayer?'':radarLayer.id," +
                        "retried:typeof radarLayer!=='undefined'&&!!radarLayer&&radarLayer.retried===true," +
                        "reference:typeof radarLayer==='undefined'||!radarLayer?'':radarLayer.frame.reference," +
                        "playing:timer!==null&&document.getElementById('play').getAttribute('aria-pressed')==='true'," +
                        "playLabel:document.getElementById('play').getAttribute('aria-label')," +
                        "transitionDuration:transitionDuration," +
                        "rendered:window.radarPlaybackProbe||null," +
                        "errors:window.radarErrors||[]," +
                        "events:window.radarSourceEvents||[]," +
                        "responses:window.radarResponses||[]," +
                        "decodeProbe:window.radarDecodeProbe||null," +
                        "pendingState:typeof pendingLayer==='undefined'||!pendingLayer?null:{id:pendingLayer.id,ready:pendingLayer.ready,failed:pendingLayer.failed,contentSeen:pendingLayer.contentSeen,loaded:map.isSourceLoaded(pendingLayer.id)}," +
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
                                "visible:getComputedStyle(panel).visibility==='visible'&&getComputedStyle(panel).display!=='none'," +
                                "controls:['play','slider'].map(function(id){" +
                                    "var element=document.getElementById(id),bounds=element.getBoundingClientRect(),style=getComputedStyle(element);" +
                                    "return {id:id,rect:bounds,visible:style.visibility==='visible'&&style.display!=='none'," +
                                        "hit:element.contains(document.elementFromPoint(bounds.left+bounds.width/2,bounds.top+bounds.height/2))};" +
                                "})};" +
                        "})()," +
                        "detailsOpen:!document.getElementById('details').hidden," +
                        "detailsRect:document.getElementById('details').getBoundingClientRect()," +
                        "summary:document.getElementById('frame-summary').textContent," +
                        "modeButtons:!!document.getElementById('observed')&&!!document.getElementById('forecast')," +
                        "horizon:typeof frames==='undefined'||!frames.length?0:(frames[frames.length-1].time-Date.now()/1000)/3600," +
                        "model:typeof forecastCache==='undefined'||!forecastCache?'':forecastCache.key," +
                        "width:innerWidth,panelTop:document.querySelector('.panel').getBoundingClientRect().top," +
                        "panelBottom:document.querySelector('.panel').getBoundingClientRect().bottom," +
                        "toolsBottom:document.getElementById('radar-tools').getBoundingClientRect().bottom," +
                        "creditsBottom:document.querySelector('.maplibregl-ctrl-attrib')?.getBoundingClientRect().bottom||0," +
                        "mapRect:document.getElementById('map').getBoundingClientRect()," +
                        "mode:typeof mode==='undefined'?'':mode," +
                        "sourceCount:map?Object.keys(map.getStyle().sources).filter(function(id){return id.startsWith('frame-');}).length:0," +
                        "short:document.body.classList.contains('short')," +
                        "pending:typeof pendingLayer!=='undefined'&&(!!pendingLayer||seekTimer!==null)," +
                        "status:document.getElementById('status')?.textContent})",
                ) { result ->
                        val json = JSONTokener(result).nextValue() as? String
                        state.set(json?.let(::JSONObject) ?: JSONObject())
                        received.countDown()
                }
            }
            assertTrue("WebView state callback did not arrive", received.await(5, TimeUnit.SECONDS))
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
        fun assertPlaybackAndPause() {
            val playLabel = state.get().getString("playLabel")
            assertTrue(playLabel.isNotBlank())
            compose.runOnIdle {
                view.evaluateJavascript("""
                    window.radarPlaybackProbe = null;
                    if (!window.radarPlaybackListener) {
                      window.radarPlaybackListener = function() {
                        if (!radarLayer || !radarLayer.ready || radarLayer.failed || !map.isSourceLoaded(radarLayer.id)) return;
                        var source = map.getSource(radarLayer.id), layer = map.getLayer(radarLayer.id);
                        if (!source || !layer || layer.type !== 'raster' || layer.source !== radarLayer.id ||
                            map.getPaintProperty(radarLayer.id, 'raster-opacity') <= 0) return;
                        var previous = window.radarPlaybackProbe;
                        var same = previous && previous.id === radarLayer.id, now = performance.now();
                        var started = same ? previous.started : now;
                        window.radarPlaybackProbe = {id:radarLayer.id, time:radarLayer.frame.time,
                          url:map.getStyle().sources[radarLayer.id].tiles[0],
                          expectedUrl:radarLayer.frame.type === 'forecast' ? forecastTileUrl(radarLayer.frame) : radarTileUrl(radarLayer.frame),
                          started:started, lastAt:now,
                          maximumGapMs:same && now - started <= transitionDuration ?
                            Math.max(previous.maximumGapMs, now - previous.lastAt) : same ? previous.maximumGapMs : 0,
                          renders:same ? previous.renders + 1 : 1};
                      };
                      map.on('render', window.radarPlaybackListener);
                    }
                    document.getElementById('play').click();
                """.trimIndent(), null)
            }
            val expected = listOf(state.get().getLong("firstTime"), state.get().getLong("secondTime"))
            val initialSource = state.get().getString("sourceId")
            for (time in expected) {
                compose.waitUntil(30_000) {
                    readState()
                    val rendered = state.get().optJSONObject("rendered")
                    state.get().optBoolean("playing") && state.get().optBoolean("ready") &&
                        !state.get().optBoolean("pending") && state.get().optLong("displayedTime") == time &&
                        rendered?.optLong("time") == time && rendered.optString("id") == state.get().optString("sourceId") &&
                        rendered.optInt("renders") >= if (state.get().optInt("transitionDuration") == 0) 1 else 3
                }
                assertEquals(time, state.get().getLong("sliderTime"))
                assertEquals(playLabel, state.get().getString("playLabel"))
                assertTrue(state.get().getString("sourceId") != initialSource)
                val rendered = state.get().getJSONObject("rendered")
                assertEquals(rendered.getString("expectedUrl"), rendered.getString("url"))
                InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                    putString("stream", "\nRADAR_RENDER ${state.get().getString("mode")} time=$time renders=${rendered.getInt("renders")} maximumTransitionGapMs=${rendered.getDouble("maximumGapMs")}\n")
                })
                assertEquals("", state.get().getString("status"))
                assertTrue("Weather sources must remain bounded during playback", state.get().getInt("sourceCount") <= 4)
            }
            compose.runOnIdle { view.evaluateJavascript("document.getElementById('play').click()", null) }
            compose.waitUntil(30_000) { readState(); !state.get().optBoolean("playing") && !state.get().optBoolean("pending") }
            val pausedTime = state.get().getLong("displayedTime")
            assertEquals(playLabel, state.get().getString("playLabel"))
            val pausedSource = state.get().getString("sourceId")
            val pausedAt = SystemClock.elapsedRealtime()
            compose.waitUntil(5_000) {
                readState()
                assertFalse(state.get().getBoolean("playing"))
                assertEquals(pausedTime, state.get().getLong("displayedTime"))
                assertEquals(pausedSource, state.get().getString("sourceId"))
                SystemClock.elapsedRealtime() - pausedAt >= 2_200
            }
            InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                putString("stream", "\nRADAR_PLAY ${state.get().getString("mode")} wrapped, rendered two loaded raster frames; pause held 2200ms\n")
            })
        }
        try {
            compose.waitUntil(30_000) {
                readState()
                state.get().optBoolean("ready") && !state.get().optBoolean("pending") &&
                    state.get().optString("type") == "forecast"
            }
        } catch (failure: ComposeTimeoutException) {
            throw AssertionError("Initial forecast raster did not load: ${state.get()}", failure)
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
        assertEquals("forecast", state.get().getString("type"))
        assertEquals("forecast", state.get().getString("mode"))
        assertTrue("Forecast and observations must be separate modes", state.get().getBoolean("modeButtons"))
        assertEquals(state.get().getLong("displayedTime"), state.get().getLong("sliderTime"))
        val profiling = InstrumentationRegistry.getArguments().getString("profileRadar") == "true"
        val forecastStarted = SystemClock.elapsedRealtime()
        compose.runOnIdle {
            assertFalse(view.settings.allowFileAccess)
            if (profiling) view.evaluateJavascript("""
                window.radarProfile = {layers:0, tiles:0, peakWeatherLayers:0};
                function retainedWeatherLayers() {
                  return Object.keys(map.getStyle().sources).filter(function(id) { return id.startsWith('frame-'); }).length;
                }
                map.on('sourcedataloading', function(event) {
                  if (event.sourceId && event.sourceId.startsWith('frame-')) radarProfile.tiles++;
                  radarProfile.peakWeatherLayers = Math.max(radarProfile.peakWeatherLayers, retainedWeatherLayers());
                });
                var originalFrame = newFrameLayer;
                newFrameLayer = function(index) {
                  var layer = originalFrame(index);
                  radarProfile.layers++;
                  return layer;
                };
            """.trimIndent(), null)
        }
        compose.waitUntil(30_000) { readState(); state.get().optInt("forecastCount") > 0 }
        assertTrue(state.get().getLong("sliderMin") <= state.get().getLong("firstTime"))
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
        assertTrue("Weather sources must remain bounded", state.get().getInt("sourceCount") <= 4)
        assertTrue("Map must retain useful height: ${state.get()}", state.get().getJSONObject("mapRect").getDouble("height") >= 72)
        assertTrue("Forecast must actually cover +48h: ${state.get()}", state.get().getDouble("horizon") >= 48.0)
        if (profiling) {
            InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                putString("stream", "\nRADAR_LOAD elapsedMs=${SystemClock.elapsedRealtime() - forecastStarted}\n")
            })
            val started = SystemClock.elapsedRealtime()
            compose.runOnIdle {
                view.evaluateJavascript("""
                    radarProfile.layers = radarProfile.tiles = 0;
                    radarProfile.peakWeatherLayers = retainedWeatherLayers();
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
                            retainedWeatherLayers:retainedWeatherLayers(), peakWeatherLayers:radarProfile.peakWeatherLayers,
                            status:document.getElementById('status').textContent}) : null
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
        val sourceBeforeDecode = state.get().getString("sourceId")
        compose.runOnIdle {
            view.evaluateJavascript("""
                window.radarDecodeProbe = {injected:0,reloadRequests:0,errors:0,events:[],reference:forecastCache.frames[0].reference,
                  previousRun:forecastCache.previousRun,lastInjectedSource:null};
                var healthyFetch = window.fetch;
                window.fetch = function(input, options) {
                  var url = typeof input === 'string' ? input : input.url;
                  if (url.startsWith(RadarForecast.endpoint + '?') && pendingLayer) {
                    if (radarDecodeProbe.injected < ${if (incompleteRun) 2 else 1} &&
                        radarDecodeProbe.lastInjectedSource !== pendingLayer.id && pendingLayer.frame.reference === radarDecodeProbe.reference) {
                      radarDecodeProbe.injected++;
                      radarDecodeProbe.lastInjectedSource = pendingLayer.id;
                      return Promise.resolve(new Response(new Uint8Array([0,1,2]),
                        {status:200,headers:{'Content-Type':'image/png'}}));
                    }
                    if ((input.cache || (options && options.cache)) === 'reload') radarDecodeProbe.reloadRequests++;
                  }
                  return healthyFetch(input, options);
                };
                function countDecodeError(event) {
                  if (event.error && event.error.message === 'The source image could not be decoded.') radarDecodeProbe.errors++;
                  radarDecodeProbe.events.push({type:'error',id:event.sourceId,message:String(event.error && event.error.message)});
                }
                function recordDecodeProgress(event) {
                  if (!event.sourceId || !event.sourceId.startsWith('frame-')) return;
                  radarDecodeProbe.events.push({type:'data',id:event.sourceId,tile:!!event.tile,loaded:event.isSourceLoaded,
                    pending:pendingLayer && pendingLayer.id});
                  if (radarDecodeProbe.events.length > 32) radarDecodeProbe.events.shift();
                }
                map.on('error', countDecodeError);
                map.on('sourcedata', recordDecodeProgress);
                renderFrame(0, true);
            """.trimIndent(), null)
        }
        try {
            compose.waitUntil(30_000) {
                readState()
                state.get().optBoolean("ready") && !state.get().optBoolean("pending") &&
                    state.get().optString("sourceId") != sourceBeforeDecode &&
                    (if (incompleteRun) state.get().optJSONObject("decodeProbe")?.let { probe ->
                        probe.has("previousRun") && !probe.isNull("previousRun") &&
                            Instant.parse(state.get().getString("reference")).epochSecond == probe.getLong("previousRun")
                    } == true else state.get().optBoolean("retried")) &&
                    state.get().optJSONObject("decodeProbe")?.optInt("reloadRequests", 0)?.let { it > 0 } == true
            }
        } catch (failure: ComposeTimeoutException) {
            throw AssertionError("Forecast did not recover from ${if (incompleteRun) "an incomplete run" else "one invalid image"}: ${state.get()}", failure)
        }
        assertEquals(if (incompleteRun) 2 else 1, state.get().getJSONObject("decodeProbe").getInt("injected"))
        assertEquals(if (incompleteRun) 2 else 1, state.get().getJSONObject("decodeProbe").getInt("errors"))
        assertEquals("", state.get().getString("status"))
        compose.runOnIdle { view.evaluateJavascript("window.fetch=healthyFetch;map.off('error',countDecodeError);map.off('sourcedata',recordDecodeProgress)", null) }
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
            putString("stream", "\nRADAR_RECOVERY ${if (incompleteRun) "two injected invalid images; previous advertised run ${state.get().getString("reference")} loaded" else "one injected invalid PNG, one decode error, uncached retry loaded"}\n")
        })
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
        assertPlaybackAndPause()
        compose.runOnIdle { view.evaluateJavascript("""
            window.radarErrors = [];
            window.radarSourceEvents = [];
            window.radarResponses = [];
            var originalFetch = window.fetch;
            window.fetch = function(input, options) {
              return originalFetch(input, options).then(function(response) {
                var url = typeof input === 'string' ? input : input.url;
                if (url.startsWith('https://maps.dwd.de/') && url.includes('request=GetMap')) {
                  var entry = {url:url,status:response.status,type:response.headers.get('Content-Type')};
                  window.radarResponses.push(entry);
                  if (window.radarResponses.length > 32) window.radarResponses.shift();
                  response.clone().arrayBuffer().then(function(buffer) {
                    entry.length = buffer.byteLength;
                    entry.signature = Array.from(new Uint8Array(buffer, 0, Math.min(16, buffer.byteLength)));
                    if (!entry.type || !entry.type.startsWith('image/')) entry.text = new TextDecoder().decode(buffer).slice(0,1000);
                  }).catch(function(error) { entry.error = String(error); });
                }
                return response;
              });
            };
            ['sourcedataloading','sourcedata'].forEach(function(type) {
              map.on(type, function(event) {
                if (!event.sourceId || !event.sourceId.startsWith('frame-')) return;
                window.radarSourceEvents.push({at:performance.now(),type:type,sourceId:event.sourceId,
                  dataType:event.sourceDataType,loaded:event.isSourceLoaded,tile:!!event.tile});
                if (window.radarSourceEvents.length > 48) window.radarSourceEvents.shift();
              });
            });
            map.on('error', function(event) {
              var tile = event.tile && event.tile.tileID && event.tile.tileID.canonical;
              window.radarErrors.push({sourceId:event.sourceId, message:String(event.error && event.error.message),
                status:event.error && event.error.status,url:event.error && event.error.url,
                coordinate:tile ? {z:tile.z,x:tile.x,y:tile.y} : null});
              if (window.radarErrors.length > 16) window.radarErrors.shift();
            });
            map.jumpTo({center:[139.6503,35.6762],zoom:6});
        """.trimIndent(), null) }
        try {
            compose.waitUntil(30_000) {
                readState()
                state.get().optString("model") == "global" && state.get().optString("type") == "forecast" &&
                    state.get().optBoolean("ready") && !state.get().optBoolean("pending")
            }
        } catch (failure: ComposeTimeoutException) {
            throw AssertionError("Tokyo forecast raster did not load: ${state.get()}", failure)
        }
        assertTrue(state.get().getDouble("horizon") >= 48.0)
        compose.runOnIdle {
            view.evaluateJavascript("var slider=document.getElementById('slider');slider.value=slider.max;slider.dispatchEvent(new Event('input'))", null)
        }
        try {
            compose.waitUntil(30_000) {
                readState()
                state.get().optBoolean("ready") && !state.get().optBoolean("pending") &&
                    state.get().optInt("frame") == state.get().optInt("count") - 1
            }
        } catch (failure: ComposeTimeoutException) {
            throw AssertionError("Last global forecast raster did not load: ${state.get()}", failure)
        }
        assertEquals(state.get().getLong("lastTime"), state.get().getLong("displayedTime"))
        assertEquals(state.get().getLong("displayedTime"), state.get().getLong("sliderTime"))
        assertEquals("", state.get().getString("status"))
        assertPlaybackAndPause()
        for ((place, coordinates) in listOf("Sydney" to "[151.2093,-33.8688]",
            "Nairobi" to "[36.8219,-1.2921]", "Lima" to "[-77.0428,-12.0464]")) {
            compose.runOnIdle {
                view.evaluateJavascript("map.jumpTo({center:$coordinates,zoom:6});showFrame(0)", null)
            }
            for (last in listOf(false, true)) {
                if (last) compose.runOnIdle {
                    view.evaluateJavascript("showFrame(frames.length-1)", null)
                }
                try {
                    compose.waitUntil(30_000) {
                        readState()
                        state.get().optString("model") == "global" && state.get().optBoolean("ready") &&
                            !state.get().optBoolean("pending") && state.get().optInt("frame") ==
                            if (last) state.get().optInt("count") - 1 else 0
                    }
                } catch (failure: ComposeTimeoutException) {
                    throw AssertionError("$place forecast last=$last did not load: ${state.get()}", failure)
                }
                assertEquals("", state.get().getString("status"))
                assertTrue(state.get().getInt("sourceCount") <= 4)
                assertTrue(state.get().getDouble("horizon") >= 48.0)
            }
            InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                putString("stream", "\nRADAR_GLOBAL $place first and +48h frame loaded\n")
            })
        }
        compose.runOnIdle { view.evaluateJavascript("document.getElementById('play').click()", null) }
        compose.waitUntil(10_000) { readState(); state.get().optBoolean("playing") }
        compose.runOnIdle {
            view.evaluateJavascript("""
                document.getElementById('observed').click();
            """.trimIndent(), null)
        }
        compose.waitUntil(30_000) {
            readState()
            state.get().optString("type") == "observed" && state.get().optBoolean("ready") &&
                !state.get().optBoolean("pending")
        }
        assertEquals(state.get().getLong("displayedTime"), state.get().getLong("sliderTime"))
        assertEquals("", state.get().getString("status"))
        assertFalse("Changing radar mode must stop playback", state.get().getBoolean("playing"))
        val selectedTime = state.get().getLong("displayedTime")
        val selectedLabel = state.get().getString("time")
        compose.runOnIdle { view.evaluateJavascript("document.getElementById('details-toggle').click()", null) }
        compose.waitUntil(10_000) { readState(); state.get().optBoolean("detailsOpen") }
        val details = state.get().getJSONObject("detailsRect")
        val nativeHeight = view.height.toDouble() * state.get().getDouble("width") / view.width
        assertTrue("Details must stay onscreen: ${state.get()}",
            details.getDouble("width") > 0 && details.getDouble("height") > 0 &&
                details.getDouble("top") >= state.get().getDouble("toolsBottom") &&
                details.getDouble("bottom") <= nativeHeight + 1 &&
                details.getDouble("left") >= 0 && details.getDouble("right") <= state.get().getDouble("width") + 1)
        assertFalse("Details replace the timeline", state.get().getJSONObject("geometry").getBoolean("visible"))
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
        assertEquals("observed", state.get().getString("mode"))
        assertTrue("Weather sources must remain bounded", state.get().getInt("sourceCount") <= 4)
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
        assertPlaybackAndPause()
    }

    private fun findWebView(view: View): WebView? = when (view) {
        is WebView -> view
        is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { findWebView(view.getChildAt(it)) }
        else -> null
    }
}
