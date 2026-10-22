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
    val currentSkyModelId: String? = null,
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
    val modelsRoot = JSONObject(modelsJson)
    val sourceOffset = modelsRoot.opt("utc_offset_seconds")
    if (modelsRoot.has("utc_offset_seconds") && (sourceOffset !is Number ||
        sourceOffset.toDouble() != sourceOffset.toInt().toDouble() || sourceOffset.toInt() !in -64800..64800 ||
        (root.opt("utc_offset_seconds") as? Number)?.toDouble() != sourceOffset.toDouble())) {
        return ModelBlendResult(bestMatchJson, ForecastCalculationMode.BEST_MATCH, emptyList(),
            ForecastFallbackReason.PROVIDER_UNAVAILABLE)
    }
    val source = modelsRoot.getJSONObject("hourly")
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
    target.put(PRECIPITATION_SPREAD_KEY, JSONArray(List(targetTimes.length()) { JSONObject.NULL }))
    target.put(MODEL_AGREEMENT_KEY, JSONArray(List(targetTimes.length()) { JSONObject.NULL }))
    target.put(SKY_SOURCE_MODEL_KEY, JSONArray(List(targetTimes.length()) { JSONObject.NULL }))
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
    val precipitationBlendedIndices = mutableSetOf<Int>()
    for (targetIndex in 0 until targetTimes.length()) {
        val sourceIndex = sourceIndices[targetTimes.getString(targetIndex)] ?: continue
        val agreement = modelAgreement(source, suffixes, sourceIndex)
        val localTime = LocalDateTime.parse(targetTimes.getString(targetIndex))
        var calibratedThisHour = false
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
                    calibratedThisHour = true
                    calibratedValueCount++
                    if (firstCalibration == null) {
                        firstCalibration = calibrated
                        calibrationAppliedAt = targetTimes.getString(targetIndex)
                        calibrationVariable = field
                    }
                }
            }
        }
        if (blendPrecipitation(source, target, suffixes, sourceIndex, targetIndex)) {
            precipitationBlendedIndices.add(targetIndex)
            blendedAny = true
        }
        blendedAny = blendCloudCover(source, target, suffixes, sourceIndex, targetIndex) || blendedAny
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
        // Static calibration has a different run contract from the live comparison inputs.
        if (!calibratedThisHour && agreement != null) target.getJSONArray(MODEL_AGREEMENT_KEY)
            .put(targetIndex, agreement.toJson())
    }
    if (!blendedAny) {
        return ModelBlendResult(
            root.toString(),
            ForecastCalculationMode.BEST_MATCH,
            diagnosticContributors,
            ForecastFallbackReason.INSUFFICIENT_CONTRIBUTORS,
        )
    }
    updateCurrent(root, target, targetTimes)
    updateDaily(root, target, targetTimes, precipitationBlendedIndices)
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

