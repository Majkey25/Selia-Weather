package cz.majkey.pocasicesko.ui

import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RadarScreenTest {
    @Test
    fun appearanceBoundsNativeInsetsAndKeepsThemeColors() {
        val script = radarAppearanceScript(darkColorScheme(primary = Color(0xFF123456)), -1, 300)
        val values = JSONObject(script.substringAfter("window.setAppearance(").substringBeforeLast(");"))

        assertEquals(0, values.getInt("topInset"))
        assertEquals(240, values.getInt("bottomInset"))
        assertEquals("#123456", values.getString("accent"))
    }

    @Test
    fun appearanceCarriesNullableTimezoneAsJson() {
        val timezone = "Europe/Prague"
        val script = radarAppearanceScript(darkColorScheme(), 64, 76, timezone)
        val values = JSONObject(script.substringAfter("window.setAppearance(").substringBeforeLast(");"))
        assertEquals(timezone, values.getString("timezone"))

        val unknown = radarAppearanceScript(darkColorScheme(), 64, 76, null)
        val unknownValues = JSONObject(unknown.substringAfter("window.setAppearance(").substringBeforeLast(");"))
        assertTrue(unknownValues.isNull("timezone"))
    }

    @Test
    fun localizedRadarUrlUsesSupportedLanguageOrEnglishFallback() {
        assertEquals(
            "https://appassets.androidplatform.net/assets/radar.html?lang=cs&lat=50.0755&lon=14.4378&chmi=1",
            localizedRadarUrl("cs-CZ", 50.0755, 14.4378, true),
        )
        assertEquals(
            "https://appassets.androidplatform.net/assets/radar.html?lang=fr&lat=-1.2921&lon=36.8219&chmi=0",
            localizedRadarUrl("fr-FR", -1.2921, 36.8219, false),
        )
        assertEquals(
            "https://appassets.androidplatform.net/assets/radar.html?lang=en&lat=35.6762&lon=139.6503&chmi=0",
            localizedRadarUrl("pl-PL", 35.6762, 139.6503, false),
        )
    }

    @Test
    fun radarSeparatesObservedFramesFromModelForecast() {
        val asset = File(System.getProperty("user.dir"), "src/main/assets/radar.html")
        assertTrue("Missing radar asset: ${asset.absolutePath}", asset.isFile)
        val source = asset.readText()

        assertTrue(source.contains("https://api.rainviewer.com/public/weather-maps.json"))
        assertTrue(source.contains("manifest.radar.past"))
        assertTrue(source.contains("/v2/coverage/0/"))
        assertTrue(source.contains("#map { flex: 1 1 auto; min-height: 0"))
        assertTrue(source.contains("document.documentElement.style.height = height + 'px'"))
        assertTrue(source.contains(".panel, .details { flex: 0 1 auto; min-height: 0"))
        assertFalse(source.contains(".panel, .details { position: absolute"))
        assertFalse(source.contains("getElementById('map').style.height ="))
        assertTrue(source.contains("new maplibre.Map("))
        assertTrue(source.contains("center: [longitude, latitude], zoom: 6"))
        assertTrue(source.contains("maplibre-6.14.0/maplibre-gl.js"))
        assertTrue(source.contains("maplibre.setWorkerUrl("))
        assertFalse(source.contains("L.map("))
        assertTrue(source.contains("https://tile.openstreetmap.org/{z}/{x}/{y}.png"))
        assertTrue(source.contains("RainViewer"))
        assertTrue(source.contains("radar-forecast.js"))
        assertTrue(source.contains("https://maps.dwd.de"))
        assertFalse(source.contains("radar.nowcast"))
        assertFalse(source.contains("satellite.infrared"))
        assertFalse(source.contains("czrad-z_max3d_fct_masked"))
    }

    @Test
    fun radarAssetBoundsLayersAndRejectsStaleManifestResponses() {
        val asset = File(System.getProperty("user.dir"), "src/main/assets/radar.html")
        assertTrue("Missing radar asset: ${asset.absolutePath}", asset.isFile)
        val source = asset.readText().replace("\r\n", "\n")

        assertTrue(source.contains("var observedState = { request: null, token: 0"))
        assertTrue(source.contains("var forecastState = { request: null, token: 0"))
        assertTrue(source.contains("if (token !== state.token) return;"))
        assertTrue(source.contains("function clearRadarLayer()"))
        assertTrue(source.contains("if (map.getLayer(id)) map.removeLayer(id);"))
        assertTrue(source.contains("if (map.getSource(id)) map.removeSource(id);"))
        assertTrue(source.contains("var past = manifest.radar.past.slice(-MAX_FRAMES);"))
        assertTrue(source.contains("mode = 'forecast'"))
        assertTrue(source.contains("function setMode(next)"))
        assertTrue(source.contains("modeTimes[mode] = selectedTime;"))
        assertTrue(source.contains("var MAX_FRAMES = 20,"))
        assertTrue(source.contains("MAX_FRAME_AGE_SECONDS = 3600"))
        val observedValidity = source.substringAfter("function observedIsUsable(cached) {")
            .substringBefore("function forecastIsUsable(cached)")
        assertTrue(observedValidity.contains("cached.loadedAt <= now"))
        assertTrue(observedValidity.contains("cached.frames.length && now / 1000 - cached.frames[cached.frames.length - 1].time <= MAX_FRAME_AGE_SECONDS"))
        assertFalse(observedValidity.contains("REFRESH_INTERVAL_MS"))
        val forecastValidity = source.substringAfter("function forecastIsUsable(cached) {")
            .substringBefore("function refreshDue(cached)")
        assertTrue(forecastValidity.contains("cached.loadedAt <= now"))
        assertTrue(forecastValidity.contains("now / 1000 - cached.run <= 12 * 3600"))
        assertTrue(forecastValidity.contains("cached.frames.length >= 3 && cached.frames.length <= RadarForecast.maxFrames"))
        assertTrue(forecastValidity.contains("cached.frames[cached.frames.length - 1].time >= now / 1000 + 12 * 3600"))
        assertFalse(forecastValidity.contains("REFRESH_INTERVAL_MS"))
        val expiry = source.substringAfter("function ensureSourcesFresh(fetchMissing) {")
            .substringBefore("function commitFrame(layer)")
        assertTrue(expiry.contains("if (observedCache && !observedIsUsable(observedCache)) {\n        observedCache = null;"))
        assertTrue(expiry.contains("if (forecastModels[key] && !forecastIsUsable(forecastModels[key])) {\n          forecastModels[key] = null;"))
        assertTrue(source.contains("function pageActive() { return nativeActive && !document.hidden && styleReady; }"))
        assertTrue(source.contains("window.setNativeActive = function(active)"))
        assertTrue(source.contains("parameters.get('nativeActive') !== '0'"))
        val hidden = source.substringAfter("function updateVisibility() {")
            .substringAfter("if (!pageActive()) {").substringBefore("} else {")
        assertTrue(hidden.contains("stopPlay();"))
        assertTrue(hidden.contains("clearPending();"))
        assertTrue(hidden.contains("clearRadarLayer(); removeSource('base');"))
        assertTrue(hidden.contains("clearTimeout(expiryTimer); expiryTimer = null;"))
        assertTrue(hidden.contains("cancelRequest(observedState); cancelRequest(forecastState);"))
        val activityHook = source.substringAfter("window.setNativeActive = function(active) {")
            .substringBefore("document.addEventListener('visibilitychange'")
        assertTrue(activityHook.contains("typeof active !== 'boolean'"))
        assertTrue(activityHook.contains("nativeActive = active; updateVisibility();"))
    }

    @Test
    fun interactiveRadarUsesAvailableHeightAboveNavigation() {
        val source = File(
            System.getProperty("user.dir"),
            "src/main/java/cz/majkey/pocasicesko/ui/MapHubScreen.kt",
        ).readText()

        assertTrue(source.contains(".fillMaxSize()"))
        assertFalse(source.contains(".aspectRatio("))
        assertFalse(source.contains("else 84.dp"))
        assertTrue(source.contains("bottomInsetDp = if (fullscreen) 0 else bottomInsetDp"))
        assertTrue(source.contains("modifier = Modifier.weight(1f)"))
        assertTrue(source.contains("topInsetDp = 0"))
    }

    @Test
    fun mapHubUsesClassicRadarWithoutTargetWidgets() {
        val source = File(
            System.getProperty("user.dir"),
            "src/main/java/cz/majkey/pocasicesko/ui/MapHubScreen.kt",
        ).readText()

        assertTrue(source.contains("ChmiWebScreen("))
        assertTrue(source.contains("localizedRadarUrl("))
        assertFalse(source.contains("MapMode"))
        assertFalse(source.contains("ForecastMap"))
        assertFalse(source.contains("LocalRainField("))
        assertFalse(source.contains("loadPrecipitationField"))
    }

    @Test
    fun fullscreenKeepsTheRadarCallSiteAndBackExitsBeforeNavigation() {
        val root = File(System.getProperty("user.dir"), "src/main/java/cz/majkey/pocasicesko/ui")
        val app = File(root, "WeatherApp.kt").readText()
        val hub = File(root, "MapHubScreen.kt").readText()
        assertTrue(app.contains("BackHandler(enabled = destination == Destination.MAPS && radarFullscreen)"))
        assertTrue(app.contains("if (!(destination == Destination.MAPS && radarFullscreen)) FloatingNavigation("))
        assertTrue(hub.contains("R.string.radar_exit_fullscreen"))
        assertEquals(1, "ChmiWebScreen(".toRegex(RegexOption.LITERAL).findAll(hub).count())
    }
}
