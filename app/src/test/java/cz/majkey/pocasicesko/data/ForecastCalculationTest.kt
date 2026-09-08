package cz.majkey.pocasicesko.data

import org.json.JSONException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ForecastCalculationTest {
    private val calibrated = ForecastCalculation(
        region = ForecastRegion.CZECHIA,
        mode = ForecastCalculationMode.CALIBRATED,
        requestedModelIds = listOf("a", "b", "c"),
        contributorIds = listOf("a", "b"),
        fallbackReason = null,
        artifactVersion = 2,
        artifactGeneratedAtEpochSeconds = 1_788_000_000L,
        truthClass = CalibrationTruthClass.STATION,
        weights = mapOf("a" to 0.4, "b" to 0.6),
    )

    @Test
    fun roundTripsFirstAppliedSampleAndCountWithoutRequiringTheCurrentHour() {
        val calculation = calibrated.copy(
            calibrationAppliedAt = "2026-09-07T06:00",
            calibrationVariable = "temperature_2m",
            calibratedValueCount = 24,
        )
        assertEquals(calculation, JSONObject().putForecastCalculation(calculation).forecastCalculationOrNull())
    }

    @Test
    fun legacySchemaTwoWithoutSampleFieldsStillLoads() {
        val root = JSONObject().putForecastCalculation(calibrated)
        val value = root.getJSONObject("_selia_calculation")
        value.remove("calibration_applied_at")
        value.remove("calibration_variable")
        value.remove("calibrated_value_count")
        val parsed = requireNotNull(root.forecastCalculationOrNull())
        assertEquals(calibrated, parsed)
        assertEquals(0, parsed.calibratedValueCount)
        assertNull(parsed.calibrationAppliedAt)
        assertNull(parsed.calibrationVariable)
    }

    @Test
    fun legacySchemaOneBestMatchStillLoads() {
        val calculation = ForecastCalculation(ForecastRegion.GLOBAL, ForecastCalculationMode.BEST_MATCH,
            listOf("a"), emptyList(), ForecastFallbackReason.PROVIDER_UNAVAILABLE)
        val root = JSONObject().putForecastCalculation(calculation)
        root.getJSONObject("_selia_calculation").put("schema_version", 1)
        assertEquals(calculation, root.forecastCalculationOrNull())
    }

    @Test
    fun rejectsIncoherentSampleMetadata() {
        assertThrows(IllegalArgumentException::class.java) { calibrated.copy(calibratedValueCount = -1) }
        assertThrows(IllegalArgumentException::class.java) { calibrated.copy(calibratedValueCount = 1) }
        assertThrows(IllegalArgumentException::class.java) {
            calibrated.copy(calibrationAppliedAt = "2026-09-07T06:00", calibratedValueCount = 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            calibrated.copy(calibrationVariable = "temperature_2m", calibratedValueCount = 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            calibrated.copy(calibrationAppliedAt = "2026-09-07T06:00", calibrationVariable = "temperature_2m")
        }
        for ((time, variable) in listOf(
            "2026-02-30T06:00" to "temperature_2m",
            "2026-09-07T06:00Z" to "temperature_2m",
            "2026-09-07T06:00" to "bad variable",
            "2026-09-07T06:00" to "Temperature-2m",
        )) {
            assertThrows(IllegalArgumentException::class.java) {
                calibrated.copy(calibrationAppliedAt = time, calibrationVariable = variable, calibratedValueCount = 1)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            calibrated.copy(mode = ForecastCalculationMode.DIAGNOSTIC_MEDIAN, contributorIds = listOf("a", "b", "c"),
                artifactVersion = null, artifactGeneratedAtEpochSeconds = null, truthClass = null, weights = emptyMap(),
                calibrationAppliedAt = "2026-09-07T06:00", calibrationVariable = "temperature_2m", calibratedValueCount = 1)
        }
    }

    @Test
    fun malformedCacheMetadataFailsExplicitlyInsteadOfTruncatingCounts() {
        for (count in listOf<Number>(1.5, Long.MAX_VALUE, -1)) {
            val root = JSONObject().putForecastCalculation(calibrated)
            root.getJSONObject("_selia_calculation")
                .put("calibration_applied_at", "2026-09-07T06:00")
                .put("calibration_variable", "temperature_2m")
                .put("calibrated_value_count", count)
            assertThrows(JSONException::class.java) { root.forecastCalculationOrNull() }
        }
    }

}
