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
}
