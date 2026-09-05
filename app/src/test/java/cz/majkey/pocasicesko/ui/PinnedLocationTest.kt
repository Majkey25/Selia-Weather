package cz.majkey.pocasicesko.ui

import cz.majkey.pocasicesko.data.REGION_CZECHIA
import cz.majkey.pocasicesko.data.REGION_WORLD
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PinnedLocationTest {
    @Test
    fun mapAndSaveUseTheSameValidatedCoordinates() {
        val point = requireNotNull(mapCoordinatesOrNull("-33,8688", "151.2093"))
        val saved = requireNotNull(pinnedLocationOrNull("Sydney", "-33,8688", "151.2093"))
        assertEquals(saved.latitude, point.latitude, 0.0)
        assertEquals(saved.longitude, point.longitude, 0.0)
        assertNull(pinnedLocationOrNull("", "-33,8688", "151.2093"))
        assertNull(mapCoordinatesOrNull("-", "151.2093"))
        assertNull(mapCoordinatesOrNull("Infinity", "151.2093"))
        assertNull(mapCoordinatesOrNull("0", "-181"))
        assertEquals(MapCoordinates(0.0, 0.0), mapCoordinatesOrNull("0", "0"))
    }

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
        assertNull(pinnedLocationOrNull("Field", "91.0", "14.4"))
        assertNull(pinnedLocationOrNull("Field", "50.0", "181.0"))
        assertNull(pinnedLocationOrNull(" ", "50.0", "14.4"))
    }

    @Test
    fun acceptsNamedCoordinatesWorldwide() {
        val location = pinnedLocationOrNull("Manhattan", "40.7128", "-74.0060")

        requireNotNull(location)
        assertEquals(REGION_WORLD, location.region)
        assertEquals(40.7128, location.latitude, 0.0)
        assertEquals(-74.006, location.longitude, 0.0)
    }

}
