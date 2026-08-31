package cz.majkey.pocasicesko.data

import java.time.Instant
import java.time.ZoneOffset
import kotlin.math.max
import kotlin.math.roundToInt
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

internal fun parsePrecipitationField(
    json: String,
    points: List<PrecipitationFieldPoint>,
    modelIds: List<String>,
    location: CzechLocation? = null,
    calibration: CalibrationArtifact? = null,
): PrecipitationField {
    if (points.size != EXPECTED_POINTS) throw JSONException("Expected 25 field points.")
    if (modelIds.size < MINIMUM_MODELS || modelIds.toSet().size != modelIds.size) {
        throw JSONException("Field model identifiers are invalid.")
    }
    val root = JSONArray(json)
    if (root.length() != points.size) throw JSONException("Field location count is invalid.")
    val hourlyObjects = ArrayList<JSONObject>(points.size)
    var timestamps: List<Long>? = null
    for (index in points.indices) {
        val location = root.getJSONObject(index)
        val locationId = if (location.has("location_id")) location.getInt("location_id") else 0
        if (locationId != index || index > 0 && !location.has("location_id")) {
            throw JSONException("Field location order is invalid.")
        }
        validateCoordinate(location, "latitude", -90.0, 90.0)
        validateCoordinate(location, "longitude", -180.0, 180.0)
        val units = location.getJSONObject("hourly_units")
        if (units.getString("time") != "unixtime") throw JSONException("Field time unit is invalid.")
        VARIABLES.forEach { variable ->
            modelIds.forEach { model ->
                val expected = if (variable == "snowfall") "cm" else "mm"
                if (units.getString("${variable}_$model") != expected) {
                    throw JSONException("Field unit is invalid for $variable.")
                }
            }
        }
        val hourly = location.getJSONObject("hourly")
        val currentTimes = hourly.getJSONArray("time").longValues("time")
        if (currentTimes.size !in 1..MAX_FRAMES ||
            currentTimes.zipWithNext().any { (first, second) -> first >= second }
        ) {
            throw JSONException("Field timestamps are invalid.")
        }
        if (timestamps == null) timestamps = currentTimes else if (timestamps != currentTimes) {
            throw JSONException("Field timestamps do not match.")
        }
        VARIABLES.forEach { variable ->
            modelIds.forEach { model ->
                val name = "${variable}_$model"
                val values = hourly.getJSONArray(name)
                if (values.length() != currentTimes.size) {
                    throw JSONException("Field array length is invalid for $name.")
                }
                for (timeIndex in currentTimes.indices) values.nonNegativeOrNull(timeIndex, name)
            }
        }
        hourlyObjects += hourly
    }
    val validTimes = requireNotNull(timestamps)
    return PrecipitationField(
        validTimes.indices.map { timeIndex ->
            PrecipitationFieldFrame(
                validTime = Instant.ofEpochSecond(validTimes[timeIndex]),
                cells = points.indices.map { pointIndex ->
                    calculateCell(
                        points[pointIndex],
                        hourlyObjects[pointIndex],
                        modelIds,
                        timeIndex,
                        validTimes[timeIndex],
                        location,
                        calibration,
                    )
                },
            )
        },
    )
}

private fun calculateCell(
    point: PrecipitationFieldPoint,
    hourly: JSONObject,
    modelIds: List<String>,
    timeIndex: Int,
    validTime: Long,
    location: CzechLocation?,
    calibration: CalibrationArtifact?,
): PrecipitationFieldCell {
    val values = modelIds.mapNotNull { model ->
        val precipitation = hourly.getJSONArray("precipitation_$model")
            .nonNegativeOrNull(timeIndex, "precipitation_$model") ?: return@mapNotNull null
        val rain = hourly.getJSONArray("rain_$model")
            .nonNegativeOrNull(timeIndex, "rain_$model") ?: return@mapNotNull null
        val showers = hourly.getJSONArray("showers_$model")
            .nonNegativeOrNull(timeIndex, "showers_$model") ?: return@mapNotNull null
        val snowfall = hourly.getJSONArray("snowfall_$model")
            .nonNegativeOrNull(timeIndex, "snowfall_$model") ?: return@mapNotNull null
        ModelPrecipitation(model, precipitation, rain, showers, snowfall)
    }
    val segment = if (location == null || calibration == null) {
        null
    } else {
        calibration.segment(
            forecastRegionFor(location),
            "precipitation",
            leadHours = timeIndex,
            month = Instant.ofEpochSecond(validTime).atZone(ZoneOffset.UTC).monthValue,
        )
    }
    val calculated = calculatePrecipitation(values, segment) ?: return unavailableCell(point)
    val liquid = calculated.rainMm + calculated.showersMm >= LIQUID_THRESHOLD_MM
    val snow = calculated.snowfallCm >= SNOW_THRESHOLD_CM
    val kind = when {
        calculated.precipitationMm < WET_THRESHOLD_MM -> PrecipitationKind.DRY
        liquid && snow -> PrecipitationKind.MIXED
        snow -> PrecipitationKind.SNOW
        else -> PrecipitationKind.RAIN
    }
    return PrecipitationFieldCell(
        point = point,
        precipitationMm = calculated.precipitationMm,
        rainMm = calculated.rainMm,
        showersMm = calculated.showersMm,
        snowfallCm = calculated.snowfallCm,
        probabilityPercent = calculated.probabilityPercent,
        agreementPercent = calculated.agreementPercent,
        contributorCount = calculated.contributorCount,
        minimumMm = calculated.minimumMm,
        maximumMm = calculated.maximumMm,
        kind = kind,
    )
}

