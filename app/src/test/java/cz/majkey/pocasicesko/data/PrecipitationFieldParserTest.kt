package cz.majkey.pocasicesko.data

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.Instant

class PrecipitationFieldParserTest {
    @Test
    fun calculatesMedianProbabilityAgreementAndSpread() {
        val field = parsePrecipitationField(payload(), points, MODELS)
        val centre = field.frames.first().cells[12]

        assertEquals(2, field.frames.size)
        assertEquals(0.2, requireNotNull(centre.precipitationMm), 0.0)
        assertEquals(67, centre.probabilityPercent)
        assertEquals(67, centre.agreementPercent)
        assertEquals(3, centre.contributorCount)
        assertEquals(0.0, requireNotNull(centre.minimumMm), 0.0)
        assertEquals(0.4, requireNotNull(centre.maximumMm), 0.0)
        assertEquals(PrecipitationKind.RAIN, centre.kind)
    }

    @Test
    fun appliesTheLocationPrecipitationWeightsToEveryCell() {
        val artifact = parseCalibrationArtifact(
            CALIBRATION,
            Instant.parse("2026-08-29T19:00:00Z").epochSecond,
        )

        val field = parsePrecipitationField(payload(), points, MODELS, LOCATION, artifact)
        val centre = field.frames.first().cells[12]

        assertEquals(0.32, requireNotNull(centre.precipitationMm), 0.0001)
        assertEquals(80, centre.probabilityPercent)
        assertEquals(80, centre.agreementPercent)
        assertEquals(2, centre.contributorCount)
    }

    @Test
    fun fallsBackToDiagnosticMedianWhenCalibratedModelsAreMissing() {
        val artifact = parseCalibrationArtifact(
            CALIBRATION.replace("\"c\"", "\"missing\""),
            Instant.parse("2026-08-29T19:00:00Z").epochSecond,
        )

        val field = parsePrecipitationField(payload(), points, MODELS, LOCATION, artifact)
        val centre = field.frames.first().cells[12]

        assertEquals(0.2, requireNotNull(centre.precipitationMm), 0.0)
        assertEquals(67, centre.probabilityPercent)
        assertEquals(3, centre.contributorCount)
    }

    @Test
    fun classifiesDrySnowMixedAndUnavailableCells() {
        val dry = parsePrecipitationField(payload(), points, MODELS).frames[1].cells[12]
        val snow = parsePrecipitationField(
            payload { hourly ->
                hourly.putModels("precipitation", 12, doubleArrayOf(1.0, 1.2, 1.4))
                hourly.putModels("rain", 12, doubleArrayOf(0.0, 0.0, 0.0))
                hourly.putModels("snowfall", 12, doubleArrayOf(0.4, 0.6, 0.8))
            },
            points,
            MODELS,
        ).frames.first().cells[12]
        val mixed = parsePrecipitationField(
            payload { hourly ->
                hourly.putModels("precipitation", 12, doubleArrayOf(1.0, 1.2, 1.4))
                hourly.putModels("rain", 12, doubleArrayOf(0.2, 0.3, 0.4))
                hourly.putModels("snowfall", 12, doubleArrayOf(0.2, 0.3, 0.4))
            },
            points,
            MODELS,
        ).frames.first().cells[12]
        val unavailable = parsePrecipitationField(
            payload { hourly -> hourly.getJSONArray("precipitation_c").put(0, JSONObject.NULL) },
            points,
            MODELS,
        ).frames.first().cells[12]

        assertEquals(PrecipitationKind.DRY, dry.kind)
        assertEquals(PrecipitationKind.SNOW, snow.kind)
        assertEquals(PrecipitationKind.MIXED, mixed.kind)
        assertEquals(PrecipitationKind.UNAVAILABLE, unavailable.kind)
    }

