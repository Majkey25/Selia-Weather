package cz.majkey.pocasicesko.data

import cz.majkey.pocasicesko.BuildConfig
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import kotlin.math.abs
import kotlin.math.floor

internal class StaticForecastRepository(
    private val fetchBytes: (String) -> ByteArray = { request(it, MAX_TILE_BYTES) },
    private val fetchText: (String) -> String = {
        request(it, MAX_MANIFEST_BYTES).toString(Charsets.UTF_8)
    },
) {
    fun fetchUsableManifest(now: Instant): StaticForecastManifest {
        val manifest = StaticForecastParser.parseManifest(fetchText(MANIFEST_URL))
        if (!manifest.isUsableAt(now)) {
            throw StaticForecastUnavailableException(
                "Static forecast is ${manifest.state.name.lowercase()} or outside its validity window.",
            )
        }
        return manifest
    }

    fun fetchInterpolatedValues(now: Instant, latitude: Double, longitude: Double): List<StaticModelValue> {
        val manifest = fetchUsableManifest(now)
        val values = requiredTilePaths(manifest, latitude, longitude).flatMap { path ->
            require(path in manifest.tileChecksums) { "Required forecast tile is absent from the manifest." }
            StaticForecastParser.parseTile(fetchBytes(BASE_URL + path), manifest, path).values
        }
        return interpolateStaticValues(values, manifest.grid, latitude, longitude)
    }

    fun fetchCalibrationArtifact(now: Instant): CalibrationArtifact {
        val manifest = fetchUsableManifest(now)
        val expectedChecksum = requireNotNull(manifest.calibrationChecksum)
        val bytes = fetchBytes(BASE_URL + CALIBRATION_PATH)
        if (bytes.size > MAX_CALIBRATION_BYTES) {
            throw IOException("Calibration payload is too large.")
        }
        require(sha256Hex(bytes) == expectedChecksum) { "Calibration checksum mismatch." }
        return parseCalibrationArtifact(bytes.toString(Charsets.UTF_8), now.epochSecond)
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
