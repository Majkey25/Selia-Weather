package cz.majkey.pocasicesko.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForecastRegionTest {
    @Test
    fun routesCoordinatesToTheMostSpecificModelRegion() {
        assertEquals(
            ForecastRegion.CZECHIA,
            forecastRegionFor(CzechLocation("Praha", REGION_PRAGUE, 50.0755, 14.4378, "CZ")),
        )
        assertEquals(
            ForecastRegion.EUROPE,
            forecastRegionFor(CzechLocation("Berlin", "Berlin", 52.52, 13.405, "DE")),
        )
        assertEquals(
            ForecastRegion.NORTH_AMERICA,
            forecastRegionFor(CzechLocation("New York", "New York", 40.7128, -74.006, "US")),
        )
        assertEquals(
            ForecastRegion.EAST_ASIA,
            forecastRegionFor(CzechLocation("Tokyo", "Tokyo", 35.6762, 139.6503, "JP")),
        )
        assertEquals(
            ForecastRegion.OCEANIA,
            forecastRegionFor(CzechLocation("Sydney", "New South Wales", -33.8688, 151.2093, "AU")),
        )
        assertEquals(
            ForecastRegion.GLOBAL,
            forecastRegionFor(CzechLocation("Pacific Ocean", REGION_WORLD, 0.0, -140.0)),
        )
    }

    @Test
    fun requestsEveryVerifiedGlobalFamilyAndOnlyAddsChmiInsideCzechia() {
        val prague = forecastApiModelsFor(
            CzechLocation("Praha", REGION_PRAGUE, 50.0755, 14.4378, "CZ"),
        )
        val newYork = forecastApiModelsFor(
            CzechLocation("New York", "New York", 40.7128, -74.006, "US"),
        )

        assertEquals(prague.size, prague.toSet().size)
        assertEquals(newYork.size, newYork.toSet().size)
        assertTrue("chmi_aladin_seamless" in prague)
        assertFalse("chmi_aladin_seamless" in newYork)
        assertFalse("kma_seamless" in prague)
        assertFalse("kma_seamless" in newYork)
        listOf(
            "icon_seamless",
            "ecmwf_ifs025",
            "ecmwf_aifs025",
            "gfs_seamless",
            "gem_seamless",
            "meteofrance_seamless",
            "ukmo_seamless",
            "cma_grapes_global",
            "jma_seamless",
            "bom_access_global",
        ).forEach { model -> assertTrue(model in newYork) }
    }

    @Test
    fun selectsOnlyModelsThatCoverTheForecastRegion() {
        assertEquals(
            setOf(
                "chmi-aladin-cz-1km",
                "dwd-icon-eu",
                "ecmwf-aifs-open",
                "ecmwf-ifs-open",
                "noaa-gefs",
                "noaa-gfs",
            ),
            forecastSourcesFor(ForecastRegion.CZECHIA),
        )
        assertEquals(
            setOf("ecmwf-aifs-open", "ecmwf-ifs-open", "noaa-gefs", "noaa-gfs"),
            forecastSourcesFor(ForecastRegion.NORTH_AMERICA),
        )
        assertEquals(
            setOf("ecmwf-aifs-open", "ecmwf-ifs-open", "noaa-gefs", "noaa-gfs"),
            forecastSourcesFor(ForecastRegion.GLOBAL),
        )
    }
}
