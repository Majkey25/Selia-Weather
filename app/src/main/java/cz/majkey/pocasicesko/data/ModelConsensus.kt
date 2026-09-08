package cz.majkey.pocasicesko.data

import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import org.json.JSONArray
import org.json.JSONObject

internal data class ModelBlendResult(
    val json: String,
    val mode: ForecastCalculationMode,
    val contributorIds: List<String>,
    val fallbackReason: ForecastFallbackReason?,
    val appliedWeights: Map<String, Double> = emptyMap(),
    val truthClass: CalibrationTruthClass? = null,
    val artifactVersion: Int? = null,
    val artifactGeneratedAtEpochSeconds: Long? = null,
    val calibrationAppliedAt: String? = null,
    val calibrationVariable: String? = null,
    val calibratedValueCount: Int = 0,
)

internal fun blendModelForecast(
    bestMatchJson: String,
    modelsJson: String,
    location: CzechLocation? = null,
    calibration: CalibrationArtifact? = null,
    calibratedValues: List<StaticModelValue> = emptyList(),
    now: Instant = Instant.now(),
): ModelBlendResult {
    val root = JSONObject(bestMatchJson)
    val target = root.getJSONObject("hourly")
    val source = JSONObject(modelsJson).getJSONObject("hourly")
    val suffixes = source.keys().asSequence()
        .filter { it.startsWith("temperature_2m_") }
        .map { it.removePrefix("temperature_2m_") }
        .sorted()
        .toList()
    val sourceTimes = source.getJSONArray("time")
    val diagnosticContributors = currentTemperatureContributors(root, source, sourceTimes, suffixes)
    if (suffixes.size < MINIMUM_MODELS && (location == null || calibration == null)) {
        return ModelBlendResult(
            bestMatchJson,
            ForecastCalculationMode.BEST_MATCH,
            diagnosticContributors,
            ForecastFallbackReason.INSUFFICIENT_CONTRIBUTORS,
        )
    }

    val sourceIndices = (0 until sourceTimes.length()).associateBy { sourceTimes.getString(it) }
    val targetTimes = target.getJSONArray("time")
    val currentTargetIndex = currentIndex(root, targetTimes)
    val region = location?.let(::forecastRegionFor)
    // Open-Meteo writes every ISO timestamp using this response offset, not device timezone rules.
    val zone: ZoneId? = runCatching {
        if (root.has("utc_offset_seconds")) {
            val offset = root.get("utc_offset_seconds")
            require(offset is Number && offset.toDouble() == offset.toInt().toDouble())
            ZoneOffset.ofTotalSeconds(offset.toInt())
        } else {
            ZoneId.of(root.getString("timezone"))
        }
    }.getOrNull()
    val issuedValues = calibratedValues.filter { value ->
        location != null && kotlin.math.abs(value.latitude - location.latitude) < 1e-6 &&
            kotlin.math.abs(value.longitude - location.longitude) < 1e-6
    }.groupBy { it.validTime to it.variable }
    val activeCalibration = calibration?.takeIf {
        !now.isBefore(it.generatedAt) && now.isBefore(it.expiresAt)
    }
    var firstCalibration: WeightedModelValue? = null
    var calibrationAppliedAt: String? = null
    var calibrationVariable: String? = null
    var calibratedValueCount = 0
    var blendedAny = false
    for (targetIndex in 0 until targetTimes.length()) {
        val sourceIndex = sourceIndices[targetTimes.getString(targetIndex)] ?: continue
        val localTime = LocalDateTime.parse(targetTimes.getString(targetIndex))
        // Legacy responses without an offset cannot disambiguate daylight-saving transitions.
        val validTime = zone?.takeIf { it.rules.getValidOffsets(localTime).size == 1 }
            ?.let { localTime.atZone(it).toInstant() }
        CONTINUOUS_FIELDS.forEach { field ->
            val calibrated = if (region != null && validTime != null && currentTargetIndex != null && targetIndex >= currentTargetIndex) {
                activeCalibration?.let { artifact ->
                    weightedModelValue(
                        issuedValues[validTime to field].orEmpty(), artifact, region, field, validTime, now,
                    )
                }
            } else {
                null
            }
            val value = calibrated?.value ?: modelValues(source, suffixes, field, sourceIndex)
                .takeIf { it.size >= MINIMUM_MODELS }
                ?.median()
            if (value != null && target.optJSONArray(field) != null) {
                target.getJSONArray(field).put(targetIndex, value)
                blendedAny = true
                if (calibrated != null) {
                    calibratedValueCount++
                    if (firstCalibration == null) {
                        firstCalibration = calibrated
                        calibrationAppliedAt = targetTimes.getString(targetIndex)
                        calibrationVariable = field
                    }
                }
            }
        }
        blendedAny = blendWind(
            source,
            target,
            suffixes,
            sourceIndex,
            targetIndex,
        ) || blendedAny
        blendedAny = deriveCondition(
            source,
            target,
            suffixes,
            sourceIndex,
            targetIndex,
        ) || blendedAny
    }
    if (!blendedAny) {
        return ModelBlendResult(
            bestMatchJson,
            ForecastCalculationMode.BEST_MATCH,
            diagnosticContributors,
            ForecastFallbackReason.INSUFFICIENT_CONTRIBUTORS,
        )
    }
    updateCurrent(root, target, targetTimes)
    updateDaily(root, target, targetTimes)
    val appliedWeights = firstCalibration?.weights.orEmpty()
    val contributorIds = firstCalibration?.weights?.keys?.toList() ?: diagnosticContributors
    val calibrated = firstCalibration != null
    val diagnostic = diagnosticContributors.size >= MINIMUM_MODELS
    return ModelBlendResult(
        root.toString(),
        when {
            calibrated -> ForecastCalculationMode.CALIBRATED
            diagnostic -> ForecastCalculationMode.DIAGNOSTIC_MEDIAN
            else -> ForecastCalculationMode.BEST_MATCH
        },
        contributorIds,
        if (calibrated || diagnostic) null else ForecastFallbackReason.INSUFFICIENT_CONTRIBUTORS,
        appliedWeights,
        firstCalibration?.truthClass,
        calibration?.schemaVersion?.takeIf { calibrated },
        calibration?.generatedAt?.epochSecond?.takeIf { calibrated },
        calibrationAppliedAt,
        calibrationVariable,
        calibratedValueCount,
    )
}

