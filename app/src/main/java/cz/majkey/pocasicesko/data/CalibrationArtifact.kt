package cz.majkey.pocasicesko.data

import java.time.Instant
import kotlin.math.abs
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

internal enum class CalibrationTruthClass {
    STATION,
    RADAR_GAUGE,
    SATELLITE_PRECIPITATION,
    REANALYSIS,
}

internal data class CalibrationModelContract(
    val modelId: String,
    val maximumRunAgeHours: Int,
    val resolutionKm: Double,
)

internal data class CalibrationSegment(
    val region: ForecastRegion,
    val variable: String,
    val minimumLeadHours: Int,
    val maximumLeadHours: Int,
    val months: Set<Int>,
    val weights: Map<String, Double>,
    val minimumContributors: Int,
    val fallbackModelId: String,
    val truthClass: CalibrationTruthClass,
) {
    internal fun matches(
        requestedRegion: ForecastRegion,
        requestedVariable: String,
        leadHours: Int,
        month: Int,
    ): Boolean = region == requestedRegion && variable == requestedVariable &&
        leadHours in minimumLeadHours..maximumLeadHours && month in months
}

internal data class CalibrationArtifact(
    val schemaVersion: Int,
    val datasetManifestHash: String,
    val modelContractHash: String,
    val generatedAt: Instant,
    val expiresAt: Instant,
    val models: List<CalibrationModelContract>,
    val segments: List<CalibrationSegment>,
) {
    internal fun segment(
        region: ForecastRegion,
        variable: String,
        leadHours: Int,
        month: Int,
    ): CalibrationSegment? = segments.firstOrNull { segment ->
        segment.matches(region, variable, leadHours, month)
    }
}

internal fun parseCalibrationArtifact(json: String, nowEpochSeconds: Long): CalibrationArtifact = try {
    val root = JSONObject(json)
    val schemaVersion = root.getInt("schema_version")
    require(schemaVersion == CALIBRATION_SCHEMA_VERSION) { "Unsupported calibration schema." }
    val generatedAt = Instant.parse(root.getString("generated_at"))
    val expiresAt = Instant.parse(root.getString("expires_at"))
    val now = Instant.ofEpochSecond(nowEpochSeconds)
    require(!now.isBefore(generatedAt) && now.isBefore(expiresAt)) {
        "Calibration artifact is outside its validity window."
    }
    val models = parseModelContracts(root.getJSONArray("models"))
    val modelIds = models.map(CalibrationModelContract::modelId).toSet()
    val segments = parseSegments(root.getJSONArray("segments"), modelIds)
    require(segments.isNotEmpty()) { "Calibration segments are empty." }
    require(segments.map(CalibrationSegment::selectorKey).toSet().size == segments.size) {
        "Calibration segment selectors are duplicated."
    }
    CalibrationArtifact(
        schemaVersion = schemaVersion,
        datasetManifestHash = root.getString("dataset_manifest_hash").validatedChecksum(),
        modelContractHash = root.getString("model_contract_hash").validatedChecksum(),
        generatedAt = generatedAt,
        expiresAt = expiresAt,
        models = models,
        segments = segments,
    )
} catch (error: JSONException) {
    throw error
} catch (error: RuntimeException) {
    throw JSONException(error.message ?: "Invalid calibration artifact.")
}

private fun parseModelContracts(values: JSONArray): List<CalibrationModelContract> {
    require(values.length() > 0) { "Calibration models are empty." }
    val models = List(values.length()) { index ->
        val value = values.getJSONObject(index)
        CalibrationModelContract(
            modelId = value.getString("model_id").validatedModelId(),
            maximumRunAgeHours = value.getInt("maximum_run_age_hours").also {
                require(it > 0) { "Maximum model run age must be positive." }
            },
            resolutionKm = value.getDouble("resolution_km").also {
                require(it.isFinite() && it > 0) { "Model resolution must be positive." }
            },
        )
    }
    require(models.map(CalibrationModelContract::modelId).toSet().size == models.size) {
        "Calibration model IDs are duplicated."
    }
    return models.sortedBy(CalibrationModelContract::modelId)
}