private fun calculatePrecipitation(
    values: List<ModelPrecipitation>,
    segment: CalibrationSegment?,
): CalculatedPrecipitation? {
    if (segment != null) {
        val byModel = values.associateBy(ModelPrecipitation::modelId)
        val weighted = segment.weights.mapNotNull { (modelId, weight) ->
            if (weight <= 0) return@mapNotNull null
            byModel[modelId]?.let { value -> WeightedPrecipitation(value, weight) }
        }
        if (weighted.size >= segment.minimumContributors) {
            val totalWeight = weighted.sumOf(WeightedPrecipitation::weight)
            fun weightedValue(value: (ModelPrecipitation) -> Double): Double =
                weighted.sumOf { item -> value(item.value) * item.weight } / totalWeight
            val precipitation = weighted.map { it.value.precipitation }
            val probability = weighted.sumOf { item ->
                if (item.value.precipitation >= WET_THRESHOLD_MM) item.weight else 0.0
            } / totalWeight * 100.0
            return CalculatedPrecipitation(
                precipitationMm = weightedValue(ModelPrecipitation::precipitation),
                rainMm = weightedValue(ModelPrecipitation::rain),
                showersMm = weightedValue(ModelPrecipitation::showers),
                snowfallCm = weightedValue(ModelPrecipitation::snowfall),
                probabilityPercent = probability.roundToInt(),
                agreementPercent = max(probability, 100.0 - probability).roundToInt(),
                contributorCount = weighted.size,
                minimumMm = precipitation.min(),
                maximumMm = precipitation.max(),
            )
        }
    }
    if (values.size < MINIMUM_MODELS) return null
    val precipitation = values.map(ModelPrecipitation::precipitation).sorted()
    val wetCount = precipitation.count { it >= WET_THRESHOLD_MM }
    return CalculatedPrecipitation(
        precipitationMm = precipitation.median(),
        rainMm = values.map(ModelPrecipitation::rain).median(),
        showersMm = values.map(ModelPrecipitation::showers).median(),
        snowfallCm = values.map(ModelPrecipitation::snowfall).median(),
        probabilityPercent = (wetCount * 100.0 / values.size).roundToInt(),
        agreementPercent = (max(wetCount, values.size - wetCount) * 100.0 / values.size)
            .roundToInt(),
        contributorCount = values.size,
        minimumMm = precipitation.first(),
        maximumMm = precipitation.last(),
    )
}

private fun unavailableCell(point: PrecipitationFieldPoint) = PrecipitationFieldCell(
    point = point,
    precipitationMm = null,
    rainMm = null,
    showersMm = null,
    snowfallCm = null,
    probabilityPercent = null,
    agreementPercent = null,
    contributorCount = 0,
    minimumMm = null,
    maximumMm = null,
    kind = PrecipitationKind.UNAVAILABLE,
)

private fun validateCoordinate(source: JSONObject, name: String, minimum: Double, maximum: Double) {
    val value = source.getDouble(name)
    if (!value.isFinite() || value !in minimum..maximum) {
        throw JSONException("Field $name is invalid.")
    }
}

private fun JSONArray.longValues(name: String): List<Long> = List(length()) { index ->
    if (isNull(index)) throw JSONException("Field $name[$index] is missing.")
    getLong(index)
}

private fun JSONArray.nonNegativeOrNull(index: Int, name: String): Double? {
    if (isNull(index)) return null
    val value = getDouble(index)
    if (!value.isFinite() || value < 0) throw JSONException("Field $name[$index] is invalid.")
    return value
}

private fun List<Double>.median(): Double {
    val sorted = sorted()
    val middle = sorted.size / 2
    return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2
}

private data class ModelPrecipitation(
    val modelId: String,
    val precipitation: Double,
    val rain: Double,
    val showers: Double,
    val snowfall: Double,
)

private data class WeightedPrecipitation(val value: ModelPrecipitation, val weight: Double)

private data class CalculatedPrecipitation(
    val precipitationMm: Double,
    val rainMm: Double,
    val showersMm: Double,
    val snowfallCm: Double,
    val probabilityPercent: Int,
    val agreementPercent: Int,
    val contributorCount: Int,
    val minimumMm: Double,
    val maximumMm: Double,
)

private val VARIABLES = listOf("precipitation", "rain", "showers", "snowfall")
private const val EXPECTED_POINTS = 25
private const val MAX_FRAMES = 24
private const val MINIMUM_MODELS = 3
private const val WET_THRESHOLD_MM = 0.1
private const val LIQUID_THRESHOLD_MM = 0.1
private const val SNOW_THRESHOLD_CM = 0.1
