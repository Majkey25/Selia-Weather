package cz.majkey.pocasicesko.ui

import androidx.compose.ui.graphics.Color
import cz.majkey.pocasicesko.data.PrecipitationFieldCell
import cz.majkey.pocasicesko.data.PrecipitationFieldPoint
import cz.majkey.pocasicesko.data.PrecipitationKind
import cz.majkey.pocasicesko.units.MeasurementSystem
import cz.majkey.pocasicesko.units.WeatherUnitFormatter
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalRainFieldTest {
    @Test
    fun mapsFixedMetricIntensityThresholds() {
        assertEquals(Color.Transparent, rainFieldColor(cell(null, PrecipitationKind.UNAVAILABLE)))
        assertEquals(Color.Transparent, rainFieldColor(cell(0.0, PrecipitationKind.DRY)))
        assertEquals(Color(0xFF4EC8E0), rainFieldColor(cell(0.4)))
        assertEquals(Color(0xFF3987E3), rainFieldColor(cell(0.5)))
        assertEquals(Color(0xFF3987E3), rainFieldColor(cell(1.9)))
        assertEquals(Color(0xFF7657D6), rainFieldColor(cell(2.0)))
        assertEquals(Color(0xFF7657D6), rainFieldColor(cell(4.9)))
        assertEquals(Color(0xFFC441B8), rainFieldColor(cell(5.0)))
    }

    @Test
    fun describesCentreAndNeighbourWithUnitsAndUncertainty() {
        val metric = WeatherUnitFormatter(MeasurementSystem.METRIC, Locale.US)
        val imperial = WeatherUnitFormatter(MeasurementSystem.IMPERIAL, Locale.US)
        val centre = rainFieldCellDescription(cell(0.4), "20:00", metric, LABELS)
        val neighbour = rainFieldCellDescription(
            cell(25.4, point = point(east = 7.0, north = 7.0)),
            "21:00",
            imperial,
            LABELS,
        )

        listOf("Selected location", "20:00", "0.4 mm", "Range 0.4 mm–0.4 mm", "Rain", "67%", "3 models").forEach {
            assertTrue(centre.contains(it))
        }
        listOf("NE", "6.2 mi", "1.00 in", "21:00").forEach { assertTrue(neighbour.contains(it)) }
    }

    private fun cell(
        precipitation: Double?,
        kind: PrecipitationKind = PrecipitationKind.RAIN,
        point: PrecipitationFieldPoint = point(),
    ): PrecipitationFieldCell = if (kind == PrecipitationKind.UNAVAILABLE) {
        PrecipitationFieldCell(point, null, null, null, null, null, null, 0, null, null, kind)
    } else {
        PrecipitationFieldCell(
            point = point,
            precipitationMm = requireNotNull(precipitation),
            rainMm = precipitation,
            showersMm = 0.0,
            snowfallCm = 0.0,
            probabilityPercent = 67,
            agreementPercent = 67,
            contributorCount = 3,
            minimumMm = precipitation,
            maximumMm = precipitation,
            kind = kind,
        )
    }

    private fun point(east: Double = 0.0, north: Double = 0.0) = PrecipitationFieldPoint(
        row = 2,
        column = 2,
        latitude = 50.0,
        longitude = 14.0,
        offsetEastKm = east,
        offsetNorthKm = north,
    )

    companion object {
        private val LABELS = RainFieldLabels(
            centre = "Selected location",
            unavailable = "Unavailable",
            dry = "Dry",
            rain = "Rain",
            snow = "Snow",
            mixed = "Mixed",
            probability = "Probability",
            agreement = "Agreement",
            models = "models",
            range = "Range",
            north = "N",
            east = "E",
            south = "S",
            west = "W",
        )
    }
}
