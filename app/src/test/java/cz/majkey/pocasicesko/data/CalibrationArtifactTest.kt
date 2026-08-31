package cz.majkey.pocasicesko.data

import org.json.JSONException
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
                "holdout":{"accepted":true}
              }]
            }
        """.trimIndent()
    }
}
