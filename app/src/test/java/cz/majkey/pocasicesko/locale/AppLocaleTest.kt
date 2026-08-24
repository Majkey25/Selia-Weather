package cz.majkey.pocasicesko.locale

import org.junit.Assert.assertEquals
import org.junit.Test

class AppLocaleTest {
    @Test
    fun acceptsSupportedTagsAndResetsInvalidTagsToSystem() {
        assertEquals("en", normalizeLanguageTag("en-US"))
        assertEquals("cs", normalizeLanguageTag("cs-CZ"))
        assertEquals("de", normalizeLanguageTag("de"))
        assertEquals("es", normalizeLanguageTag("es-MX"))
        assertEquals("fr", normalizeLanguageTag("fr-FR"))
        assertEquals("", normalizeLanguageTag("pl"))
        assertEquals("", normalizeLanguageTag(null))
    }
}
