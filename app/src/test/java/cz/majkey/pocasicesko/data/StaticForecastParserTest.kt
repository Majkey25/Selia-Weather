package cz.majkey.pocasicesko.data

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StaticForecastParserTest {
    @Test
    fun parsesDiagnosticManifestButDoesNotMarkItUsable() {
        val manifest = StaticForecastParser.parseManifest(DIAGNOSTIC_MANIFEST)

        assertEquals(StaticFeedState.DIAGNOSTIC, manifest.state)
        assertEquals(2, manifest.sources.size)
        assertFalse(manifest.isUsableAt(Instant.parse("2026-08-29T15:00:00Z")))
    }

    @Test
    fun productionManifestRequiresCalibrationTilesAndFreshness() {
        val manifest = StaticForecastParser.parseManifest(PRODUCTION_MANIFEST)

        assertTrue(manifest.isUsableAt(Instant.parse("2026-08-29T15:00:00Z")))
        assertFalse(manifest.isUsableAt(Instant.parse("2026-08-29T18:00:00Z")))
        assertEquals(
            "a".repeat(64),
            manifest.tileChecksums.getValue("tiles/20260829T120000Z/0/0.json"),
        )
    }

    @Test
    fun rejectsUnsupportedOrMalformedManifest() {
        assertFails("schema") {
            StaticForecastParser.parseManifest(DIAGNOSTIC_MANIFEST.replace("\"schema_version\":1", "\"schema_version\":2"))
        }
        assertFails("checksum") {
            StaticForecastParser.parseManifest(PRODUCTION_MANIFEST.replace("\"${"a".repeat(64)}\"", "\"bad\""))
        }
        assertFails("grid") {
            StaticForecastParser.parseManifest(DIAGNOSTIC_MANIFEST.replace("\"step\":0.05", "\"step\":0"))
        }
    }

    private fun assertFails(message: String, block: () -> Unit) {
        val error = runCatching(block).exceptionOrNull()
        assertTrue("Expected failure containing $message", error is IllegalArgumentException)
        assertTrue(error?.message.orEmpty().contains(message, ignoreCase = true))
    }

    companion object {
        private val SOURCES = """
            [
              {"source_id":"dwd-icon-eu","model_id":"dwd_icon_eu","enabled":true,"commercial_redistribution":true},
              {"source_id":"noaa-gfs","model_id":"noaa_gfs","enabled":true,"commercial_redistribution":true}
            ]
        """.trimIndent()

        private val DIAGNOSTIC_MANIFEST = """
            {
              "schema_version":1,
              "calibration_checksum":null,
              "dataset_manifest_hash":null,
              "grid":{"south":48.45,"north":51.2,"west":11.9,"east":19.0,"step":0.05,"tile_step":0.5},
              "run":{"run_id":"20260829T120000Z","generated_at":"2026-08-29T12:00:00Z","expires_at":"2026-08-29T18:00:00Z","state":"diagnostic"},
              "sources":$SOURCES,
              "tile_checksums":{}
            }
        """.trimIndent()

        private val PRODUCTION_MANIFEST = """
            {
              "schema_version":1,
              "calibration_checksum":"${"b".repeat(64)}",
              "dataset_manifest_hash":"${"c".repeat(64)}",
              "grid":{"south":48.45,"north":51.2,"west":11.9,"east":19.0,"step":0.05,"tile_step":0.5},
              "run":{"run_id":"20260829T120000Z","generated_at":"2026-08-29T12:00:00Z","expires_at":"2026-08-29T18:00:00Z","state":"production"},
              "sources":$SOURCES,
              "tile_checksums":{"tiles/20260829T120000Z/0/0.json":"${"a".repeat(64)}"}
            }
        """.trimIndent()
    }
}