private fun currentTemperatureContributors(
    root: JSONObject,
    source: JSONObject,
    sourceTimes: JSONArray,
    suffixes: List<String>,
): List<String> {
    val currentHour = root.getJSONObject("current").getString("time").take(13)
    val index = (0 until sourceTimes.length()).firstOrNull {
        sourceTimes.getString(it).take(13) == currentHour
    } ?: return emptyList()
    return suffixes.filter { suffix ->
        source.optJSONArray("temperature_2m_$suffix").numberOrNull(index)
            ?.let { value -> isValidModelValue("temperature_2m", value) } == true
    }
}

private fun blendWind(
    source: JSONObject,
    target: JSONObject,
    suffixes: List<String>,
    sourceIndex: Int,
    targetIndex: Int,
): Boolean {
    val vectors = suffixes.mapNotNull { suffix ->
        val speed = source.optJSONArray("wind_speed_10m_$suffix").numberOrNull(sourceIndex)
            ?: return@mapNotNull null
        val direction = source.optJSONArray("wind_direction_10m_$suffix").numberOrNull(sourceIndex)
            ?: return@mapNotNull null
        if (speed < 0 || direction !in 0.0..360.0) return@mapNotNull null
        WindVector(speed, Math.toRadians(direction))
    }
    if (vectors.size < MINIMUM_MODELS) return false
    val east = vectors.sumOf { vector -> vector.speed * sin(vector.angle) } / vectors.size
    val north = vectors.sumOf { vector -> vector.speed * cos(vector.angle) } / vectors.size
    target.optJSONArray("wind_speed_10m")?.put(targetIndex, hypot(east, north))
    target.optJSONArray("wind_direction_10m")?.put(
        targetIndex,
        ((Math.toDegrees(atan2(east, north)) + 360.0) % 360.0).roundToInt() % 360,
    )
    return true
}

