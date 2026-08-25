package cz.majkey.pocasicesko.ui

import cz.majkey.pocasicesko.data.REGION_CZECHIA
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PinnedLocationTest {
    @Test
    fun createsNamedCzechPointFromDirectCoordinates() {
        val location = pinnedLocationOrNull("  North field  ", "50,075500", "14.437800")

        requireNotNull(location)
        assertEquals("North field", location.name)
        assertEquals(REGION_CZECHIA, location.region)
        assertEquals(50.0755, location.latitude, 0.0)
        assertEquals(14.4378, location.longitude, 0.0)
    }

    @Test
    fun rejectsMalformedNonFiniteAndOutsideCoordinates() {
        assertNull(pinnedLocationOrNull("Field", "north", "14.4"))
        assertNull(pinnedLocationOrNull("Field", "NaN", "14.4"))
        assertNull(pinnedLocationOrNull("Field", "50.0", "11.0"))
        assertNull(pinnedLocationOrNull(" ", "50.0", "14.4"))
    }

    @Test
    fun convertsDocumentedWebMercatorMapEdges() {
        val northWest = coordinatesFromMapPosition(0.0, 0.0)
        val southEast = coordinatesFromMapPosition(1.0, 1.0)

        requireNotNull(northWest)
        requireNotNull(southEast)
        assertEquals(52.167, northWest.latitude, 0.000_001)
        assertEquals(11.267, northWest.longitude, 0.000_001)
        assertEquals(48.047, southEast.latitude, 0.000_001)
        assertEquals(20.770, southEast.longitude, 0.000_001)
    }

    @Test
    fun mapConversionRejectsUntrustedFractions() {
        assertNull(coordinatesFromMapPosition(-0.01, 0.5))
        assertNull(coordinatesFromMapPosition(0.5, 1.01))
        assertNull(coordinatesFromMapPosition(Double.NaN, 0.5))
        assertTrue(coordinatesFromMapPosition(0.5, 0.5) != null)
    }

    @Test
    fun fittedMapRejectsLetterboxAndMapsImageCenter() {
        assertNull(imagePositionToMapFractions(500.0, 50.0, 1_000.0, 1_000.0, 2_000, 1_000))
        val center = imagePositionToMapFractions(500.0, 500.0, 1_000.0, 1_000.0, 2_000, 1_000)

        requireNotNull(center)
        assertEquals(0.5, center.first, 0.0)
        assertEquals(0.5, center.second, 0.0)
    }
}
