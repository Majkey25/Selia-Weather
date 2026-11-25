package cz.majkey.pocasicesko.ui

import cz.majkey.pocasicesko.data.NativeRadarBounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeRadarGeometryTest {
    @Test fun forecastRequestIsStableAcrossSmallPansAndRefreshesOutsideItsMargin() {
        val viewport = NativeRadarBounds(48.0, 12.0, 52.0, 16.0)
        val request = radarForecastBounds(viewport)
        assertEquals(NativeRadarBounds(47.5, 11.5, 52.5, 16.5), request)
        val nearbyPans = (1..4).map { offset -> viewport.copy(west = 12.0 + offset / 10.0, east = 16.0 + offset / 10.0) }
        assertEquals(4, nearbyPans.toSet().size)
        assertTrue(nearbyPans.all { radarForecastBounds(it, request) == request })
        assertNotEquals(request, radarForecastBounds(viewport.copy(west = 13.0, east = 17.0), request))
        assertNotEquals(request, radarForecastBounds(NativeRadarBounds(49.0, 13.0, 51.0, 15.0), request))
    }

    @Test fun bufferedForecastWrapsAtTheDateLineAndClampsToMercator() {
        val viewport = NativeRadarBounds(79.0, 176.0, 89.0, 190.0)
        val request = radarForecastBounds(viewport)
        assertTrue(request.north < viewport.north)
        assertEquals(2, radarImageBounds(request).size)
        assertEquals(request, radarForecastBounds(viewport.copy(west = -184.0, east = -170.0), request))
        val world = radarForecastBounds(NativeRadarBounds(-85.0, 15.0, 85.0, 375.0))
        assertEquals(2, radarImageBounds(world).size)
        assertEquals(360.0, radarImageBounds(world).sumOf { it.east - it.west }, 0.0)
    }

    @Test fun imageUsesProjectedAspectRatioWithoutGoingOverThePixelBudget() {
        val (width, height) = radarImageDimensions(NativeRadarBounds(45.0, 12.0, 55.0, 16.0))
        assertTrue(width in 1..767)
        assertEquals(768, height)
        assertEquals(768, radarImageDimensions(NativeRadarBounds(-2.0, -10.0, 2.0, 10.0)).first)
    }

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