private fun deriveCondition(
    source: JSONObject,
    target: JSONObject,
    suffixes: List<String>,
    sourceIndex: Int,
    targetIndex: Int,
): Boolean {
    val precipitation = modelValues(source, suffixes, "precipitation", sourceIndex)
    val clouds = modelValues(source, suffixes, "cloud_cover", sourceIndex)
    if (clouds.size < MINIMUM_MODELS) return false
    val cloudCover = target.optJSONArray("cloud_cover").numberOrNull(targetIndex)
        ?.roundToInt()?.coerceIn(0, 100) ?: return false
    val fallbackCode = target.optJSONArray("weather_code").numberOrNull(targetIndex)?.roundToInt()
    val code = if (precipitation.size >= MINIMUM_MODELS) {
        val amount = target.optJSONArray("precipitation").numberOrNull(targetIndex) ?: return false
        deriveWeatherCode(
            modelValues(source, suffixes, "weather_code", sourceIndex).map(Double::roundToInt),
            amount,
            cloudCover,
            fallbackCode,
        )
    } else {
        // Cloud-only evidence updates sky classes, never disproves drizzle or other hazards.
        // A provider's preceding-hour rain total is not an instantaneous condition.
        if (fallbackCode !in 0..3) return false
        skyWeatherCode(cloudCover)
    }
    target.optJSONArray("weather_code")?.put(targetIndex, code)
    return true
}

private fun deriveWeatherCode(
    codes: List<Int>,
    precipitation: Double,
    cloudCover: Int,
    fallbackCode: Int?,
): Int {
    val sufficientCodes = codes.size >= MINIMUM_MODELS
    // Missing codes cannot disprove a provider's snow, freezing rain, fog, or storm forecast.
    if (!sufficientCodes && fallbackCode != null && fallbackCode !in 0..3) return fallbackCode
    val required = codes.size / 2 + 1
    return when {
        sufficientCodes && codes.count { it in 95..99 } >= required -> 95
        sufficientCodes && codes.count { it in 66..67 } >= required -> 66
        sufficientCodes && codes.count { it in 56..57 } >= required -> 56
        sufficientCodes && codes.count { it in DRIZZLE_CODES } >= required ->
            DRIZZLE_CODES.firstOrNull { code -> codes.count { it == code } >= required } ?: 51
        sufficientCodes && codes.count { it in 71..77 || it == 85 || it == 86 } >= required -> 71
        sufficientCodes && codes.count { it in 45..48 } >= required -> 45
        precipitation >= WET_THRESHOLD_MM -> 61
        else -> skyWeatherCode(cloudCover)
    }
}

private fun skyWeatherCode(cloudCover: Int): Int = when {
    cloudCover <= 20 -> 0
    cloudCover <= 50 -> 1
    cloudCover <= 80 -> 2
    else -> 3
}

private fun updateCurrent(root: JSONObject, hourly: JSONObject, times: JSONArray) {
    val current = root.getJSONObject("current")
    val currentTime = current.getString("time")
    val index = (0 until times.length()).firstOrNull { times.getString(it) == currentTime }
        ?: return
    CURRENT_FIELDS.forEach { field ->
        hourly.optJSONArray(field).numberOrNull(index)?.let { current.put(field, it) }
    }
}

