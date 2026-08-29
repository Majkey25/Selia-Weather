package cz.majkey.pocasicesko.data

import org.junit.Assert.assertEquals
import org.junit.Test

class LocationFavoritesCodecTest {
    @Test
    fun roundTripsTypedLocations() {
        val locations = listOf(
            CzechLocation("Praha", "Hlavní město Praha", 50.0755, 14.4378),
            CzechLocation("Brno", "Jihomoravský", 49.1951, 16.6068),
            CzechLocation("Berlin", "Berlin", 52.52, 13.405, countryCode = "DE"),
        )

        assertEquals(
            listOf(
                CzechLocation("Praha", REGION_PRAGUE, 50.0755, 14.4378),
                CzechLocation("Brno", REGION_SOUTH_MORAVIAN, 49.1951, 16.6068),
                CzechLocation("Berlin", "Berlin", 52.52, 13.405, countryCode = "DE"),
            ),
            LocationFavoritesCodec.decode(LocationFavoritesCodec.encode(locations)),
        )
    }

    @Test
    fun skipsInvalidEntries() {
        val json = """
            [
              {"name":"Praha","region":"Praha","latitude":50.1,"longitude":14.4},
              {"name":"","region":"Praha","latitude":50.1,"longitude":14.4},
              {"name":"Mimo","region":"X","latitude":500,"longitude":14.4}
            ]
        """.trimIndent()

        assertEquals(
            listOf(CzechLocation("Praha", REGION_CZECHIA, 50.1, 14.4)),
            LocationFavoritesCodec.decode(json),
        )
    }
}