private fun modelAgreement(source: JSONObject, suffixes: List<String>, index: Int): HourlyModelAgreement? {
    val rows = suffixes.mapNotNull { suffix ->
        fun value(field: String) = source.optJSONArray("${field}_$suffix").numberOrNull(index)
            ?.takeIf { isValidModelValue(field, it) }
        val temperature = value("temperature_2m") ?: return@mapNotNull null
        val wind = value("wind_speed_10m")?.takeIf { it >= 0 } ?: return@mapNotNull null
        val direction = value("wind_direction_10m")?.takeIf { it in 0.0..360.0 } ?: return@mapNotNull null
        val cloud = value("cloud_cover") ?: return@mapNotNull null
        val code = value("weather_code")?.toInt() ?: return@mapNotNull null
        val precipitation = value("precipitation") ?: return@mapNotNull null
        HourlyModelInput(suffix, temperature, wind, direction, cloud, code, precipitation)
    }
    if (rows.size !in MINIMUM_MODELS..MAX_FORECAST_MODEL_IDS) return null
    // Do not describe only a complete subset while partial inputs still affect the displayed blend.
    if (listOf("temperature_2m", "wind_speed_10m", "wind_direction_10m", "cloud_cover", "weather_code", "precipitation")
        .any { modelValues(source, suffixes, it, index).size != rows.size }) return null
    val conditions = rows.groupingBy { it.conditionGroup }.eachCount()
    val dominant = conditions.entries.sortedBy { it.key.first.ordinal }.maxBy { it.value }
    return HourlyModelAgreement(rows.size, rows.maxOf { it.temperatureCelsius } - rows.minOf { it.temperatureCelsius },
        rows.maxOf { first -> rows.maxOf { second ->
            hypot(first.eastWind - second.eastWind, first.northWind - second.northWind)
        } },
        rows.maxOf { it.cloudCoverPercent } - rows.minOf { it.cloudCoverPercent },
        dominant.value, rows.count { it.precipitationMm > 0 }, rows.minOf { it.precipitationMm }, rows.maxOf { it.precipitationMm }, dominant.key.first,
        allSkyConditions = rows.all { it.condition in SKY_CONDITION_KINDS }, modelInputs = rows)
}

internal fun applyRegionalCurrentSky(
    blend: ModelBlendResult,
    location: CzechLocation,
    regionalCurrentJson: String?,
): ModelBlendResult {
    if (!location.isInCzechia() || regionalCurrentJson == null) return blend
    val regionalRoot = runCatching { JSONObject(regionalCurrentJson) }.getOrNull() ?: return blend
    val root = JSONObject(blend.json)
    if (regionalRoot.opt("utc_offset_seconds") != root.opt("utc_offset_seconds")) return blend
    val current = root.getJSONObject("current")
    val time = current.getString("time")
    val preferred = regionalRoot.optJSONObject("current")?.takeIf { it.optString("time") == time }
    val preferredCode = (preferred?.opt("weather_code") as? Number)?.toDouble()
    // A valid phenomenon is a veto, not an incomplete dry sample to replace.
    if (preferredCode != null && isValidModelValue("weather_code", preferredCode) && preferredCode.toInt() !in 0..3) return blend
    val regional = preferred?.takeIf { sample ->
        val cloud = (sample.opt("cloud_cover") as? Number)?.toDouble()
        preferredCode != null && isValidModelValue("weather_code", preferredCode) &&
            cloud != null && cloud.isFinite() && cloud in 0.0..100.0
    } ?: run {
        // Separate requests can cross a quarter-hour boundary; never round either instant.
        val window = regionalRoot.optJSONObject("minutely_15") ?: return blend
        val times = window.optJSONArray("time") ?: return blend
        val index = (0 until times.length()).filter { times.opt(it) == time }.singleOrNull() ?: return blend
        JSONObject().put("time", time).also { matched ->
            listOf("weather_code", "cloud_cover", "cloud_cover_low", "cloud_cover_mid", "cloud_cover_high").forEach { field ->
                val values = window.optJSONArray(field)?.takeIf { it.length() == times.length() }
                val value = (values?.opt(index) as? Number)?.takeIf { it.toDouble().isFinite() }
                matched.put(field, value ?: JSONObject.NULL)
            }
        }
    }
    val code = (regional.opt("weather_code") as? Number)?.toDouble() ?: return blend
    val cloud = (regional.opt("cloud_cover") as? Number)?.toDouble() ?: return blend
    if (!isValidModelValue("weather_code", code) || code.toInt() !in 0..3 || !cloud.isFinite() || cloud !in 0.0..100.0) return blend
    val originalCode = (current.opt("weather_code") as? Number)?.toDouble() ?: return blend
    val precipitation = (current.opt("precipitation") as? Number)?.toDouble() ?: return blend
    if (!isValidModelValue("weather_code", originalCode) || originalCode.toInt() !in 0..3 ||
        !precipitation.isFinite() || precipitation !in 0.0..<WET_THRESHOLD_MM) return blend
    current.put("weather_code", code.toInt()).put("cloud_cover", cloud)
    listOf("cloud_cover_low", "cloud_cover_mid", "cloud_cover_high").forEach { field ->
        val value = (regional.opt(field) as? Number)?.toDouble()?.takeIf { it.isFinite() && it in 0.0..100.0 }
        if (value == null) current.remove(field) else current.put(field, value)
    }
    return blend.copy(json = root.toString(), currentSkyModelId = "chmi_aladin_seamless")
}

