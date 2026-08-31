package cz.majkey.pocasicesko.data

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import kotlin.math.abs

enum class ForecastCalculationMode {
    CALIBRATED,
    DIAGNOSTIC_MEDIAN,
    BEST_MATCH,
}

enum class ForecastFallbackReason {
    INSUFFICIENT_CONTRIBUTORS,
    PROVIDER_UNAVAILABLE,
}

data class ForecastCalculation(
    val region: ForecastRegion,
    val mode: ForecastCalculationMode,
    val requestedModelIds: List<String>,
    val contributorIds: List<String>,
    val fallbackReason: ForecastFallbackReason?,
    val artifactVersion: Int? = null,
    val artifactGeneratedAtEpochSeconds: Long? = null,
    val truthClass: CalibrationTruthClass? = null,
    val weights: Map<String, Double> = emptyMap(),
) {
    init {
        require(requestedModelIds.isNotEmpty() && requestedModelIds.size <= MAX_MODEL_IDS)
        require(requestedModelIds.all(::isModelId) && requestedModelIds.distinct() == requestedModelIds)
        require(contributorIds.all(::isModelId) && contributorIds.distinct() == contributorIds)
        require(contributorIds.all(requestedModelIds::contains))
        require(weights.keys.all(::isModelId) && weights.values.all { it.isFinite() && it > 0 })
        when (mode) {
            ForecastCalculationMode.CALIBRATED -> {
                require(contributorIds.size >= MINIMUM_CALIBRATED_MODELS && fallbackReason == null)
                require(
                    artifactVersion == 2 &&
                        artifactGeneratedAtEpochSeconds != null && artifactGeneratedAtEpochSeconds > 0 &&
                        truthClass != null,
                )
                require(weights.keys == contributorIds.toSet())
                require(abs(weights.values.sum() - 1.0) <= WEIGHT_EPSILON)
            }
            ForecastCalculationMode.DIAGNOSTIC_MEDIAN -> {
                require(contributorIds.size >= MINIMUM_DIAGNOSTIC_MODELS && fallbackReason == null)
                require(
                    artifactVersion == null && artifactGeneratedAtEpochSeconds == null &&
                        truthClass == null && weights.isEmpty(),
                )
            }
            ForecastCalculationMode.BEST_MATCH -> {
                require(fallbackReason != null)
                require(
                    artifactVersion == null && artifactGeneratedAtEpochSeconds == null &&
                        truthClass == null && weights.isEmpty(),
                )
            }
        }
    }
}

internal fun JSONObject.putForecastCalculation(calculation: ForecastCalculation): JSONObject = put(
    CALCULATION_KEY,
    JSONObject()
        .put("schema_version", CALCULATION_SCHEMA_VERSION)
        .put("region", calculation.region.name)
        .put("mode", calculation.mode.name)
        .put("requested_model_ids", JSONArray(calculation.requestedModelIds))
        .put("contributor_ids", JSONArray(calculation.contributorIds))
        .put("fallback_reason", calculation.fallbackReason?.name ?: JSONObject.NULL)
        .put("artifact_version", calculation.artifactVersion ?: JSONObject.NULL)
        .put(
            "artifact_generated_at_epoch_seconds",
            calculation.artifactGeneratedAtEpochSeconds ?: JSONObject.NULL,
        )
        .put("truth_class", calculation.truthClass?.name ?: JSONObject.NULL)
        .put("weights", JSONObject(calculation.weights)),
)

internal fun JSONObject.forecastCalculationOrNull(): ForecastCalculation? {
    val value = optJSONObject(CALCULATION_KEY) ?: return null
    val schemaVersion = value.getInt("schema_version")
    if (schemaVersion !in LEGACY_CALCULATION_SCHEMA_VERSION..CALCULATION_SCHEMA_VERSION) {
        throw JSONException("Unsupported forecast calculation schema.")
    }
    return try {
        ForecastCalculation(
            region = enumValueOf(value.getString("region")),
            mode = enumValueOf(value.getString("mode")),
            requestedModelIds = value.getJSONArray("requested_model_ids").modelIds(),
            contributorIds = value.getJSONArray("contributor_ids").modelIds(),
            fallbackReason = if (value.isNull("fallback_reason")) null else {
                enumValueOf(value.getString("fallback_reason"))
            },
            artifactVersion = if (schemaVersion == LEGACY_CALCULATION_SCHEMA_VERSION ||
                value.isNull("artifact_version")
            ) {
                null
            } else {
                value.getInt("artifact_version")
            },
            artifactGeneratedAtEpochSeconds = if (
                schemaVersion == LEGACY_CALCULATION_SCHEMA_VERSION ||
                value.isNull("artifact_generated_at_epoch_seconds")
            ) {
                null
            } else {
                value.getLong("artifact_generated_at_epoch_seconds")
            },
            truthClass = if (schemaVersion == LEGACY_CALCULATION_SCHEMA_VERSION ||
                value.isNull("truth_class")
            ) {
                null
            } else {
                enumValueOf(value.getString("truth_class"))
            },
            weights = if (schemaVersion == LEGACY_CALCULATION_SCHEMA_VERSION) {
                emptyMap()
            } else {
                value.getJSONObject("weights").modelWeights()
            },
        )
    } catch (error: IllegalArgumentException) {
        throw JSONException(error.message ?: "Invalid forecast calculation metadata.")
    }
}

private fun JSONArray.modelIds(): List<String> = List(length()) { index -> getString(index) }

private fun JSONObject.modelWeights(): Map<String, Double> = keys().asSequence().sorted().associateWith {
    getDouble(it)
}

private fun isModelId(value: String): Boolean = MODEL_ID.matches(value)

private const val CALCULATION_KEY = "_selia_calculation"
private const val LEGACY_CALCULATION_SCHEMA_VERSION = 1
private const val CALCULATION_SCHEMA_VERSION = 2
private const val MAX_MODEL_IDS = 32
private const val MINIMUM_CALIBRATED_MODELS = 2
private const val MINIMUM_DIAGNOSTIC_MODELS = 3
private const val WEIGHT_EPSILON = 1e-6
private val MODEL_ID = Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}")
