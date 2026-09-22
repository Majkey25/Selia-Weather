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
        assertEquals("https://majkey25.github.io/Selia-Weather/terms.html#data-sources", WEATHER_DATA_URL)
    }
}