private fun blendPrecipitation(
    source: JSONObject,
    target: JSONObject,
    suffixes: List<String>,
    sourceIndex: Int,
    targetIndex: Int,
): Boolean {
    val ranked = suffixes.mapNotNull { suffix ->
        source.optJSONArray("precipitation_$suffix").numberOrNull(sourceIndex)
            ?.takeIf { it >= 0.0 }?.let { suffix to it }
    }.sortedWith(compareBy<Pair<String, Double>> { it.second }.thenBy { it.first })
    if (ranked.size < MINIMUM_MODELS) return false
    val total = target.optJSONArray("precipitation") ?: return false
    val middle = ranked.size / 2
    val selected = if (ranked.size % 2 == 1) listOf(ranked[middle]) else ranked.subList(middle - 1, middle + 1)
    val median = selected.map { it.second }.average()
    if (ranked.size <= MAX_FORECAST_MODEL_IDS) {
        target.getJSONArray(PRECIPITATION_SPREAD_KEY).put(targetIndex, JSONObject()
            .put("model_count", ranked.size)
            .put("wet_model_count", ranked.count { it.second > 0.0 })
            .put("minimum_mm", ranked.first().second)
            .put("maximum_mm", ranked.last().second))
    }
    // An unvalidated dry median cannot disprove the regionally selected wet forecast.
    if (median == 0.0 && (total.numberOrNull(targetIndex) ?: 0.0) > 0.0) return false
    total.put(targetIndex, median)
    // Keep each component with the source(s) that determine the median total.
    // Snowfall stays in cm; missing partitions never borrow a different model's zero.
    listOf("rain", "showers", "snowfall").forEach { field ->
        val values = selected.mapNotNull { (suffix, _) ->
            source.optJSONArray("${field}_$suffix").numberOrNull(sourceIndex)?.takeIf { it >= 0.0 }
        }
        target.optJSONArray(field)?.put(
            targetIndex,
            values.takeIf { it.size == selected.size }?.average() ?: JSONObject.NULL,
        )
    }
    return true
}

