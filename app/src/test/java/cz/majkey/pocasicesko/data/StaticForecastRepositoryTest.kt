package cz.majkey.pocasicesko.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.time.Instant
import java.util.zip.GZIPOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StaticForecastRepositoryTest {
    @Test
    fun requestsPublicManifestAndRejectsDiagnosticFeed() {
        var requestedUrl = ""
        val repository = StaticForecastRepository { url ->
            requestedUrl = url
            DIAGNOSTIC_MANIFEST
        }

        val error = runCatching {
            repository.fetchUsableManifest(Instant.parse("2026-08-29T15:00:00Z"))
        }.exceptionOrNull()

        assertEquals("https://majkey25.github.io/Selia-Weather/data/v1/manifest.json", requestedUrl)
        assertTrue(error is StaticForecastUnavailableException)
        assertTrue(error?.message.orEmpty().contains("diagnostic"))
    }

    @Test
    fun returnsFreshProductionManifest() {
        val repository = StaticForecastRepository { PRODUCTION_MANIFEST }

        val manifest = repository.fetchUsableManifest(Instant.parse("2026-08-29T15:00:00Z"))

        assertEquals(StaticFeedState.PRODUCTION, manifest.state)
    }

    @Test
    fun fetchesAndInterpolatesTheRequiredTile() {
        val tile = gzip(TILE.toByteArray())
        val path = "tiles/20260829T120000Z/1/4.json.gz"
        val requestedTiles = mutableListOf<String>()
        val repository = StaticForecastRepository(
            fetchText = { manifest("production", checksum(tile), path) },
            fetchBytes = { url ->
                requestedTiles += url
                tile
            },
        )

        val values = repository.fetchInterpolatedValues(
            Instant.parse("2026-08-29T15:00:00Z"),
            latitude = 49.025,
            longitude = 14.025,
        )

        assertEquals(25.0, requireNotNull(values.single().value), 1e-9)
        assertEquals(listOf("https://majkey25.github.io/Selia-Weather/data/v1/$path"), requestedTiles)
    }

    @Test
    fun selectsEveryTileNeededAcrossTileBoundaries() {
        val manifest = StaticForecastParser.parseManifest(PRODUCTION_MANIFEST)

        val paths = requiredTilePaths(manifest, latitude = 48.925, longitude = 12.375)

        assertEquals(
            listOf(
                "tiles/20260829T120000Z/0/0.json.gz",
                "tiles/20260829T120000Z/0/1.json.gz",
                "tiles/20260829T120000Z/1/0.json.gz",
                "tiles/20260829T120000Z/1/1.json.gz",
            ),
            paths,
        )
    }

    @Test
    fun boundedReaderRejectsOversizedPayload() {
        val error = runCatching {
            readLimited(ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)), maxBytes = 3)
        }.exceptionOrNull()

        assertTrue(error is java.io.IOException)
    }

    companion object {
        private val SOURCE = """
            [{"source_id":"noaa-gfs","model_id":"noaa_gfs","enabled":true,"commercial_redistribution":true}]
        """.trimIndent()

        private val DIAGNOSTIC_MANIFEST = manifest(
            state = "diagnostic",
            calibration = "null",
            dataset = "null",
            tiles = "{}",
        )

        private val PRODUCTION_MANIFEST = manifest(
            state = "production",
            calibration = "\"${"b".repeat(64)}\"",
            dataset = "\"${"c".repeat(64)}\"",
            tiles = "{\"tiles/20260829T120000Z/0/0.json.gz\":\"${"a".repeat(64)}\"}",
        )

        private fun manifest(state: String, checksum: String, path: String): String = manifest(
            state = state,
            calibration = "\"${"b".repeat(64)}\"",
            dataset = "\"${"c".repeat(64)}\"",
            tiles = "{\"$path\":\"$checksum\"}",
        )

        private fun manifest(state: String, calibration: String, dataset: String, tiles: String) = """
            {
              "schema_version":1,
              "calibration_checksum":$calibration,
              "dataset_manifest_hash":$dataset,
              "grid":{"south":48.45,"north":51.2,"west":11.9,"east":19.0,"step":0.05,"tile_step":0.5},
              "run":{"run_id":"20260829T120000Z","generated_at":"2026-08-29T12:00:00Z","expires_at":"2026-08-29T18:00:00Z","state":"$state"},
              "sources":$SOURCE,
              "tile_checksums":$tiles
            }
        """.trimIndent()

        private fun checksum(value: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(value)
            .joinToString("") { "%02x".format(it) }

        private fun gzip(value: ByteArray): ByteArray = ByteArrayOutputStream().use { output ->
            GZIPOutputStream(output).use { it.write(value) }
            output.toByteArray()
        }

        private val TILE = """
            {
              "schema_version":1,
              "run_id":"20260829T120000Z",
              "tile_x":4,
              "tile_y":1,
              "values":[
                {"source_id":"noaa-gfs","model_id":"noaa_gfs","run_time":"2026-08-29T12:00:00Z","valid_time":"2026-08-29T13:00:00Z","latitude":49.0,"longitude":14.0,"elevation_m":250.0,"variable":"temperature_2m","value":10.0,"unit":"°C"},
                {"source_id":"noaa-gfs","model_id":"noaa_gfs","run_time":"2026-08-29T12:00:00Z","valid_time":"2026-08-29T13:00:00Z","latitude":49.0,"longitude":14.05,"elevation_m":250.0,"variable":"temperature_2m","value":20.0,"unit":"°C"},
                {"source_id":"noaa-gfs","model_id":"noaa_gfs","run_time":"2026-08-29T12:00:00Z","valid_time":"2026-08-29T13:00:00Z","latitude":49.05,"longitude":14.0,"elevation_m":250.0,"variable":"temperature_2m","value":30.0,"unit":"°C"},
                {"source_id":"noaa-gfs","model_id":"noaa_gfs","run_time":"2026-08-29T12:00:00Z","valid_time":"2026-08-29T13:00:00Z","latitude":49.05,"longitude":14.05,"elevation_m":250.0,"variable":"temperature_2m","value":40.0,"unit":"°C"}
              ]
            }
        """.trimIndent()
    }
}
