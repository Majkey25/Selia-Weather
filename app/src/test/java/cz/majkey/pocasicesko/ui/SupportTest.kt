package cz.majkey.pocasicesko.ui

import cz.majkey.pocasicesko.BuildConfig
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SupportTest {
    @Test
    fun externalDonationsMatchDistributionChannel() {
        if (BuildConfig.DISTRIBUTION_CHANNEL == "github") {
            assertEquals("https://www.buymeacoffee.com/majkey", SUPPORT_URL)
        } else {
            assertEquals("play", BuildConfig.DISTRIBUTION_CHANNEL)
            assertEquals("", SUPPORT_URL)
            assertNull(supportIntent())
        }
    }

    @Test
    fun usesRequiredWeatherDataAttributionPage() {
        val path = if (BuildConfig.DISTRIBUTION_CHANNEL == "play") "play.html" else "terms.html"
        assertEquals("https://majkey25.github.io/Selia-Weather/$path#data-sources", WEATHER_DATA_URL)
    }

    @Test
    fun playLegalLinksStayOnThePaymentFreeLegalPage() {
        val root = File(requireNotNull(System.getProperty("user.dir"))).parentFile
        val page = File(root, "docs/play.html").readText()
        val anchors = setOf("privacy", "terms", "refunds", "cookies")
        assertTrue(anchors.all { page.contains("id=\"$it\"") })
        assertTrue(page.contains("id=\"data-sources\""))
        assertFalse(page.contains("buymeacoffee", ignoreCase = true))
        assertFalse(page.contains("href=\"index.html"))
        assertFalse(page.contains("href=\"refunds.html"))
        File(root, "fastlane/metadata/android").listFiles().orEmpty().filter { it.isDirectory }.forEach { locale ->
            val description = File(locale, "full_description.txt")
            if (description.exists()) assertFalse(description.readText().contains("coffee", ignoreCase = true))
        }
        LegalPage.entries.forEach { legal ->
            if (BuildConfig.DISTRIBUTION_CHANNEL == "play") {
                assertEquals(
                    "https://majkey25.github.io/Selia-Weather/play.html#${legal.name.lowercase(java.util.Locale.ROOT)}",
                    legal.url,
                )
            } else {
                assertEquals("https://majkey25.github.io/Selia-Weather/${legal.path}", legal.url)
            }
        }
    }
}
