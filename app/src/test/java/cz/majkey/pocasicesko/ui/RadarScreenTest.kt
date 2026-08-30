package cz.majkey.pocasicesko.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RadarScreenTest {
    @Test
    fun localizedRadarUrlUsesSupportedLanguageOrEnglishFallback() {
        assertEquals("file:///android_asset/radar.html?lang=cs", localizedRadarUrl("cs-CZ"))
        assertEquals("file:///android_asset/radar.html?lang=fr", localizedRadarUrl("fr-FR"))
        assertEquals("file:///android_asset/radar.html?lang=en", localizedRadarUrl("pl-PL"))
        assertEquals("file:///android_asset/radar.html?lang=en", localizedRadarUrl(null))
    }

    @Test
    fun radarAssetKeepsLightningIndependentFromBaseLayer() {
        val asset = File(System.getProperty("user.dir"), "src/main/assets/radar.html")
        assertTrue("Missing radar asset: ${asset.absolutePath}", asset.isFile)
        val source = asset.readText()

        assertTrue(source.contains("function layerVisibility(baseLayer, lightningVisible)"))
        assertTrue(source.contains("function layerStateSelfTest()"))
        assertTrue(source.contains("id=\"lightning\" class=\"active\" type=\"button\" aria-pressed=\"true\""))
        assertTrue(source.contains("lightningVisible ? 'active' : ''"))
        assertTrue(source.contains("setAttribute('aria-pressed', String(lightningVisible))"))
        assertTrue(source.contains("document.getElementById('lightning').style.display = '';"))
        assertTrue(source.contains("var lightning = lightningVisible ? lightningUrl(frame.date) : null;"))
        assertTrue(source.contains("#strikes { z-index: 1; pointer-events: none; }"))
        assertTrue(source.contains("layerStateSelfTest();"))
        assertTrue(source.contains("nowcast: 'nowcast'"))
    }

    @Test
    fun radarAssetGuardsImageRequestsAgainstStaleFrames() {
        val asset = File(System.getProperty("user.dir"), "src/main/assets/radar.html")
        assertTrue("Missing radar asset: ${asset.absolutePath}", asset.isFile)
        val source = asset.readText()

        assertTrue(source.contains("var requestToken = 0;"))
        assertTrue(source.contains("var radarSource = null;"))
        assertTrue(source.contains("function isActiveImageRequest(token, frame, source, activeSource)"))
        assertTrue(source.contains("function loadImage(source, onload, onerror)"))
        assertTrue(source.contains("function loadCurrentImage(source, token, frame, activeSource, onload, onerror)"))
        assertTrue(source.contains("function showRadar(frame, token, onerror)"))
        assertTrue(source.contains("function showSatellite(frame, token)"))
        assertTrue(source.contains("showRadar(frame, token, function() { setStatus(TEXT.radarUnavailable); });"))
        assertTrue(source.contains("showRadar(frame, token, function() { loadForecastFrame(frame, attempt + 1, token); });"))
        assertTrue(source.contains("hideImage(strikes);"))
        assertTrue(source.contains("hideImage(radar);"))
        assertTrue(source.contains("hideImage(satellite);"))
        assertTrue(!source.contains("id=\"satellite\" alt=\"ČHMÚ satellite image\" onerror="))
        assertTrue(!source.contains("radar.onload ="))
        assertTrue(!source.contains("radar.onerror ="))
        assertTrue(source.contains("imageRequestSelfTest();"))
    }

    @Test
    fun radarCropsChmiCrossSectionsToTheGeographicMap() {
        val source = File(System.getProperty("user.dir"), "src/main/assets/radar.html").readText()

        assertTrue(source.contains("#base, #radar, #strikes"))
        assertTrue(source.contains("class=\"radar-crop\""))
        assertTrue(source.contains("aspect-ratio: 600 / 380"))
        assertTrue(source.contains("width: 113.333%; height: 121.053%"))
        assertTrue(source.contains("inset: auto auto 0 0"))
    }

    @Test
    fun radarCardUsesImageAwareAspectRatioInsteadOfTallScreenWeight() {
        val source = File(
            System.getProperty("user.dir"),
            "src/main/java/cz/majkey/pocasicesko/ui/MapHubScreen.kt",
        ).readText()

        assertTrue(source.contains(".aspectRatio(RADAR_CARD_ASPECT_RATIO)"))
        assertTrue(source.contains("private const val RADAR_CARD_ASPECT_RATIO = 0.9f"))
        assertFalse(source.contains(".fillMaxHeight()"))
    }

    @Test
    fun mapHubSeparatesObservedRadarFrom24HourModelForecast() {
        val source = File(
            System.getProperty("user.dir"),
            "src/main/java/cz/majkey/pocasicesko/ui/MapHubScreen.kt",
        ).readText()

        assertTrue(source.contains("MapMode.OBSERVED"))
        assertTrue(source.contains("MapMode.FORECAST"))
        assertTrue(source.contains("loadPrecipitationField(location)"))
        assertTrue(source.contains("LocalRainField("))
        assertTrue(source.contains("R.string.radar_forecast_24h"))
        assertTrue(source.contains("current?.latitude == location.latitude"))
        assertTrue(source.contains("current.longitude == location.longitude"))
    }
}