private fun parseSegments(values: JSONArray, modelIds: Set<String>): List<CalibrationSegment> =
    List(values.length()) { index ->
        val value = values.getJSONObject(index)
        require(value.getString("mode") == "blend") { "Only accepted blend segments can ship." }
        require(value.getJSONObject("holdout").getBoolean("accepted")) {
            "Calibration segment failed its holdout."
        }
        val selector = value.getJSONObject("selector")
        val minimumLead = selector.getInt("minimum_lead_hours")
        val maximumLead = selector.getInt("maximum_lead_hours")
        require(minimumLead >= 0 && maximumLead >= minimumLead) {
            "Calibration lead range is invalid."
        }
        val months = selector.getJSONArray("months").intValues().toSet()
        require(months.isNotEmpty() && months.all { it in 1..12 }) {
            "Calibration months are invalid."
        }
        val weights = value.getJSONObject("weights").weights(modelIds)
        val minimumContributors = value.getInt("minimum_source_count")
        require(minimumContributors in 2..weights.count { it.value > 0 }) {
            "Calibration minimum source count is invalid."
        }
        val fallbackModel = value.getString("fallback_model").validatedModelId()
        require(fallbackModel == BEST_MATCH_MODEL_ID || fallbackModel in modelIds) {
            "Calibration fallback model is unknown."
        }
        CalibrationSegment(
            region = enumValueOf(selector.getString("region")),
            variable = selector.getString("variable").also {
                require(VARIABLE_ID.matches(it)) { "Calibration variable is invalid." }
            },
            minimumLeadHours = minimumLead,
            maximumLeadHours = maximumLead,
            months = months,
            weights = weights,
            minimumContributors = minimumContributors,
            fallbackModelId = fallbackModel,
            truthClass = when (value.getString("truth_class")) {
                "station" -> CalibrationTruthClass.STATION
                "radar_gauge" -> CalibrationTruthClass.RADAR_GAUGE
                "satellite_precipitation" -> CalibrationTruthClass.SATELLITE_PRECIPITATION
                "reanalysis" -> CalibrationTruthClass.REANALYSIS
                else -> throw IllegalArgumentException("Calibration truth class is invalid.")
            },
        )
    }

private fun JSONObject.weights(modelIds: Set<String>): Map<String, Double> {
    val names = keys().asSequence().sorted().toList()
    require(names.isNotEmpty()) { "Calibration weights are empty." }
    val weights = names.associateWith { modelId ->
        modelId.validatedModelId()
        require(modelId in modelIds) { "Calibration weight references an unknown model." }
        getDouble(modelId).also { weight ->
            require(weight.isFinite() && weight >= 0) { "Calibration weight is invalid." }
        }
    }
    require(abs(weights.values.sum() - 1.0) <= WEIGHT_EPSILON) {
        "Calibration weights are not normalized."
    }
    return weights
}

private fun JSONArray.intValues(): List<Int> = List(length(), ::getInt)

private fun CalibrationSegment.selectorKey(): String =
    "$region|$variable|$minimumLeadHours|$maximumLeadHours|${months.sorted()}"

private fun String.validatedChecksum(): String {
    require(CHECKSUM.matches(this)) { "Invalid SHA-256 checksum." }
    return this
}

private fun String.validatedModelId(): String {
    require(MODEL_ID.matches(this)) { "Calibration model ID is invalid." }
    return this
}

private const val CALIBRATION_SCHEMA_VERSION = 2
private const val BEST_MATCH_MODEL_ID = "best_match"
private const val WEIGHT_EPSILON = 1e-6
private val CHECKSUM = Regex("[0-9a-f]{64}")
private val MODEL_ID = Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}")
private val VARIABLE_ID = Regex("[a-z][a-z0-9_]{0,63}")
