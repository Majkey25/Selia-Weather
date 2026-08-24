package cz.majkey.pocasicesko.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class RadarScreenTest {
    @Test
    fun localizedRadarUrlUsesSupportedLanguageOrEnglishFallback() {
        assertEquals("file:///android_asset/radar.html?lang=cs", localizedRadarUrl("cs-CZ"))
        assertEquals("file:///android_asset/radar.html?lang=fr", localizedRadarUrl("fr-FR"))
        assertEquals("file:///android_asset/radar.html?lang=en", localizedRadarUrl("pl-PL"))
        assertEquals("file:///android_asset/radar.html?lang=en", localizedRadarUrl(null))
    }
}
