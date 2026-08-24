package cz.majkey.pocasicesko.ui

import org.junit.Assert.assertEquals
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
        assertTrue(source.contains("document.getElementById('lightning').style.display = '';"))
        assertTrue(source.contains("var lightning = lightningVisible ? lightningUrl(frame.date) : null;"))
        assertTrue(source.contains("layerStateSelfTest();"))
        assertTrue(source.contains("nowcast: 'nowcast'"))
    }
}
