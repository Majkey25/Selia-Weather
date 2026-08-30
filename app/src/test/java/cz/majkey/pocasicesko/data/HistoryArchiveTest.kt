package cz.majkey.pocasicesko.data

import java.time.LocalDate
import org.json.JSONException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class HistoryArchiveTest {
    private val location = CzechLocation("Praha", REGION_PRAGUE, 50.0755, 14.4378, "CZ")

    @Test
    fun parsesPowerDaysAndSkipsInvalidRequiredValues() {
        val archive = parsePowerHistory(SAMPLE_JSON, location, 123L)

        assertEquals("v2.9.7", archive.sourceVersion)
        assertEquals(123L, archive.accessedAtEpochMillis)
        assertEquals(2, archive.days.size)
        assertEquals(LocalDate.of(2026, 1, 1), archive.days[0].date)
        assertEquals(0.0, archive.days[0].precipitationMm, 0.0)
        assertEquals(85.81, archive.days[0].relativeHumidityPercent!!, 0.001)
        assertNull(archive.days[1].relativeHumidityPercent)
    }

    @Test
    fun calculatesDeterministicSummary() {
        val summary = parsePowerHistory(SAMPLE_JSON, location, 123L).summary()

        assertEquals(2, summary.dayCount)
        assertEquals(0.66, summary.totalPrecipitationMm, 0.001)
        assertEquals(1, summary.wetDayCount)
        assertEquals(-0.865, summary.averageTemperatureC, 0.001)
        assertEquals(-3.66, summary.minimumTemperatureC, 0.001)
        assertEquals(1.34, summary.maximumTemperatureC, 0.001)
        assertEquals(3.03, summary.totalSolarEnergyMegajoulesPerSquareMeter!!, 0.001)
    }

    @Test(expected = JSONException::class)
    fun rejectsResponseWithoutUsableDays() {
        parsePowerHistory("""{"properties":{"parameter":{}}}""", location, 123L)
    }

    @Test
    fun rejectsEmptyArchiveAtTheBoundary() {
        assertThrows(IllegalArgumentException::class.java) {
            HistoryArchive(location, emptyList(), "v1", 123L)
        }
    }

    private companion object {
        val SAMPLE_JSON = """
            {
              "properties": {
                "parameter": {
                  "T2M": {"20260101": -0.77, "20260102": -0.96, "20260103": -999.0},
                  "T2M_MAX": {"20260101": 0.57, "20260102": 1.34, "20260103": -999.0},
                  "T2M_MIN": {"20260101": -2.76, "20260102": -3.66, "20260103": -999.0},
                  "PRECTOTCORR": {"20260101": -0.1, "20260102": 0.66, "20260103": 1.0},
                  "RH2M": {"20260101": 85.81, "20260102": -999.0},
                  "WS10M": {"20260101": 9.29, "20260102": 9.78},
                  "ALLSKY_SFC_SW_DWN": {"20260101": 1.0, "20260102": 2.03}
                }
              },
              "header": {
                "api": {"version": "v2.9.7"},
                "fill_value": -999.0
              }
            }
        """.trimIndent()
    }
}