    @Test
    fun rejectsMalformedProviderPayloads() {
        val negative = payload { hourly -> hourly.getJSONArray("precipitation_a").put(0, -0.1) }
        val wrongUnits = JSONArray(payload()).also { root ->
            root.getJSONObject(0).getJSONObject("hourly_units").put("precipitation_a", "inch")
        }.toString()
        val wrongLocation = JSONArray(payload()).also { root ->
            root.getJSONObject(1).put("location_id", 2)
        }.toString()
        val mismatchedTime = JSONArray(payload()).also { root ->
            root.getJSONObject(1).getJSONObject("hourly").getJSONArray("time").put(1, TIMES[1] + 60)
        }.toString()
        val missingLocation = JSONArray(payload()).also { root -> root.remove(root.length() - 1) }.toString()

        listOf(negative, wrongUnits, wrongLocation, mismatchedTime, missingLocation).forEach { json ->
            assertThrows(JSONException::class.java) {
                parsePrecipitationField(json, points, MODELS)
            }
        }
    }

    private fun payload(changeCentre: (JSONObject) -> Unit = {}): String {
        val locations = JSONArray()
        points.forEachIndexed { index, point ->
            val hourly = JSONObject()
                .put("time", JSONArray(TIMES.toList()))
            VARIABLES.forEach { variable ->
                MODELS.forEach { model ->
                    hourly.put("${variable}_$model", JSONArray(listOf(0.0, 0.0)))
                }
            }
            if (index == 12) {
                hourly.putModels("precipitation", index, doubleArrayOf(0.0, 0.2, 0.4))
                hourly.putModels("rain", index, doubleArrayOf(0.0, 0.2, 0.4))
                changeCentre(hourly)
            }
            locations.put(
                JSONObject()
                    .put("latitude", point.latitude)
                    .put("longitude", point.longitude)
                    .apply { if (index > 0) put("location_id", index) }
                    .put("hourly_units", units())
                    .put("hourly", hourly),
            )
        }
        return locations.toString()
    }

    private fun JSONObject.putModels(variable: String, locationIndex: Int, values: DoubleArray) {
        MODELS.take(3).forEachIndexed { modelIndex, model ->
            getJSONArray("${variable}_$model").put(0, values[modelIndex])
        }
        if (locationIndex == 12) getJSONArray("${variable}_missing").put(0, JSONObject.NULL)
    }

    private fun units(): JSONObject = JSONObject().put("time", "unixtime").apply {
        VARIABLES.forEach { variable ->
            MODELS.forEach { model ->
                put("${variable}_$model", if (variable == "snowfall") "cm" else "mm")
            }
        }
    }

    companion object {
        private val LOCATION = CzechLocation("Praha", REGION_PRAGUE, 50.0755, 14.4378, "CZ")
        private val MODELS = listOf("a", "b", "c", "missing")
        private val VARIABLES = listOf("precipitation", "rain", "showers", "snowfall")
        private val TIMES = longArrayOf(1_788_030_000L, 1_788_033_600L)
        private val points = precipitationFieldPoints(LOCATION)
        private val CALIBRATION = """
            {
              "schema_version":2,
              "dataset_manifest_hash":"${"a".repeat(64)}",
              "model_contract_hash":"${"b".repeat(64)}",
              "generated_at":"2026-08-29T18:00:00Z",
              "expires_at":"2026-09-29T18:00:00Z",
              "models":[
                {"model_id":"a","maximum_run_age_hours":12,"resolution_km":10.0},
                {"model_id":"c","maximum_run_age_hours":12,"resolution_km":10.0}
              ],
              "segments":[{
                "selector":{
                  "region":"CZECHIA",
                  "variable":"precipitation",
                  "minimum_lead_hours":0,
                  "maximum_lead_hours":24,
                  "months":[8]
                },
                "truth_class":"radar_gauge",
                "mode":"blend",
                "weights":{"a":0.2,"c":0.8},
                "minimum_source_count":2,
                "fallback_model":"c",
                "holdout":{"accepted":true}
              }]
            }
        """.trimIndent()
    }
}