private fun updateDaily(root: JSONObject, hourly: JSONObject, times: JSONArray) {
    val daily = root.getJSONObject("daily")
    val days = daily.getJSONArray("time")
    for (dayIndex in 0 until days.length()) {
        val date = days.getString(dayIndex)
        val indices = (0 until times.length()).filter { times.getString(it).startsWith(date) }
        if (indices.isEmpty()) continue
        daily.putAt("temperature_2m_max", dayIndex, hourly.values("temperature_2m", indices).maxOrNull())
        daily.putAt("temperature_2m_min", dayIndex, hourly.values("temperature_2m", indices).minOrNull())
        daily.putAt("apparent_temperature_max", dayIndex, hourly.values("apparent_temperature", indices).maxOrNull())
        daily.putAt("apparent_temperature_min", dayIndex, hourly.values("apparent_temperature", indices).minOrNull())
        listOf("precipitation", "rain", "snowfall").forEach { field ->
            daily.putAt(
                "${field}_sum",
                dayIndex,
                hourly.values(field, indices).takeIf { it.size == indices.size }?.sum(),
            )
        }
        daily.putAt(
            "precipitation_probability_max",
            dayIndex,
            hourly.values("precipitation_probability", indices).maxOrNull(),
        )
        daily.putAt("wind_speed_10m_max", dayIndex, hourly.values("wind_speed_10m", indices).maxOrNull())
        daily.putAt("wind_gusts_10m_max", dayIndex, hourly.values("wind_gusts_10m", indices).maxOrNull())
        daily.putAt("wind_direction_10m_dominant", dayIndex, hourly.windDirection(indices))
        val codes = hourly.values("weather_code", indices).map(Double::roundToInt)
        daily.putAt("weather_code", dayIndex, codes.maxByOrNull(::weatherSeverity))
    }
}

private fun JSONObject.windDirection(indices: List<Int>): Int? {
    val speeds = optJSONArray("wind_speed_10m") ?: return null
    val directions = optJSONArray("wind_direction_10m") ?: return null
    val vectors = indices.mapNotNull { index ->
        val speed = speeds.numberOrNull(index) ?: return@mapNotNull null
        val direction = directions.numberOrNull(index) ?: return@mapNotNull null
        speed to Math.toRadians(direction)
    }
    if (vectors.isEmpty()) return null
    val east = vectors.sumOf { (speed, angle) -> speed * sin(angle) }
    val north = vectors.sumOf { (speed, angle) -> speed * cos(angle) }
    return ((Math.toDegrees(atan2(east, north)) + 360.0) % 360.0).roundToInt() % 360
}

private fun JSONObject.values(field: String, indices: List<Int>): List<Double> =
    optJSONArray(field)?.let { array -> indices.mapNotNull(array::numberOrNull) }.orEmpty()

private fun JSONObject.putAt(field: String, index: Int, value: Number?) {
    if (value != null) optJSONArray(field)?.put(index, value)
}

private fun modelValues(
    source: JSONObject,
    suffixes: List<String>,
    field: String,
    index: Int,
): List<Double> = suffixes.mapNotNull { suffix ->
    source.optJSONArray("${field}_$suffix").numberOrNull(index)
        ?.takeIf { value -> isValidModelValue(field, value) }
}

private fun weightedModelValue(
    values: List<StaticModelValue>,
    artifact: CalibrationArtifact,
    region: ForecastRegion,
    field: String,
    validTime: Instant,
    now: Instant,
): WeightedModelValue? {
    if (values.isEmpty() || field !in CALIBRATION_UNITS) return null
    val contracts = artifact.models.associateBy(CalibrationModelContract::modelId)
    val month = validTime.atZone(ZoneOffset.UTC).monthValue
    for (segment in artifact.segments.filter { it.region == region && it.variable == field && month in it.months }) {
        val available = values.filter { row ->
            val contract = contracts[row.modelId]
            val age = Duration.between(row.runTime, now)
            val lead = Duration.between(row.runTime, validTime)
            contract != null && !age.isNegative && age <= Duration.ofHours(contract.maximumRunAgeHours.toLong()) &&
                lead >= Duration.ofHours(segment.minimumLeadHours.toLong()) &&
                lead <= Duration.ofHours(segment.maximumLeadHours.toLong()) &&
                row.unit == CALIBRATION_UNITS[field] && row.value?.let { it.isFinite() && isValidModelValue(field, it) } == true
        }.groupBy(StaticModelValue::modelId).mapValues { (_, rows) -> rows.maxBy(StaticModelValue::runTime) }
        val inputs = segment.weights.mapNotNull { (modelId, weight) ->
            val value = available[modelId]?.value ?: return@mapNotNull null
            if (weight > 0) WeightedModelInput(modelId, value, weight) else null
        }
        if (inputs.size < segment.minimumContributors) continue
        val totalWeight = inputs.sumOf(WeightedModelInput::weight)
        return WeightedModelValue(
            inputs.sumOf { it.value * it.weight } / totalWeight,
            inputs.associate { it.modelId to it.weight / totalWeight },
            segment.truthClass,
        )
    }
    return null
}