private fun blendCloudCover(
    source: JSONObject,
    target: JSONObject,
    suffixes: List<String>,
    sourceIndex: Int,
    targetIndex: Int,
): Boolean {
    val ranked = suffixes.mapNotNull { suffix ->
        source.optJSONArray("cloud_cover_$suffix").numberOrNull(sourceIndex)
            ?.takeIf { it in 0.0..100.0 }?.let { suffix to it }
    }.sortedWith(compareBy<Pair<String, Double>> { it.second }.thenBy { it.first })
    val total = target.optJSONArray("cloud_cover") ?: return false
    if (ranked.size < MINIMUM_MODELS) return false
    // A single regional member must not replace the multi-model sky estimate.
    val selected = ranked.subList((ranked.size - 1) / 2, ranked.size / 2 + 1)
    total.put(targetIndex, selected.map { it.second }.average())
    // Layer definitions differ by provider. Preserve the selected sources, not a union formula.
    listOf("cloud_cover_low", "cloud_cover_mid", "cloud_cover_high").forEach { field ->
        val values = selected.mapNotNull { (suffix, _) ->
            source.optJSONArray("${field}_$suffix").numberOrNull(sourceIndex)
                ?.takeIf { it in 0.0..100.0 }
        }
        target.optJSONArray(field)?.put(
            targetIndex,
            values.takeIf { it.size == selected.size }?.average() ?: JSONObject.NULL,
        )
    }
    return true
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
    // Direction disagreement is uncertainty, not evidence of calm wind.
    target.optJSONArray("wind_speed_10m")?.put(targetIndex, vectors.map { it.speed }.median())
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
    val codes = modelValues(source, suffixes, "weather_code", sourceIndex).map(Double::toInt)
    val explicitCode = phenomenonWeatherCode(codes)
    if (explicitCode != null) {
        target.optJSONArray("weather_code")?.put(targetIndex, explicitCode)
        return true
    }
    val clouds = modelValues(source, suffixes, "cloud_cover", sourceIndex)
    if (clouds.size < MINIMUM_MODELS) return false
    val cloudCover = target.optJSONArray("cloud_cover").numberOrNull(targetIndex)
        ?.roundToInt()?.coerceIn(0, 100) ?: return false
    val fallbackCode = target.optJSONArray("weather_code").numberOrNull(targetIndex)?.roundToInt()
    // Preceding-hour totals cannot establish rain at this instant or disprove a provider's phenomenon.
    if (fallbackCode != null && fallbackCode !in 0..3) return false
    val code = majoritySkyCode(codes) ?: cloudWeatherCode(cloudCover)
    target.optJSONArray("weather_code")?.put(targetIndex, code)
    return true
}

private fun phenomenonWeatherCode(codes: List<Int>): Int? {
    if (codes.size < MINIMUM_MODELS) return null
    val required = codes.size / 2 + 1
    codes.groupingBy { it }.eachCount().entries
        .firstOrNull { it.key !in 0..3 && it.value >= required }?.let { return it.key }
    val liquidCodes = codes.filter { it in DRIZZLE_CODES || it in 61..65 || it in 80..82 }
    return when {
        codes.count { it in 95..99 } >= required -> 95
        codes.count { it in 66..67 } >= required -> 66
        codes.count { it in 56..57 } >= required -> 56
        codes.count { it in DRIZZLE_CODES } >= required ->
            codes.filter { it in DRIZZLE_CODES }.sortedDescending()[required - 1]
        codes.count { it in 71..77 || it == 85 || it == 86 } >= required -> 71
        codes.count { it in 45..48 } >= required -> 45
        codes.count { it in 80..82 } >= required ->
            codes.filter { it in 80..82 }.sortedDescending()[required - 1]
        codes.count { it in 61..65 || it in 80..82 } >= required ->
            codes.filter { it in 61..65 || it in 80..82 }
                .map { when (it) { 80 -> 61; 81 -> 63; 82 -> 65; else -> it } }
                .sortedDescending()[required - 1]
        liquidCodes.size >= required -> liquidCodes.sorted().groupingBy { it }.eachCount().maxBy { it.value }.key
        else -> null
    }
}

