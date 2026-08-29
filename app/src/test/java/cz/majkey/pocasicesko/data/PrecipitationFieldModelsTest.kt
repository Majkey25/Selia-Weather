package cz.majkey.pocasicesko.data

import java.time.Instant
import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PrecipitationFieldModelsTest {
    @Test
    fun generatesTwentyFivePointsInStableOrderWorldwide() {
        listOf(
            CzechLocation("Equator", REGION_WORLD, 0.0, 0.0),
            CzechLocation("Date line", REGION_WORLD, 0.0, 179.99),
            CzechLocation("North", REGION_WORLD, 89.9, 45.0),
            CzechLocation("South", REGION_WORLD, -89.9, -45.0),
        ).forEach { location ->
            val points = precipitationFieldPoints(location)

            assertEquals(25, points.size)
            assertEquals(
                (0..4).flatMap { row -> (0..4).map { column -> row to column } },
                points.map { it.row to it.column },
            )
            assertEquals(0.0, points[12].offsetEastKm, 0.0)
            assertEquals(0.0, points[12].offsetNorthKm, 0.0)
            assertTrue(points.all { point ->
                point.latitude.isFinite() && point.latitude in -90.0..90.0 &&
                    point.longitude.isFinite() && point.longitude in -180.0..180.0 &&
                    hypot(point.offsetEastKm, point.offsetNorthKm) <= 20.0
            })
        }
    }

    @Test
    fun rejectsInvalidGeometryAndCellValues() {
        assertThrows(IllegalArgumentException::class.java) {
            precipitationFieldPoints(CzechLocation("Bad", REGION_WORLD, Double.NaN, 0.0))
        }
        assertThrows(IllegalArgumentException::class.java) {
            point(row = 5, column = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            validCell().copy(precipitationMm = -0.1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            validCell().copy(probabilityPercent = 101)
        }
        assertThrows(IllegalArgumentException::class.java) {
            validCell().copy(contributorCount = 2)
        }
        assertThrows(IllegalArgumentException::class.java) {
            validCell().copy(kind = PrecipitationKind.UNAVAILABLE)
        }
    }

    @Test
    fun validatesFrameAndFieldShape() {
        val cells = precipitationFieldPoints(
            CzechLocation("Point", REGION_WORLD, 50.0, 14.0),
        ).map(::validCell)
        val first = PrecipitationFieldFrame(Instant.parse("2026-08-29T18:00:00Z"), cells)
        val second = PrecipitationFieldFrame(Instant.parse("2026-08-29T19:00:00Z"), cells)

        assertEquals(2, PrecipitationField(listOf(first, second)).frames.size)
        assertThrows(IllegalArgumentException::class.java) {
            PrecipitationFieldFrame(first.validTime, cells.dropLast(1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            PrecipitationField(listOf(second, first))
        }
    }

    private fun point(row: Int = 2, column: Int = 2) = PrecipitationFieldPoint(
        row = row,
        column = column,
        latitude = 50.0,
        longitude = 14.0,
        offsetEastKm = 0.0,
        offsetNorthKm = 0.0,
    )

    private fun validCell(point: PrecipitationFieldPoint = point()) = PrecipitationFieldCell(
        point = point,
        precipitationMm = 0.2,
        rainMm = 0.2,
        showersMm = 0.0,
        snowfallCm = 0.0,
        probabilityPercent = 67,
        agreementPercent = 67,
        contributorCount = 3,
        minimumMm = 0.0,
        maximumMm = 0.4,
        kind = PrecipitationKind.RAIN,
    )
}
