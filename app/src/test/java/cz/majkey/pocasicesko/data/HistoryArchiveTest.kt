package cz.majkey.pocasicesko.data

import java.time.LocalDate
import org.json.JSONException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class HistoryArchiveTest {
    private val location = CzechLocation("Praha", REGION_PRAGUE, 50.0755, 14.4378, "CZ")

    @Test
    fun preservesPartialDaysAndDoesNotTurnInvalidRainIntoDryWeather() {
        val archive = parsePowerHistory(SAMPLE_JSON, location, 123L)

        assertEquals("v2.9.7", archive.sourceVersion)
        assertEquals(123L, archive.accessedAtEpochMillis)
        assertEquals(3, archive.days.size)
        assertEquals(LocalDate.of(2026, 1, 1), archive.days[0].date)
        assertNull(archive.days[0].precipitationMm)
        assertEquals(85.81, archive.days[0].relativeHumidityPercent!!, 0.001)
        assertNull(archive.days[1].relativeHumidityPercent)
        assertNull(archive.days[2].temperatureMeanC)
        assertEquals(1.0, archive.days[2].precipitationMm!!, 0.0)
    }

    @Test
    fun calculatesDeterministicSummary() {
        val summary = parsePowerHistory(SAMPLE_JSON, location, 123L).summary()

        assertEquals(3, summary.dayCount)
        assertEquals(3L, summary.calendarDayCount)
        assertEquals(2, summary.precipitationDayCount)
        assertEquals(2, summary.temperatureDayCount)
        assertEquals(2, summary.temperatureMinimumDayCount)
        assertEquals(2, summary.temperatureMaximumDayCount)
        assertEquals(2, summary.solarEnergyDayCount)
        assertEquals(1, summary.humidityDayCount)
        assertEquals(2, summary.windDayCount)
        assertEquals(1.66, summary.totalPrecipitationMm!!, 0.001)
        assertEquals(2, summary.wetDayCount)
        assertEquals(-0.865, summary.averageTemperatureC!!, 0.001)
        assertEquals(-3.66, summary.minimumTemperatureC!!, 0.001)
        assertEquals(1.34, summary.maximumTemperatureC!!, 0.001)
        assertEquals(3.03, summary.totalSolarEnergyMegajoulesPerSquareMeter!!, 0.001)
    }

    @Test(expected = JSONException::class)
    fun rejectsResponseWithoutUsableDays() {
        parsePowerHistory("""{"properties":{"parameter":{}}}""", location, 123L)
    }

    @Test
    fun missingSolarDaysKeepPartialSumAndExplicitCoverage() {
        val archive = parsePowerHistory(SAMPLE_JSON, location, 123L)
        val partial = archive.copy(days = listOf(
            archive.days[0],
            archive.days[1].copy(
                date = LocalDate.of(2026, 1, 3),
                solarEnergyMegajoulesPerSquareMeter = null,
            ),
        )).summary()

        assertEquals(3L, partial.calendarDayCount)
        assertEquals(2, partial.dayCount)
        assertEquals(1, partial.solarEnergyDayCount)
        assertEquals(1.0, partial.totalSolarEnergyMegajoulesPerSquareMeter!!, 0.0)

        val missing = archive.copy(days = archive.days.map {
            it.copy(solarEnergyMegajoulesPerSquareMeter = null)
        }).summary()
        assertEquals(0, missing.solarEnergyDayCount)
        assertNull(missing.totalSolarEnergyMegajoulesPerSquareMeter)
    }

    @Test
    fun rejectsEmptyArchiveAtTheBoundary() {
        assertThrows(IllegalArgumentException::class.java) {
            HistoryArchive(location, emptyList(), "v1", 123L)
        }
    }

    @Test
    fun parsesExpandedPowerParametersAndConvertsSurfacePressureToHpa() {
        val root = JSONObject(SAMPLE_JSON)
        val fields = root.getJSONObject("properties").getJSONObject("parameter")
        mapOf(
            "T2MDEW" to -2.3, "T2MWET" to -1.01, "PS" to 97.7,
            "WS10M_MAX" to 10.72, "WS10M_MIN" to 6.02, "WD10M" to 224.4,
            "CLRSKY_SFC_SW_DWN" to 4.53, "CLOUD_AMT" to 93.79,
        ).forEach { (name, value) ->
            fields.put(name, JSONObject().put("20260101", value).put("20260102", -999.0))
        }
        val first = parsePowerHistory(root.toString(), location, 123L).days.first()
        assertEquals(-2.3, first.dewPointC!!, 0.001)
        assertEquals(-1.01, first.wetBulbTemperatureC!!, 0.001)
        assertEquals(977.0, first.surfacePressureHpa!!, 0.001)
        assertEquals(10.72, first.windSpeedMaximumMetersPerSecond!!, 0.001)
        assertEquals(6.02, first.windSpeedMinimumMetersPerSecond!!, 0.001)
        assertEquals(224.4, first.windDirectionDegrees!!, 0.001)
        assertEquals(4.53, first.clearSkySolarEnergyMegajoulesPerSquareMeter!!, 0.001)
        assertEquals(93.79, first.cloudCoverPercent!!, 0.001)
    }

    @Test
    fun retainsSolarOnlyDatesAndLeavesMissingCoreSummariesUnavailable() {
        val json = """{"properties":{"parameter":{"ALLSKY_SFC_SW_DWN":{"20260101":2.5}}}}"""
        val archive = parsePowerHistory(json, location, 123L)
        assertEquals(1, archive.days.size)
        val summary = archive.summary()
        assertEquals(0, summary.precipitationDayCount)
        assertEquals(0, summary.temperatureDayCount)
        assertNull(summary.totalPrecipitationMm)
        assertNull(summary.wetDayCount)
        assertNull(summary.averageTemperatureC)
        assertNull(summary.minimumTemperatureC)
        assertNull(summary.maximumTemperatureC)
        assertEquals(2.5, summary.totalSolarEnergyMegajoulesPerSquareMeter!!, 0.0)
    }

    @Test
    fun rejectsWrongDeclaredUnitsInsteadOfMislabelingValues() {
        val root = JSONObject(SAMPLE_JSON)
        root.put("parameters", JSONObject().put("ALLSKY_SFC_SW_DWN", JSONObject().put("units", "kW-hr/m^2/day")))
        assertThrows(JSONException::class.java) { parsePowerHistory(root.toString(), location, 123L) }
    }

    @Test
    fun rejectsLocalSolarDatesAndIgnoresInvalidOptionalMeasurements() {
        val root = JSONObject(SAMPLE_JSON)
        root.getJSONObject("header").put("time_standard", "LST")
        assertThrows(JSONException::class.java) { parsePowerHistory(root.toString(), location, 123L) }
        root.getJSONObject("header").put("time_standard", "UTC").put("fill_value", "invalid")
        val fields = root.getJSONObject("properties").getJSONObject("parameter")
        mapOf("RH2M" to 101.0, "WS10M_MAX" to -1.0, "WD10M" to 361.0, "PS" to 0.0,
            "CLOUD_AMT" to -1.0, "CLRSKY_SFC_SW_DWN" to -1.0).forEach { (name, value) ->
            fields.put(name, JSONObject().put("20260101", value))
        }
        val archive = parsePowerHistory(root.toString(), location, 123L)
        val day = archive.days.first()
        assertNull(day.relativeHumidityPercent)
        assertNull(day.windSpeedMaximumMetersPerSecond)
        assertNull(day.windDirectionDegrees)
        assertNull(day.surfacePressureHpa)
        assertNull(day.cloudCoverPercent)
        assertNull(day.clearSkySolarEnergyMegajoulesPerSquareMeter)
        assertNull(archive.days.last().temperatureMeanC)
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
