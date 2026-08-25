package cz.majkey.pocasicesko.astro

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MoonCalculatorTest {
    @Test
    fun knownNewAndFullMoonDatesProduceExpectedPhaseAndIllumination() {
        val newMoon = MoonCalculator.calculate(
            ZonedDateTime.parse("2024-04-08T18:21:00Z"),
            50.0755,
            14.4378,
        )
        val fullMoon = MoonCalculator.calculate(
            ZonedDateTime.parse("2024-03-25T07:00:00Z"),
            50.0755,
            14.4378,
        )

        assertEquals(MoonPhaseKey.NEW_MOON, newMoon.phase)
        assertTrue(newMoon.illuminatedFraction < 0.01)
        assertEquals(MoonPhaseKey.FULL_MOON, fullMoon.phase)
        assertTrue(fullMoon.illuminatedFraction > 0.99)
    }

    @Test
    fun firstQuarterAndOrientationStayInDisplayRanges() {
        val details = MoonCalculator.calculate(
            ZonedDateTime.parse("2024-04-15T19:13:00Z"),
            50.0755,
            14.4378,
        )

        assertEquals(MoonPhaseKey.FIRST_QUARTER, details.phase)
        assertTrue(details.illuminatedFraction in 0.45..0.55)
        assertTrue(details.altitudeDegrees in -90.0..90.0)
        assertTrue(details.azimuthDegrees in 0.0..360.0)
        assertTrue(details.brightLimbAngleDegrees in -180.0..180.0)
        assertTrue(details.distanceKm > 300_000.0)
    }

    @Test
    fun PragueDstKeepsLocalZoneAndFindsUpcomingPrincipalPhases() {
        val zone = ZoneId.of("Europe/Prague")
        val details = MoonCalculator.calculate(
            ZonedDateTime.of(2024, 3, 31, 12, 0, 0, 0, zone),
            50.0755,
            14.4378,
        )

        details.rise?.let { assertEquals(zone, it.zone) }
        details.set?.let { assertEquals(zone, it.zone) }
        assertEquals(zone, details.nextNewMoon.zone)
        assertEquals(zone, details.nextFullMoon.zone)
        assertTrue(details.nextNewMoon.isAfter(ZonedDateTime.of(2024, 3, 31, 12, 0, 0, 0, zone)))
        assertTrue(details.nextFullMoon.isAfter(ZonedDateTime.of(2024, 3, 31, 12, 0, 0, 0, zone)))
        assertTrue(details.nextNewMoon.isBefore(ZonedDateTime.of(2024, 5, 10, 12, 0, 0, 0, zone)))
        assertTrue(details.nextFullMoon.isBefore(ZonedDateTime.of(2024, 5, 10, 12, 0, 0, 0, zone)))
    }

    @Test
    fun polarMissingRiseOrSetIsReportedWithoutFabricatedTime() {
        val details = MoonCalculator.calculate(
            ZonedDateTime.parse("2024-01-01T12:00:00Z"),
            89.0,
            0.0,
        )

        if (details.rise == null && details.set == null) {
            assertTrue(details.alwaysUp || details.alwaysDown)
        } else {
            assertNotNull(details.rise ?: details.set)
            assertFalse(details.alwaysUp && details.alwaysDown)
        }
    }

    @Test
    fun invalidCoordinatesAndElevationFailAtBoundary() {
        val at = ZonedDateTime.parse("2024-04-08T18:21:00Z")

        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            MoonCalculator.calculate(at, Double.NaN, 14.0)
        }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            MoonCalculator.calculate(at, 91.0, 14.0)
        }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            MoonCalculator.calculate(at, 50.0, 14.0, elevationMeters = -1.0)
        }
    }
}
