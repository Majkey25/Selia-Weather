package cz.majkey.pocasicesko.data

import org.json.JSONException
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.Instant

class CalibrationArtifactTest {
    @Test
    fun selectsExactRegionVariableLeadAndSeason() {
        val artifact = parseCalibrationArtifact(VALID_ARTIFACT, NOW)

        val segment = requireNotNull(
            artifact.segment(ForecastRegion.AFRICA, "temperature_2m", leadHours = 24, month = 8),
        )

        assertEquals(
            mapOf("ecmwf_ifs025" to 0.6, "gfs_seamless" to 0.4),
            segment.weights,
        )
        assertEquals(2, segment.minimumContributors)
        assertEquals("gfs_seamless", segment.fallbackModelId)
        assertEquals(CalibrationTruthClass.STATION, segment.truthClass)
    }

    @Test
    fun doesNotUseSegmentOutsideItsSelector() {
        val artifact = parseCalibrationArtifact(VALID_ARTIFACT, NOW)

        assertEquals(
            null,
            artifact.segment(ForecastRegion.AFRICA, "temperature_2m", leadHours = 25, month = 8),
        )
        assertEquals(
            null,
            artifact.segment(ForecastRegion.AFRICA, "temperature_2m", leadHours = 24, month = 1),
        )
        assertEquals(
            null,
            artifact.segment(ForecastRegion.EUROPE, "temperature_2m", leadHours = 24, month = 8),
        )
    }

    @Test
    fun rejectsExpiredUnknownOrUnnormalizedArtifacts() {
        assertThrows(JSONException::class.java) {
            parseCalibrationArtifact(VALID_ARTIFACT, 1_800_000_000L)
        }
        assertThrows(JSONException::class.java) {
            parseCalibrationArtifact(
                VALID_ARTIFACT.replace("\"gfs_seamless\":0.4", "\"unknown\":0.4"),
                NOW,
            )
        }
        assertThrows(JSONException::class.java) {
            parseCalibrationArtifact(
                VALID_ARTIFACT.replace("\"gfs_seamless\":0.4", "\"gfs_seamless\":0.5"),
                NOW,
            )
        }
    }

    @Test
    fun requiresThirtyOrMoreActualHoldoutSamples() {
        for (count in listOf(null, 0, 29, 30.5, "30", true)) {
            val root = JSONObject(VALID_ARTIFACT)
            val holdout = root.getJSONArray("segments").getJSONObject(0).getJSONObject("holdout")
            if (count == null) holdout.remove("sample_count") else holdout.put("sample_count", count)
            assertThrows(JSONException::class.java) { parseCalibrationArtifact(root.toString(), NOW) }
        }
        assertEquals(1, parseCalibrationArtifact(VALID_ARTIFACT, NOW).segments.size)
    }

    @Test
    fun rejectsOverlappingScopesIncludingSharedLeadEndpoints() {
        for (minimumLead in listOf(1, 12, 24)) {
            val json = additionalSegment {
                put("minimum_lead_hours", minimumLead)
                put("maximum_lead_hours", 36)
                put("months", JSONArray(listOf(8, 9)))
            }
            assertThrows(JSONException::class.java) { parseCalibrationArtifact(json, NOW) }
        }
    }

    @Test
    fun allowsDisjointLeadMonthsRegionOrVariable() {
        val variants = listOf<(JSONObject) -> Unit>(
            { it.put("minimum_lead_hours", 25).put("maximum_lead_hours", 48) },
            { it.put("months", JSONArray(listOf(9))) },
            { it.put("region", "EUROPE") },
            { it.put("variable", "dew_point_2m") },
        )
        variants.forEach { change ->
            assertEquals(2, parseCalibrationArtifact(additionalSegment(change), NOW).segments.size)
        }
    }

    private fun additionalSegment(change: JSONObject.() -> Unit): String {
        val root = JSONObject(VALID_ARTIFACT)
        val segments = root.getJSONArray("segments")
        val second = JSONObject(segments.getJSONObject(0).toString())
        second.getJSONObject("selector").change()
        segments.put(second)
        return root.toString()
    }

    companion object {
        internal val NOW = Instant.parse("2026-08-29T15:00:00Z").epochSecond
        internal val VALID_ARTIFACT = """
            {
              "schema_version":2,
              "dataset_manifest_hash":"${"a".repeat(64)}",
              "model_contract_hash":"${"b".repeat(64)}",
              "generated_at":"2026-08-29T12:00:00Z",
              "expires_at":"2026-09-29T12:00:00Z",
              "models":[
                {"model_id":"ecmwf_ifs025","maximum_run_age_hours":12,"resolution_km":25.0},
                {"model_id":"gfs_seamless","maximum_run_age_hours":12,"resolution_km":13.0}
              ],
              "segments":[{
                "selector":{
                  "region":"AFRICA",
                  "variable":"temperature_2m",
                  "minimum_lead_hours":1,
                  "maximum_lead_hours":24,
                  "months":[6,7,8]
                },
                "truth_class":"station",
                "mode":"blend",
                "weights":{"ecmwf_ifs025":0.6,"gfs_seamless":0.4},
                "minimum_source_count":2,
                "fallback_model":"gfs_seamless",
                "holdout":{"accepted":true,"sample_count":30}
              }]
            }
        """.trimIndent()
    }
}
