package cz.majkey.pocasicesko.data

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.time.Instant
import java.util.zip.GZIPOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StaticForecastTileParserTest {
    @Test
    fun verifiesChecksumAndParsesSourceSeparatedValues() {
        val bytes = gzip(TILE.toByteArray())
        val tile = StaticForecastParser.parseTile(bytes, manifest(checksum(bytes)), PATH)

        assertEquals("20260829T120000Z", tile.runId)
        assertEquals(2, tile.values.size)
        assertEquals(20.5, tile.values[0].value)
        assertNull(tile.values[1].value)
    }

    @Test
    fun rejectsChecksumUnknownSourceAndDuplicateIdentity() {
        val bytes = gzip(TILE.toByteArray())
        assertFails("checksum") {
            StaticForecastParser.parseTile(bytes, manifest("a".repeat(64)), PATH)
        }
        assertFails("source") {
            val invalid = TILE.replace("dwd-icon-eu", "unknown-source")
            val compressed = gzip(invalid.toByteArray())
            StaticForecastParser.parseTile(compressed, manifest(checksum(compressed)), PATH)
        }
        assertFails("duplicate") {
            val row = TILE.substringAfter("\"values\":[").substringBefore("},") + "}"
            val invalid = TILE.replace("\"values\":[", "\"values\":[$row,")
            val compressed = gzip(invalid.toByteArray())
            StaticForecastParser.parseTile(compressed, manifest(checksum(compressed)), PATH)
        }
    }

    @Test
    fun rejectsRowsOutsideDeclaredTile() {
        val invalid = TILE.replaceFirst("\"longitude\":14.0", "\"longitude\":13.5")
        val compressed = gzip(invalid.toByteArray())

        assertFails("tile coordinates") {
            StaticForecastParser.parseTile(
                compressed,
                manifest(checksum(compressed)),
                PATH,
            )
        }
    }

    @Test
    fun rejectsMixedUnitsForOneVariable() {
        val invalid = TILE.replaceFirst("\"unit\":\"°C\"", "\"unit\":\"K\"")
        val compressed = gzip(invalid.toByteArray())

        assertFails("mixed units") {
            StaticForecastParser.parseTile(
                compressed,
                manifest(checksum(compressed)),
                PATH,
            )
        }
    }

    private fun manifest(tileChecksum: String): StaticForecastManifest = StaticForecastManifest(
        schemaVersion = 1,
        state = StaticFeedState.PRODUCTION,
        runId = "20260829T120000Z",
        generatedAt = Instant.parse("2026-08-29T12:00:00Z"),
        expiresAt = Instant.parse("2026-08-29T18:00:00Z"),
        grid = StaticFeedGrid(48.45, 51.2, 11.9, 19.0, 0.05, 0.5),
        sources = listOf(StaticFeedSource("dwd-icon-eu", "dwd_icon_eu")),
        tileChecksums = mapOf(PATH to tileChecksum),
        calibrationChecksum = "b".repeat(64),
        datasetManifestHash = "c".repeat(64),
    )

    private fun checksum(value: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(value)
        .joinToString("") { "%02x".format(it) }

    private fun gzip(value: ByteArray): ByteArray = ByteArrayOutputStream().use { output ->
        GZIPOutputStream(output).use { it.write(value) }
        output.toByteArray()
    }

    private fun assertFails(message: String, block: () -> Unit) {
        val error = runCatching(block).exceptionOrNull()
        assertTrue("Expected IllegalArgumentException, got $error", error is IllegalArgumentException)
        assertTrue(
            "Expected message containing '$message', got '${error?.message}'",
            error?.message.orEmpty().contains(message, ignoreCase = true),
        )
    }

    companion object {
        private const val PATH = "tiles/20260829T120000Z/1/4.json.gz"
        private val TILE = """
            {
              "schema_version":1,
              "run_id":"20260829T120000Z",
              "tile_x":4,
              "tile_y":1,
              "values":[
                {"source_id":"dwd-icon-eu","model_id":"dwd_icon_eu","run_time":"2026-08-29T12:00:00Z","valid_time":"2026-08-29T13:00:00Z","latitude":49.0,"longitude":14.0,"elevation_m":250.0,"variable":"temperature_2m","value":20.5,"unit":"°C"},
                {"source_id":"dwd-icon-eu","model_id":"dwd_icon_eu","run_time":"2026-08-29T12:00:00Z","valid_time":"2026-08-29T14:00:00Z","latitude":49.0,"longitude":14.0,"elevation_m":250.0,"variable":"temperature_2m","value":null,"unit":"°C"}
              ]
            }
        """.trimIndent()
    }
}
