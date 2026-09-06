package cz.majkey.pocasicesko.data

import cz.majkey.pocasicesko.BuildConfig
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.DateTimeException
import kotlin.math.abs
import kotlin.math.floor
import org.json.JSONException

internal class StaticForecastRepository(
    private val fetchBytes: (String) -> ByteArray = { request(it, MAX_TILE_BYTES) },
    private val fetchText: (String) -> String = {
        request(it, MAX_MANIFEST_BYTES).toString(Charsets.UTF_8)
    },
) {
    private var cachedManifest: StaticForecastManifest? = null
    private var manifestFetchedAt: Instant? = null

    fun fetchForLocation(location: CzechLocation, now: Instant): CalibratedForecastInput? = try {
        val manifest = fetchUsableManifest(now)
        if (location.latitude !in manifest.grid.south..manifest.grid.north ||
            location.longitude !in manifest.grid.west..manifest.grid.east
        ) {
            null
        } else {
            val artifact = fetchCalibrationArtifact(manifest, now)
            require((forecastApiModelsFor(location) + artifact.models.map { it.modelId }).distinct().size <= MAX_FORECAST_MODEL_IDS) {
                "Combined forecast has too many model identities."
            }
            if (artifact.segments.none { it.region == forecastRegionFor(location) }) {
                null
            } else {
                CalibratedForecastInput(
                    artifact,
                    fetchInterpolatedValues(manifest, now, location.latitude, location.longitude),
                )
            }
        }
    } catch (_: IOException) {
        null
    } catch (_: JSONException) {
        null
    } catch (_: DateTimeException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    @Synchronized
    fun fetchUsableManifest(now: Instant): StaticForecastManifest {
        val fetchedAt = manifestFetchedAt
        val manifest = cachedManifest?.takeIf {
            fetchedAt != null && !now.isBefore(fetchedAt) && now.isBefore(fetchedAt.plusSeconds(MANIFEST_CACHE_SECONDS))
        } ?: StaticForecastParser.parseManifest(fetchText(MANIFEST_URL)).also {
            cachedManifest = it
            manifestFetchedAt = now
        }
        requireUsableManifest(manifest, now)
        return manifest
    }

    fun fetchInterpolatedValues(now: Instant, latitude: Double, longitude: Double): List<StaticModelValue> =
        fetchInterpolatedValues(fetchUsableManifest(now), now, latitude, longitude)

    fun fetchInterpolatedValues(
        manifest: StaticForecastManifest,
        now: Instant,
        latitude: Double,
        longitude: Double,
    ): List<StaticModelValue> {
        requireUsableManifest(manifest, now)
        val values = requiredTilePaths(manifest, latitude, longitude).flatMap { path ->
            require(path in manifest.tileChecksums) { "Required forecast tile is absent from the manifest." }
            StaticForecastParser.parseTile(fetchBytes(BASE_URL + path), manifest, path).values
        }
        return interpolateStaticValues(values, manifest.grid, latitude, longitude)
    }

    fun fetchCalibrationArtifact(now: Instant): CalibrationArtifact =
        fetchCalibrationArtifact(fetchUsableManifest(now), now)

    fun fetchCalibrationArtifact(manifest: StaticForecastManifest, now: Instant): CalibrationArtifact {
        requireUsableManifest(manifest, now)
        val expectedChecksum = requireNotNull(manifest.calibrationChecksum)
        val bytes = fetchBytes(BASE_URL + CALIBRATION_PATH)
        if (bytes.size > MAX_CALIBRATION_BYTES) {
            throw IOException("Calibration payload is too large.")
        }
        require(sha256Hex(bytes) == expectedChecksum) { "Calibration checksum mismatch." }
        val artifact = parseCalibrationArtifact(bytes.toString(Charsets.UTF_8), now.epochSecond)
        require(artifact.datasetManifestHash == manifest.datasetManifestHash) {
            "Calibration dataset does not match its manifest."
        }
        val publishedModels = manifest.sources.map(StaticFeedSource::modelId).toSet()
        require(artifact.models.all { it.modelId in publishedModels }) {
            "Calibration model is not published by its manifest."
        }
        return artifact
    }

    private fun requireUsableManifest(manifest: StaticForecastManifest, now: Instant) {
        if (!manifest.isUsableAt(now)) {
            throw StaticForecastUnavailableException(
                "Static forecast is ${manifest.state.name.lowercase()} or outside its validity window.",
            )
        }
    }

    companion object {
        private const val BASE_URL = "https://majkey25.github.io/Selia-Weather/data/v1/"
        internal const val MANIFEST_URL = "${BASE_URL}manifest.json"
        private const val CALIBRATION_PATH = "calibration/ensemble_weights.json"
        private const val CONNECT_TIMEOUT_MILLIS = 5_000
        private const val READ_TIMEOUT_MILLIS = 10_000
        private const val MAX_MANIFEST_BYTES = 1_000_000
        private const val MAX_CALIBRATION_BYTES = 2_000_000
        private const val MAX_TILE_BYTES = 20_000_000
        private const val MANIFEST_CACHE_SECONDS = 15 * 60L

        private fun request(url: String, maxBytes: Int): ByteArray {
            val connection = URL(url).openConnection() as HttpURLConnection
            return try {
                connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
                connection.readTimeout = READ_TIMEOUT_MILLIS
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty(
                    "User-Agent",
                    "Selia-Weather/${BuildConfig.VERSION_NAME} (Android; Majkey25/Selia-Weather)",
                )
                if (connection.responseCode !in 200..299) {
                    throw IOException("Static forecast returned HTTP ${connection.responseCode}.")
                }
                connection.inputStream.use { readLimited(it, maxBytes) }
            } finally {
                connection.disconnect()
            }
        }
    }
}

internal data class CalibratedForecastInput(
    val artifact: CalibrationArtifact,
    val values: List<StaticModelValue>,
)

internal class StaticForecastUnavailableException(message: String) : IOException(message)

internal fun requiredTilePaths(
    manifest: StaticForecastManifest,
    latitude: Double,
    longitude: Double,
): List<String> {
    val grid = manifest.grid
    require(latitude.isFinite() && longitude.isFinite()) { "Forecast coordinates must be finite." }
    require(latitude in grid.south..grid.north && longitude in grid.west..grid.east) {
        "Forecast coordinates are outside the grid."
    }
    val latitudes = surroundingGridCoordinates(latitude, grid.south, grid.step)
    val longitudes = surroundingGridCoordinates(longitude, grid.west, grid.step)
    return latitudes.flatMap { gridLatitude ->
        longitudes.map { gridLongitude ->
            val tileY = floor((gridLatitude - grid.south) / grid.tileStep).toInt()
            val tileX = floor((gridLongitude - grid.west) / grid.tileStep).toInt()
            "tiles/${manifest.runId}/$tileY/$tileX.json.gz"
        }
    }.distinct().sorted()
}

private fun surroundingGridCoordinates(value: Double, minimum: Double, step: Double): List<Double> {
    val lower = minimum + floor((value - minimum) / step) * step
    return if (abs(value - lower) < GRID_EPSILON) listOf(lower) else listOf(lower, lower + step)
}

internal fun readLimited(input: InputStream, maxBytes: Int): ByteArray {
    require(maxBytes > 0) { "maxBytes must be positive." }
    val output = ByteArrayOutputStream(minOf(maxBytes, DEFAULT_BUFFER_SIZE))
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
        ensureForecastThreadActive()
        val count = input.read(buffer)
        if (count < 0) return output.toByteArray()
        if (output.size() > maxBytes - count) throw IOException("Static forecast payload is too large.")
        output.write(buffer, 0, count)
    }
}

private const val GRID_EPSILON = 1e-8
