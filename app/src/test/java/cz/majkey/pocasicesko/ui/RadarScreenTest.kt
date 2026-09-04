package cz.majkey.pocasicesko.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RadarScreenTest {
    @Test
    fun localizedRadarUrlUsesSupportedLanguageOrEnglishFallback() {
        assertEquals(
            "file:///android_asset/radar.html?lang=cs&lat=50.0755&lon=14.4378&chmi=1",
            localizedRadarUrl("cs-CZ", 50.0755, 14.4378, true),
        )
        assertEquals(
            "file:///android_asset/radar.html?lang=fr&lat=-1.2921&lon=36.8219&chmi=0",
            localizedRadarUrl("fr-FR", -1.2921, 36.8219, false),
        )
        assertEquals(
            "file:///android_asset/radar.html?lang=en&lat=35.6762&lon=139.6503&chmi=0",
            localizedRadarUrl("pl-PL", 35.6762, 139.6503, false),
        )
    }

    @Test
    fun radarAssetUsesWorldwideObservedFramesOnly() {
        val asset = File(System.getProperty("user.dir"), "src/main/assets/radar.html")
        assertTrue("Missing radar asset: ${asset.absolutePath}", asset.isFile)
        val source = asset.readText()

        assertTrue(source.contains("https://api.rainviewer.com/public/weather-maps.json"))
        assertTrue(source.contains("manifest.radar.past"))
        assertTrue(source.contains("/v2/coverage/0/"))
        assertTrue(source.contains("height: 100vh"))
        assertTrue(source.contains("#map { position: fixed"))
        assertTrue(source.contains("style.height = Math.max(window.innerHeight, 1) + 'px'"))
        assertTrue(source.contains("L.map('map'"))
        assertTrue(source.contains("setView([latitude, longitude], 6)"))
        assertTrue(source.contains("https://tile.openstreetmap.org/{z}/{x}/{y}.png"))
        assertTrue(source.contains("RainViewer"))
        assertFalse(source.contains("radar.nowcast"))
        assertFalse(source.contains("satellite.infrared"))
        assertFalse(source.contains("czrad-z_max3d_fct_masked"))
    }

    @Test
    fun radarAssetBoundsLayersAndRejectsStaleManifestResponses() {
        val asset = File(System.getProperty("user.dir"), "src/main/assets/radar.html")
        assertTrue("Missing radar asset: ${asset.absolutePath}", asset.isFile)
        val source = asset.readText()

        assertTrue(source.contains("var requestToken = 0;"))
        assertTrue(source.contains("if (token !== requestToken) return;"))
        assertTrue(source.contains("function clearRadarLayer()"))
        assertTrue(source.contains("map.removeLayer(radarLayer)"))
        assertTrue(source.contains("frames = manifest.radar.past.slice(-MAX_FRAMES);"))
        assertTrue(source.contains("var MAX_FRAMES = 20;"))
    }

    @Test
    fun radarCardUsesImageAwareAspectRatioInsteadOfTallScreenWeight() {
        val source = File(
            System.getProperty("user.dir"),
            "src/main/java/cz/majkey/pocasicesko/ui/MapHubScreen.kt",
        ).readText()

        assertTrue(source.contains(".aspectRatio(RADAR_CARD_ASPECT_RATIO)"))
        assertTrue(source.contains("private const val RADAR_CARD_ASPECT_RATIO = 0.9f"))
    }

    @Test
    fun mapHubUsesOneClassicObservedRadarWithoutTargetGrid() {
        val source = File(
            System.getProperty("user.dir"),
            "src/main/java/cz/majkey/pocasicesko/ui/MapHubScreen.kt",
        ).readText()

        assertTrue(source.contains("ChmiWebScreen("))
        assertTrue(source.contains("localizedRadarUrl("))
        assertTrue(source.contains("R.string.radar_footer"))
        assertFalse(source.contains("MapMode"))
        assertFalse(source.contains("ForecastMap"))
        assertFalse(source.contains("LocalRainField("))
        assertFalse(source.contains("loadPrecipitationField"))
    }
}
