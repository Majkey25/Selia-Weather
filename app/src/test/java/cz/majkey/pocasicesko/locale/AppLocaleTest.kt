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

    @Test
    fun usesTheSelectedOrSupportedSystemLanguageForProviderQueries() {
        assertEquals("en", effectiveLanguageTag("en", "cs-CZ"))
        assertEquals("cs", effectiveLanguageTag("", "cs-CZ"))
        assertEquals("de", effectiveLanguageTag(null, "de-AT"))
        assertEquals("en", effectiveLanguageTag("", "pl-PL"))
    }

    @Test
    fun usesFrameworkAppLocalesInsteadOfStalePreferenceOnApi33() {
        assertEquals("fr", selectedLanguageTag(35, "cs", "fr-FR"))
        assertEquals("fr", selectedLanguageTag(35, "cs", "fr,de"))
        assertEquals("", selectedLanguageTag(35, "cs", ""))
        assertEquals("cs", selectedLanguageTag(32, "cs", "fr-FR"))
    }
}
