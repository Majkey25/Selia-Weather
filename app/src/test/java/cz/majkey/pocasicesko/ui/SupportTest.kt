package cz.majkey.pocasicesko.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class SupportTest {
    @Test
    fun usesPublishedBuyMeACoffeePage() {
        assertEquals("https://www.buymeacoffee.com/majkey", SUPPORT_URL)
    }

    @Test
    fun usesRequiredWeatherDataAttributionPage() {
        assertEquals("https://open-meteo.com/", OPEN_METEO_URL)
    }
}
