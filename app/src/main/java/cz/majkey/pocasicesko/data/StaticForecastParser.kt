package cz.majkey.pocasicesko.data

import java.time.Instant
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.floor
import org.json.JSONException
import org.json.JSONObject

internal object StaticForecastParser {
    fun parseManifest(value: String): StaticForecastManifest = try {
        val root = JSONObject(value)
        val schemaVersion = root.getInt("schema_version")
        require(schemaVersion == SCHEMA_VERSION) { "Unsupported manifest schema." }
        val grid = parseGrid(root.getJSONObject("grid"))
        val run = root.getJSONObject("run")
        val state = when (run.getString("state")) {
            "diagnostic" -> StaticFeedState.DIAGNOSTIC
            "production" -> StaticFeedState.PRODUCTION
            else -> throw IllegalArgumentException("Unsupported manifest state.")
        }
        val runId = run.getString("run_id")
        require(RUN_ID.matches(runId)) { "Invalid manifest run ID." }
        val generatedAt = Instant.parse(run.getString("generated_at"))
        val expiresAt = Instant.parse(run.getString("expires_at"))
        require(expiresAt.isAfter(generatedAt)) { "Manifest expiry must follow generation." }
        val sourcesJson = root.getJSONArray("sources")
        val sources = List(sourcesJson.length()) { index ->
            val source = sourcesJson.getJSONObject(index)
            require(source.getBoolean("enabled")) { "Manifest source must be enabled." }
            require(source.getBoolean("commercial_redistribution")) {
                "Manifest source must permit commercial redistribution."
            }
            StaticFeedSource(
                sourceId = source.getString("source_id").also { require(it.isNotBlank()) },
                modelId = source.getString("model_id").also { require(it.isNotBlank()) },
            )
        }
        require(sources.isNotEmpty()) { "Manifest sources are empty." }
        require(sources.map(StaticFeedSource::sourceId).toSet().size == sources.size) {
            "Manifest source IDs are duplicated."
        }
        val checksums = parseChecksums(root.getJSONObject("tile_checksums"), runId)
        val calibration = root.optionalChecksum("calibration_checksum")
        val dataset = root.optionalChecksum("dataset_manifest_hash")
        if (state == StaticFeedState.PRODUCTION) {
            require(checksums.isNotEmpty()) { "Production manifest has no tiles." }
            require(calibration != null && dataset != null) {
                "Production manifest is missing calibration checksums."
            }
        }
        StaticForecastManifest(
            schemaVersion = schemaVersion,
            state = state,
            runId = runId,
            generatedAt = generatedAt,
            expiresAt = expiresAt,
            grid = grid,
            sources = sources,
            tileChecksums = checksums,
            calibrationChecksum = calibration,
            datasetManifestHash = dataset,
        )
    } catch (error: JSONException) {
        throw IllegalArgumentException("Malformed static forecast manifest.", error)
    }

