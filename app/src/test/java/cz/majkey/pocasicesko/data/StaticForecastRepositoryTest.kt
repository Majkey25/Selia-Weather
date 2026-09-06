package cz.majkey.pocasicesko.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.CancellationException
import java.util.zip.GZIPOutputStream
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class StaticForecastRepositoryTest {
    @Test
    fun interruptedRefreshStopsBeforeConsumingResponseBytes() {
        val source = ByteArrayInputStream(byteArrayOf(1, 2, 3))
        Thread.currentThread().interrupt()
        try {
            val error = runCatching { readLimited(source, maxBytes = 3) }.exceptionOrNull()
            assertTrue(error is CancellationException)
            assertTrue(Thread.currentThread().isInterrupted)
            assertEquals(3, source.available())
        } finally {
            Thread.interrupted()
        }
        assertEquals(3, readLimited(source, maxBytes = 3).size)
    }

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
    fun optionalCalibrationDoesNotBreakForecastsWhenFeedIsUnavailable() {
        val invalidTime = PRODUCTION_MANIFEST.replace("2026-08-29T12:00:00Z", "invalid-time")
        listOf(DIAGNOSTIC_MANIFEST, "not-json", invalidTime).forEach { response ->
            val repository = StaticForecastRepository { response }
            assertEquals(null, repository.fetchForLocation(
                CzechLocation("Prague", REGION_PRAGUE, 50.0755, 14.4378, "CZ"),
                Instant.parse("2026-08-29T15:00:00Z"),
            ))
        }
        val repository = StaticForecastRepository { throw java.io.IOException("offline") }
        assertEquals(null, repository.fetchForLocation(
            CzechLocation("Tokyo", REGION_WORLD, 35.6762, 139.6503, "JP"),
            Instant.parse("2026-08-29T15:00:00Z"),
        ))
    }

    @Test
    fun doesNotDownloadCalibrationOrTilesOutsidePublishedGrid() {
        val repository = StaticForecastRepository(
            fetchText = { PRODUCTION_MANIFEST },
            fetchBytes = { error("Out-of-area location must not request forecast tiles") },
        )
        assertEquals(null, repository.fetchForLocation(
            CzechLocation("Tokyo", REGION_WORLD, 35.6762, 139.6503, "JP"),
            Instant.parse("2026-08-29T15:00:00Z"),
        ))
    }

    @Test
    fun returnsFreshProductionManifest() {
        val repository = StaticForecastRepository { PRODUCTION_MANIFEST }

        val manifest = repository.fetchUsableManifest(Instant.parse("2026-08-29T15:00:00Z"))

        assertEquals(StaticFeedState.PRODUCTION, manifest.state)
    }

    @Test
    fun cachesDiagnosticManifestBrieflyButRechecksAfterExpiry() {
        var requests = 0
        val repository = StaticForecastRepository(
            fetchBytes = { byteArrayOf() },
            fetchText = {
                requests++
                if (requests == 1) DIAGNOSTIC_MANIFEST else PRODUCTION_MANIFEST
            },
        )
        val now = Instant.parse("2026-08-29T15:00:00Z")
        val location = CzechLocation("Prague", REGION_PRAGUE, 50.0755, 14.4378, "CZ")
        assertEquals(null, repository.fetchForLocation(location, now))
        assertEquals(null, repository.fetchForLocation(location, now.plusSeconds(60)))
        assertEquals(1, requests)
        assertEquals(StaticFeedState.PRODUCTION, repository.fetchUsableManifest(now.plusSeconds(901)).state)
        assertEquals(2, requests)
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
    fun verifiedFeedRecalculatesAnHourlyForecastAtTheRequestedCoordinate() {
        val tileJson = JSONObject(TILE)
        val rows = tileJson.getJSONArray("values")
        for (index in 0 until rows.length()) {
            val original = rows.getJSONObject(index)
            rows.put(JSONObject(original.toString()).put("source_id", "ecmwf")
                .put("model_id", "ecmwf_ifs025").put("value", original.getDouble("value") + 10.0))
        }
        val tile = gzip(tileJson.toString().toByteArray())
        val artifact = CalibrationArtifactTest.VALID_ARTIFACT
            .replace("\"AFRICA\"", "\"CZECHIA\"").replace("gfs_seamless", "noaa_gfs").toByteArray()
        val path = "tiles/20260829T120000Z/1/4.json.gz"
        val manifest = JSONObject(manifest("production", checksum(tile), path))
            .put("calibration_checksum", checksum(artifact)).put("dataset_manifest_hash", "a".repeat(64))
            .put("sources", JSONArray(SOURCE).put(JSONObject()
                .put("source_id", "ecmwf").put("model_id", "ecmwf_ifs025")
                .put("enabled", true).put("commercial_redistribution", true)))
        val repository = StaticForecastRepository(
            fetchText = { manifest.toString() },
            fetchBytes = { if (it.endsWith("ensemble_weights.json")) artifact else tile },
        )
        val location = CzechLocation("Test point", REGION_PRAGUE, 49.025, 14.025, "CZ")
        val now = Instant.parse("2026-08-29T13:00:00Z")
        val input = requireNotNull(repository.fetchForLocation(location, now))
        val base = """{"timezone":"UTC","utc_offset_seconds":0,
            "current":{"time":"2026-08-29T13:00","temperature_2m":99},
            "hourly":{"time":["2026-08-29T13:00"],"temperature_2m":[99]},
            "daily":{"time":["2026-08-29"],"temperature_2m_max":[99],"temperature_2m_min":[99]}}
        """.trimIndent()
        val live = """{"hourly":{"time":["2026-08-29T13:00"],"temperature_2m_noaa_gfs":[99]}}"""
        val result = blendModelForecast(base, live, location, input.artifact, input.values, now)
        assertEquals(ForecastCalculationMode.CALIBRATED, result.mode)
        assertEquals(31.0, JSONObject(result.json).getJSONObject("current").getDouble("temperature_2m"), 1e-9)
        assertEquals(31.0, JSONObject(result.json).getJSONObject("daily").getJSONArray("temperature_2m_max").getDouble(0), 1e-9)
        assertEquals(1, result.calibratedValueCount)
    }

    @Test
    fun fetchesCalibrationOnlyAfterVerifyingItsManifestChecksum() {
        val artifact = CalibrationArtifactTest.VALID_ARTIFACT.toByteArray()
        val requestedUrls = mutableListOf<String>()
        val repository = StaticForecastRepository(
            fetchText = {
                manifest(
                    state = "production",
                    calibration = "\"${checksum(artifact)}\"",
                    dataset = "\"${"a".repeat(64)}\"",
                    tiles = "{\"tiles/20260829T120000Z/0/0.json.gz\":\"${"a".repeat(64)}\"}",
                    sources = CALIBRATION_SOURCES,
                )
            },
            fetchBytes = { url ->
                requestedUrls += url
                artifact
            },
        )

        val calibration = repository.fetchCalibrationArtifact(
            Instant.ofEpochSecond(CalibrationArtifactTest.NOW),
        )

        assertEquals(2, calibration.schemaVersion)
        assertEquals(
            listOf("https://majkey25.github.io/Selia-Weather/data/v1/calibration/ensemble_weights.json"),
            requestedUrls,
        )
    }

    @Test
    fun rejectsCalibrationWhoseBytesDoNotMatchTheManifest() {
        val repository = StaticForecastRepository(
            fetchText = {
                manifest(
                    state = "production",
                    calibration = "\"${"f".repeat(64)}\"",
                    dataset = "\"${"c".repeat(64)}\"",
                    tiles = "{\"tiles/20260829T120000Z/0/0.json.gz\":\"${"a".repeat(64)}\"}",
                )
            },
            fetchBytes = { CalibrationArtifactTest.VALID_ARTIFACT.toByteArray() },
        )

        val error = runCatching {
            repository.fetchCalibrationArtifact(Instant.ofEpochSecond(CalibrationArtifactTest.NOW))
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertTrue(error?.message.orEmpty().contains("checksum"))
    }

    @Test
    fun rejectsCalibrationWithDifferentDatasetOrUnpublishedModelContracts() {
        val artifact = CalibrationArtifactTest.VALID_ARTIFACT.toByteArray()
        for ((dataset, sources) in listOf("c".repeat(64) to CALIBRATION_SOURCES, "a".repeat(64) to SOURCE)) {
            val repository = StaticForecastRepository(
                fetchText = {
                    manifest(
                        "production", "\"${checksum(artifact)}\"", "\"$dataset\"",
                        "{\"tiles/20260829T120000Z/0/0.json.gz\":\"${"a".repeat(64)}\"}", sources,
                    )
                },
                fetchBytes = { artifact },
            )
            assertThrows(IllegalArgumentException::class.java) {
                repository.fetchCalibrationArtifact(Instant.ofEpochSecond(CalibrationArtifactTest.NOW))
            }
        }
    }

    @Test
    fun loadsCalibrationAndTilesAgainstOneVerifiedManifestWithoutRefetching() {
        val artifact = CalibrationArtifactTest.VALID_ARTIFACT.toByteArray()
        val tile = gzip(TILE.toByteArray())
        val path = "tiles/20260829T120000Z/1/4.json.gz"
        val sources = JSONArray(CALIBRATION_SOURCES).put(JSONArray(SOURCE).getJSONObject(0)).toString()
        var manifestRequests = 0
        val repository = StaticForecastRepository(
            fetchText = {
                manifestRequests++
                check(manifestRequests == 1) { "Manifest was fetched again during one forecast." }
                manifest("production", "\"${checksum(artifact)}\"", "\"${"a".repeat(64)}\"",
                    JSONObject().put(path, checksum(tile)).toString(), sources)
            },
            fetchBytes = { url -> if (url.endsWith(".json.gz")) tile else artifact },
        )
        val now = Instant.ofEpochSecond(CalibrationArtifactTest.NOW)
        val verifiedManifest = repository.fetchUsableManifest(now)
        val calibration = repository.fetchCalibrationArtifact(verifiedManifest, now)
        val values = repository.fetchInterpolatedValues(verifiedManifest, now, 49.025, 14.025)

        assertEquals(1, manifestRequests)
        assertEquals(verifiedManifest.datasetManifestHash, calibration.datasetManifestHash)
        assertEquals(25.0, requireNotNull(values.single().value), 1e-9)
    }

    @Test
    fun suppliedManifestStillRequiresProductionAndFreshnessBeforeFetchingBytes() {
        val now = Instant.ofEpochSecond(CalibrationArtifactTest.NOW)
        val production = StaticForecastParser.parseManifest(PRODUCTION_MANIFEST)
        var requests = 0
        val repository = StaticForecastRepository(fetchBytes = { requests++; byteArrayOf() })
        for (manifest in listOf(
            production.copy(state = StaticFeedState.DIAGNOSTIC),
            production.copy(expiresAt = now),
        )) {
            assertThrows(StaticForecastUnavailableException::class.java) {
                repository.fetchCalibrationArtifact(manifest, now)
            }
            assertThrows(StaticForecastUnavailableException::class.java) {
                repository.fetchInterpolatedValues(manifest, now, 49.025, 14.025)
            }
        }
        assertEquals(0, requests)
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
        private val CALIBRATION_SOURCES = """
            [{"source_id":"ecmwf-ifs","model_id":"ecmwf_ifs025","enabled":true,"commercial_redistribution":true},
             {"source_id":"noaa-gfs-seamless","model_id":"gfs_seamless","enabled":true,"commercial_redistribution":true}]
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

        private fun manifest(state: String, calibration: String, dataset: String, tiles: String, sources: String = SOURCE) = """
            {
              "schema_version":1,
              "calibration_checksum":$calibration,
              "dataset_manifest_hash":$dataset,
              "grid":{"south":48.45,"north":51.2,"west":11.9,"east":19.0,"step":0.05,"tile_step":0.5},
              "run":{"run_id":"20260829T120000Z","generated_at":"2026-08-29T12:00:00Z","expires_at":"2026-08-29T18:00:00Z","state":"$state"},
              "sources":$sources,
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
