package cz.majkey.pocasicesko.data

import org.junit.Assert.assertEquals
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
            ForecastRegion.GLOBAL,
            forecastRegionFor(CzechLocation("Tokyo", "Tokyo", 35.6762, 139.6503, "JP")),
        )
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
