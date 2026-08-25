package cz.majkey.pocasicesko.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class SupportTest {
    @Test
    fun usesPublishedBuyMeACoffeePage() {
        assertEquals("https://www.buymeacoffee.com/majkey", SUPPORT_URL)
    }
}
