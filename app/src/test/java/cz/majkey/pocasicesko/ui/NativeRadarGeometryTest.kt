package cz.majkey.pocasicesko.ui

import cz.majkey.pocasicesko.data.NativeRadarBounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeRadarGeometryTest {
    @Test fun pacificViewportSplitsAtTheDateLineWithoutLosingCoverage() {
        val parts = radarImageBounds(NativeRadarBounds(-20.0, 170.0, 20.0, 195.0))
        assertEquals(listOf(NativeRadarBounds(-20.0, 170.0, 20.0, 180.0),
            NativeRadarBounds(-20.0, -180.0, 20.0, -165.0)), parts)
        assertEquals(25.0, parts.sumOf { it.east - it.west }, 0.0001)
    }
    @Test fun wrappedWesternViewportProducesValidGeographicImageRequests() {
        val parts = radarImageBounds(NativeRadarBounds(-30.0, -190.0, -10.0, -175.0))
        assertTrue(parts.all { it.west >= -180 && it.east <= 180 })
        assertEquals(15.0, parts.sumOf { it.east - it.west }, 0.0001)
    }
}
