package cz.majkey.pocasicesko.data

import cz.majkey.pocasicesko.R
import cz.majkey.pocasicesko.ui.regionLabelResource
import org.junit.Assert.assertEquals
import org.junit.Test

class CzechRegionTest {
    @Test
    fun mapsEveryOpenMeteoCzechAdmin1IdToAStableKey() {
        val expected = mapOf(
            3_067_695 to REGION_PRAGUE,
            3_339_576 to REGION_CENTRAL_BOHEMIA,
            3_339_537 to REGION_SOUTH_BOHEMIAN,
            3_339_575 to REGION_PLZEN,
            3_339_539 to REGION_KARLOVY_VARY,
            3_339_577 to REGION_USTI_NAD_LABEM,
            3_339_541 to REGION_LIBEREC,
            3_339_540 to REGION_HRADEC_KRALOVE,
            3_339_574 to REGION_PARDUBICE,
            3_339_538 to REGION_VYSOCINA,
            3_339_536 to REGION_SOUTH_MORAVIAN,
            3_339_542 to REGION_OLOMOUC,
            3_339_578 to REGION_ZLIN,
            3_339_573 to REGION_MORAVIAN_SILESIAN,
        )

        expected.forEach { (id, key) -> assertEquals(key, regionKeyForAdmin1Id(id)) }
        assertEquals(REGION_CZECHIA, regionKeyForAdmin1Id(0))
    }

    @Test
    fun normalizesLegacyLabelsAndUnknownsBeforePersistence() {
        assertEquals(REGION_PRAGUE, normalizeRegionKey("Hlavní město Praha"))
        assertEquals(REGION_PRAGUE, normalizeRegionKey("Capital City of Prague"))
        assertEquals(REGION_SOUTH_MORAVIAN, normalizeRegionKey("Jihomoravský kraj"))
        assertEquals(REGION_SOUTH_MORAVIAN, normalizeRegionKey("South Moravian"))
        assertEquals(REGION_CZECHIA, normalizeRegionKey("Unknown region"))
    }

    @Test
    fun storesTheCzechLegacyPragueLabelAsAKeyForEnglishRendering() {
        val location = CzechLocation("Praha", "Hlavní město Praha", 50.0755, 14.4378)

        assertEquals(REGION_PRAGUE, normalizeLocationRegion(location).region)
        assertEquals(R.string.location_region_prague, regionLabelResource(REGION_PRAGUE))
    }

    @Test
    fun normalizesEveryGermanSpanishAndFrenchLegacyRegionLabel() {
        listOf(
            mapOf(
                "Hauptstadt Prag" to REGION_PRAGUE,
                "Mittelböhmische Region" to REGION_CENTRAL_BOHEMIA,
                "Südböhmische Region" to REGION_SOUTH_BOHEMIAN,
                "Region Pilsen" to REGION_PLZEN,
                "Region Karlsbad" to REGION_KARLOVY_VARY,
                "Region Ústí nad Labem" to REGION_USTI_NAD_LABEM,
                "Region Liberec" to REGION_LIBEREC,
                "Region Hradec Králové" to REGION_HRADEC_KRALOVE,
                "Region Pardubice" to REGION_PARDUBICE,
                "Region Vysočina" to REGION_VYSOCINA,
                "Südmährische Region" to REGION_SOUTH_MORAVIAN,
                "Region Olomouc" to REGION_OLOMOUC,
                "Region Zlín" to REGION_ZLIN,
                "Mährisch-Schlesische Region" to REGION_MORAVIAN_SILESIAN,
            ),
            mapOf(
                "Ciudad Capital de Praga" to REGION_PRAGUE,
                "Región de Bohemia Central" to REGION_CENTRAL_BOHEMIA,
                "Región de Bohemia Meridional" to REGION_SOUTH_BOHEMIAN,
                "Región de Pilsen" to REGION_PLZEN,
                "Región de Karlovy Vary" to REGION_KARLOVY_VARY,
                "Región de Ústí nad Labem" to REGION_USTI_NAD_LABEM,
                "Región de Liberec" to REGION_LIBEREC,
                "Región de Hradec Králové" to REGION_HRADEC_KRALOVE,
                "Región de Pardubice" to REGION_PARDUBICE,
                "Región de Vysočina" to REGION_VYSOCINA,
                "Región de Moravia Meridional" to REGION_SOUTH_MORAVIAN,
                "Región de Olomouc" to REGION_OLOMOUC,
                "Región de Zlín" to REGION_ZLIN,
                "Región de Moravia-Silesia" to REGION_MORAVIAN_SILESIAN,
            ),
            mapOf(
                "Capitale Prague" to REGION_PRAGUE,
                "Région de Bohême-Centrale" to REGION_CENTRAL_BOHEMIA,
                "Région de Bohême-du-Sud" to REGION_SOUTH_BOHEMIAN,
                "Région de Plzeň" to REGION_PLZEN,
                "Région de Karlovy Vary" to REGION_KARLOVY_VARY,
                "Région d'Ústí nad Labem" to REGION_USTI_NAD_LABEM,
                "Région de Liberec" to REGION_LIBEREC,
                "Région de Hradec Králové" to REGION_HRADEC_KRALOVE,
                "Région de Pardubice" to REGION_PARDUBICE,
                "Région de Vysočina" to REGION_VYSOCINA,
                "Région de Moravie-du-Sud" to REGION_SOUTH_MORAVIAN,
                "Région d'Olomouc" to REGION_OLOMOUC,
                "Région de Zlín" to REGION_ZLIN,
                "Région de Moravie-Silésie" to REGION_MORAVIAN_SILESIAN,
            ),
        ).forEach { labels ->
            labels.forEach { (label, key) -> assertEquals(key, normalizeRegionKey(label)) }
            val location = CzechLocation("Brno", labels.entries.first { it.value == REGION_SOUTH_MORAVIAN }.key, 49.1951, 16.6068)
            assertEquals(REGION_SOUTH_MORAVIAN, LocationFavoritesCodec.decode(LocationFavoritesCodec.encode(listOf(location))).single().region)
        }
    }
}
