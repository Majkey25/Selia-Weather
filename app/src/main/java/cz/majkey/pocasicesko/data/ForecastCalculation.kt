package cz.majkey.pocasicesko.data

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

enum class ForecastCalculationMode {
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
) {
    init {
        require(requestedModelIds.isNotEmpty() && requestedModelIds.size <= MAX_MODEL_IDS)
        require(requestedModelIds.all(::isModelId) && requestedModelIds.distinct() == requestedModelIds)
        require(contributorIds.all(::isModelId) && contributorIds.distinct() == contributorIds)
        require(contributorIds.all(requestedModelIds::contains))
        when (mode) {
            ForecastCalculationMode.DIAGNOSTIC_MEDIAN -> {
                require(contributorIds.size >= MINIMUM_DIAGNOSTIC_MODELS && fallbackReason == null)
            }
            ForecastCalculationMode.BEST_MATCH -> require(fallbackReason != null)
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
        .put("fallback_reason", calculation.fallbackReason?.name ?: JSONObject.NULL),
)

internal fun JSONObject.forecastCalculationOrNull(): ForecastCalculation? {
    val value = optJSONObject(CALCULATION_KEY) ?: return null
    if (value.getInt("schema_version") != CALCULATION_SCHEMA_VERSION) {
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
        )
    } catch (error: IllegalArgumentException) {
        throw JSONException(error.message ?: "Invalid forecast calculation metadata.")
    }
}

private fun JSONArray.modelIds(): List<String> = List(length()) { index -> getString(index) }

private fun isModelId(value: String): Boolean = MODEL_ID.matches(value)

private const val CALCULATION_KEY = "_selia_calculation"
private const val CALCULATION_SCHEMA_VERSION = 1
private const val MAX_MODEL_IDS = 32
private const val MINIMUM_DIAGNOSTIC_MODELS = 3
private val MODEL_ID = Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}")