private fun currentIndex(root: JSONObject, times: JSONArray): Int? {
    val currentHour = root.getJSONObject("current").getString("time").take(13)
    return (0 until times.length()).firstOrNull { times.getString(it).take(13) == currentHour }
}

private fun isValidModelValue(field: String, value: Double): Boolean = when {
    field in NON_NEGATIVE_FIELDS -> value >= 0
    field == "relative_humidity_2m" || field.startsWith("cloud_cover") -> value in 0.0..100.0
    field == "weather_code" -> value in 0.0..99.0
    else -> true
}

private fun JSONArray?.numberOrNull(index: Int): Double? {
    if (this == null || index !in 0 until length() || isNull(index)) return null
    return optDouble(index, Double.NaN).takeIf(Double::isFinite)
}

private fun List<Double>.median(): Double = sorted().let { values ->
    val middle = values.size / 2
    if (values.size % 2 == 1) values[middle] else (values[middle - 1] + values[middle]) / 2
}

private data class WeightedModelInput(val modelId: String, val value: Double, val weight: Double)
private data class WeightedModelValue(
    val value: Double,
    val weights: Map<String, Double>,
    val truthClass: CalibrationTruthClass,
)
private data class WindVector(val speed: Double, val angle: Double)

private fun weatherSeverity(code: Int): Int = when (code) {
    in 95..99 -> 7
    in 71..77, 85, 86 -> 6
    in 51..67, in 80..82 -> 5
    45, 48 -> 4
    3 -> 3
    2 -> 2
    1 -> 1
    else -> 0
}

private val CONTINUOUS_FIELDS = listOf(
    "temperature_2m",
    "relative_humidity_2m",
    "apparent_temperature",
    "precipitation",
    "rain",
    "snowfall",
    "cloud_cover",
    "cloud_cover_low",
    "cloud_cover_mid",
    "cloud_cover_high",
    "pressure_msl",
    "surface_pressure",
    "wind_gusts_10m",
    "dew_point_2m",
    "visibility",
)
// Keep current sky and interval aggregates at the provider's current validity time.
// Hourly conditions are derived partly from the preceding hour's precipitation.
private val CURRENT_FIELDS = CONTINUOUS_FIELDS - setOf(
    "precipitation", "rain", "snowfall", "wind_gusts_10m",
    "cloud_cover", "cloud_cover_low", "cloud_cover_mid", "cloud_cover_high",
) + listOf(
    "wind_speed_10m",
    "wind_direction_10m",
)
private val NON_NEGATIVE_FIELDS = setOf(
    "precipitation",
    "rain",
    "snowfall",
    "visibility",
    "pressure_msl",
    "surface_pressure",
    "wind_gusts_10m",
)
private const val MINIMUM_MODELS = 3
private const val WET_THRESHOLD_MM = 0.1
private val DRIZZLE_CODES = setOf(51, 53, 55)
// Interval totals and wind need their own interval/vector contracts, not scalar substitution.
private val CALIBRATION_UNITS = mapOf(
    "temperature_2m" to "°C", "dew_point_2m" to "°C",
    "pressure_msl" to "hPa", "surface_pressure" to "hPa",
)