private fun majoritySkyCode(codes: List<Int>): Int? {
    if (codes.size < MINIMUM_MODELS) return null
    // Provider sky categories need not use the same cloud-percentage thresholds.
    return (0..3).firstOrNull { code -> codes.count { it == code } > codes.size / 2 }
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

private fun updateDaily(root: JSONObject, hourly: JSONObject, times: JSONArray, precipitationBlendedIndices: Set<Int>) {
    val daily = root.getJSONObject("daily")
    val days = daily.getJSONArray("time")
    val intervalEnds = (0 until times.length()).associateBy { LocalDateTime.parse(times.getString(it)) }
    for (dayIndex in 0 until days.length()) {
        val date = days.getString(dayIndex)
        val indices = (0 until times.length()).filter { times.getString(it).startsWith(date) }
        if (indices.isEmpty()) continue
        val start = LocalDateTime.parse("${date}T00:00")
        // Preceding-hour totals end at 01:00 through next midnight; the API uses a fixed UTC offset even across DST.
        val precipitationIndices = (1L..24L).mapNotNull { intervalEnds[start.plusHours(it)] }
        val completePrecipitationDay = precipitationIndices.size == 24
        daily.putAt("temperature_2m_max", dayIndex, hourly.values("temperature_2m", indices).maxOrNull())
        daily.putAt("temperature_2m_min", dayIndex, hourly.values("temperature_2m", indices).minOrNull())
        daily.putAt("apparent_temperature_max", dayIndex, hourly.values("apparent_temperature", indices).maxOrNull())
        daily.putAt("apparent_temperature_min", dayIndex, hourly.values("apparent_temperature", indices).minOrNull())
        listOf("precipitation", "rain", "snowfall").forEach { field ->
            val sum = hourly.values(field, precipitationIndices)
                .takeIf { completePrecipitationDay && it.size == 24 }?.sum()
            if (field == "precipitation" && sum != null && precipitationIndices.any { it in precipitationBlendedIndices }) {
                // Provider wet duration does not describe the blended precipitation inputs.
                daily.optJSONArray("precipitation_hours")?.put(dayIndex, JSONObject.NULL)
            }
            if (field != "precipitation" && precipitationIndices.any { it in precipitationBlendedIndices }) {
                daily.optJSONArray("${field}_sum")?.put(dayIndex, sum ?: JSONObject.NULL)
            } else {
                daily.putAt("${field}_sum", dayIndex, sum)
            }
        }
        daily.putAt(
            "precipitation_probability_max",
            dayIndex,
            hourly.values("precipitation_probability", precipitationIndices)
                .takeIf { completePrecipitationDay && it.size == 24 }?.maxOrNull(),
        )
        daily.putAt("wind_speed_10m_max", dayIndex, hourly.values("wind_speed_10m", indices).filter { it >= 0 }.maxOrNull())
        daily.putAt("wind_gusts_10m_max", dayIndex, hourly.values("wind_gusts_10m", indices).filter { it >= 0 }.maxOrNull())
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
        if (speed < 0 || direction !in 0.0..360.0) return@mapNotNull null
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
    field == "weather_code" -> value == value.toInt().toDouble() && value.toInt() in MODEL_WEATHER_CODES
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

private fun weatherSeverity(code: Int): Int = 10 * when (code) {
    in 95..99 -> 7
    in 71..77, 85, 86 -> 6
    in 51..67, in 80..82 -> 5
    45, 48 -> 4
    3 -> 3
    2 -> 2
    1 -> 1
    else -> 0
} + when (code) {
    53, 63, 73, 81 -> 1
    55, 57, 65, 67, 75, 82, 86 -> 2
    else -> 0
}

private val CONTINUOUS_FIELDS = listOf(
    "temperature_2m",
    "relative_humidity_2m",
    "apparent_temperature",
    "pressure_msl",
    "surface_pressure",
    "wind_gusts_10m",
    "dew_point_2m",
    "visibility",
)
// Keep current sky and interval aggregates at the provider's current validity time.
private val CURRENT_FIELDS = CONTINUOUS_FIELDS - "wind_gusts_10m" + listOf(
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
    "wind_speed_10m",
    "wind_gusts_10m",
)
private const val MINIMUM_MODELS = 3
private const val WET_THRESHOLD_MM = 0.1
private val DRIZZLE_CODES = setOf(51, 53, 55)
private val MODEL_WEATHER_CODES = setOf(
    0, 1, 2, 3, 45, 48, 51, 53, 55, 56, 57, 61, 63, 65, 66, 67,
    71, 73, 75, 77, 80, 81, 82, 85, 86, 95, 96, 97, 99,
)
// Interval totals and wind need their own interval/vector contracts, not scalar substitution.
private val CALIBRATION_UNITS = mapOf(
    "temperature_2m" to "°C", "dew_point_2m" to "°C",
    "pressure_msl" to "hPa", "surface_pressure" to "hPa",
)
