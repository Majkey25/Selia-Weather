package cz.majkey.pocasicesko.locale

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test
    fun everyLocaleContainsDenseWeatherAndRainFieldKeys() {
        val resourceRoot = File(System.getProperty("user.dir"), "src/main/res")
        listOf("values", "values-cs", "values-de", "values-es", "values-fr").forEach { directory ->
            val source = File(resourceRoot, "$directory/strings.xml").readText()
            DENSE_WEATHER_KEYS.forEach { key ->
                assertTrue("Missing $key in $directory", source.contains("name=\"$key\""))
            }
        }
    }

    companion object {
        private val DENSE_WEATHER_KEYS = listOf(
            "at_a_glance",
            "next_rain",
            "no_rain_next_24h",
            "max_precipitation_probability",
            "uv_index",
            "uv_index_max",
            "freezing_level",
            "atmosphere",
            "ground",
            "boundary_layer_height",
            "integrated_water_vapour",
            "lifted_index",
            "convective_inhibition",
            "soil_temperature",
            "soil_moisture",
            "showers",
            "spatial_precipitation_unavailable",
            "retry_spatial_precipitation",
            "local_rain_field",
            "rain_field_centre",
            "rain_field_unavailable",
            "rain_field_dry",
            "rain_field_rain",
            "rain_field_snow",
            "rain_field_mixed",
            "rain_field_probability",
            "rain_field_agreement",
            "rain_field_models",
            "rain_field_range",
            "rain_field_north",
            "rain_field_east",
            "rain_field_south",
            "rain_field_west",
            "rain_field_radius",
        )
    }
}