    fun parseTile(bytes: ByteArray, manifest: StaticForecastManifest): StaticForecastTile = try {
        val root = JSONObject(bytes.toString(Charsets.UTF_8))
        require(root.getInt("schema_version") == SCHEMA_VERSION) { "Unsupported tile schema." }
        val runId = root.getString("run_id")
        require(runId == manifest.runId) { "Tile run does not match manifest." }
        val tileX = root.getInt("tile_x")
        val tileY = root.getInt("tile_y")
        require(tileX >= 0 && tileY >= 0) { "Tile coordinates are invalid." }
        val path = "tiles/$runId/$tileY/$tileX.json"
        val expectedChecksum = manifest.tileChecksums[path]
            ?: throw IllegalArgumentException("Tile is absent from manifest checksums.")
        require(sha256(bytes) == expectedChecksum) { "Tile checksum mismatch." }
        val sources = manifest.sources.associateBy(StaticFeedSource::sourceId)
        val valuesJson = root.getJSONArray("values")
        val identities = mutableSetOf<String>()
        val units = mutableMapOf<String, String>()
        val values = List(valuesJson.length()) { index ->
            val row = valuesJson.getJSONObject(index)
            val sourceId = row.getString("source_id")
            val modelId = row.getString("model_id")
            val source = sources[sourceId]
                ?: throw IllegalArgumentException("Tile contains an unknown source.")
            require(source.modelId == modelId) { "Tile source/model identity is invalid." }
            val latitude = row.getDouble("latitude")
            val longitude = row.getDouble("longitude")
            val elevation = row.getDouble("elevation_m")
            require(listOf(latitude, longitude, elevation).all(Double::isFinite)) {
                "Tile coordinates contain a non-finite value."
            }
            require(latitude in manifest.grid.south..manifest.grid.north) {
                "Tile latitude is outside the manifest grid."
            }
            require(longitude in manifest.grid.west..manifest.grid.east) {
                "Tile longitude is outside the manifest grid."
            }
            val expectedTileY = floor((latitude - manifest.grid.south) / manifest.grid.tileStep).toInt()
            val expectedTileX = floor((longitude - manifest.grid.west) / manifest.grid.tileStep).toInt()
            require(tileY == expectedTileY && tileX == expectedTileX) {
                "Tile coordinates do not match a forecast row."
            }
            val runTime = Instant.parse(row.getString("run_time"))
            val validTime = Instant.parse(row.getString("valid_time"))
            require(!validTime.isBefore(runTime)) { "Tile validity precedes its model run." }
            val variable = row.getString("variable")
            val unit = row.getString("unit")
            require(variable.isNotBlank() && unit.isNotBlank()) { "Tile variable and unit are required." }
            val previousUnit = units.putIfAbsent(variable, unit)
            require(previousUnit == null || previousUnit == unit) {
                "Tile contains mixed units for one variable."
            }
            val value = if (row.isNull("value")) null else row.getDouble("value").also {
                require(it.isFinite()) { "Tile value is non-finite." }
            }
            val identity = "$sourceId|$modelId|$runTime|$validTime|$latitude|$longitude|$variable"
            require(identities.add(identity)) { "Tile contains a duplicate forecast identity." }
            StaticModelValue(
                sourceId = sourceId,
                modelId = modelId,
                runTime = runTime,
                validTime = validTime,
                latitude = latitude,
                longitude = longitude,
                elevationMeters = elevation,
                variable = variable,
                value = value,
                unit = unit,
            )
        }
        StaticForecastTile(runId, tileX, tileY, values)
    } catch (error: JSONException) {
        throw IllegalArgumentException("Malformed static forecast tile.", error)
    }

    private fun parseGrid(value: JSONObject): StaticFeedGrid {
        val grid = StaticFeedGrid(
            south = value.getDouble("south"),
            north = value.getDouble("north"),
            west = value.getDouble("west"),
            east = value.getDouble("east"),
            step = value.getDouble("step"),
            tileStep = value.getDouble("tile_step"),
        )
        require(
            listOf(grid.south, grid.north, grid.west, grid.east, grid.step, grid.tileStep)
                .all(Double::isFinite),
        ) { "Manifest grid contains a non-finite value." }
        require(grid.south < grid.north && grid.west < grid.east) { "Manifest grid bounds are invalid." }
        require(grid.step > 0 && grid.tileStep > 0) { "Manifest grid steps must be positive." }
        val ratio = grid.tileStep / grid.step
        require(abs(ratio - ratio.toInt()) < GRID_EPSILON) { "Manifest grid tile_step is invalid." }
        return grid
    }

    private fun parseChecksums(value: JSONObject, runId: String): Map<String, String> = buildMap {
        val keys = value.keys()
        while (keys.hasNext()) {
            val path = keys.next()
            require(path.startsWith("tiles/$runId/") && !path.contains("..")) {
                "Manifest tile path is invalid."
            }
            put(path, value.getString(path).validatedChecksum())
        }
    }

    private fun JSONObject.optionalChecksum(name: String): String? =
        if (isNull(name)) null else getString(name).validatedChecksum()

    private fun String.validatedChecksum(): String {
        require(CHECKSUM.matches(this)) { "Invalid SHA-256 checksum." }
        return this
    }

    private fun sha256(value: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(value)
        .joinToString("") { "%02x".format(it) }

    private const val SCHEMA_VERSION = 1
    private const val GRID_EPSILON = 1e-8
    private val RUN_ID = Regex("[0-9]{8}T[0-9]{6}Z")
    private val CHECKSUM = Regex("[0-9a-f]{64}")
}
