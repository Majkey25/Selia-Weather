package cz.majkey.pocasicesko.data

import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import org.json.JSONObject
import org.json.JSONArray

data class HourlyModelInput(
    val modelId: String,
    val temperatureCelsius: Double,
    val windSpeedKmh: Double,
    val windDirectionDegrees: Double,
    val cloudCoverPercent: Double,
    val weatherCode: Int,
    val precipitationMm: Double,
) {
    init {
        require(isModelId(modelId))
        require(listOf(temperatureCelsius, windSpeedKmh, windDirectionDegrees, cloudCoverPercent, precipitationMm).all(Double::isFinite))
        require(windSpeedKmh >= 0 && windDirectionDegrees in 0.0..360.0 && cloudCoverPercent in 0.0..100.0 && precipitationMm >= 0)
        require(conditionFor(weatherCode).kind != WeatherKind.UNKNOWN)
    }
    internal val eastWind get() = windSpeedKmh * kotlin.math.sin(Math.toRadians(windDirectionDegrees))
    internal val northWind get() = windSpeedKmh * kotlin.math.cos(Math.toRadians(windDirectionDegrees))
    internal val condition get() = conditionFor(weatherCode).kind
    internal val conditionGroup get() = conditionFor(weatherCode).agreementGroup
}

private val WeatherCondition.agreementGroup get() =
    Triple(kind, weatherCode in 56..57 || weatherCode in 66..67, weatherCode in 68..69)

internal val SKY_CONDITION_KINDS = listOf(WeatherKind.CLEAR, WeatherKind.MAINLY_CLEAR, WeatherKind.PARTLY_CLOUDY, WeatherKind.CLOUDY)

/** Descriptive agreement between complete model inputs at one validity time. */
data class HourlyModelAgreement(
    val modelCount: Int,
    val temperatureRangeCelsius: Double,
    val windRangeKmh: Double,
    val cloudRangePercent: Double,
    val agreeingConditionCount: Int,
    val wetModelCount: Int,
    val minimumPrecipitationMm: Double,
    val maximumPrecipitationMm: Double,
    val dominantCondition: WeatherKind,
    val allSkyConditions: Boolean? = null,
    val modelInputs: List<HourlyModelInput> = emptyList(),
) {
    init {
        require(modelCount in 3..MAX_FORECAST_MODEL_IDS)
        require(dominantCondition != WeatherKind.UNKNOWN)
        require(allSkyConditions != true || dominantCondition in SKY_CONDITION_KINDS)
        require(agreeingConditionCount in 1..modelCount && wetModelCount in 0..modelCount)
        require(listOf(temperatureRangeCelsius, windRangeKmh, cloudRangePercent, minimumPrecipitationMm, maximumPrecipitationMm)
            .all { it.isFinite() && it >= 0 })
        require(cloudRangePercent <= 100 && maximumPrecipitationMm >= minimumPrecipitationMm)
        require((wetModelCount == 0) == (maximumPrecipitationMm == 0.0))
        require((wetModelCount == modelCount) == (minimumPrecipitationMm > 0.0))
        require(modelInputs.isEmpty() || modelInputs.size == modelCount && modelInputs.map { it.modelId }.distinct().size == modelCount)
    }
}

internal const val MODEL_AGREEMENT_KEY = "_selia_model_agreement"
internal const val SKY_SOURCE_MODEL_KEY = "_selia_sky_source_model"
internal enum class ConfidenceLevel { HIGH, MODERATE, LOW, LIMITED }
internal enum class ConfidenceReason { AGREEMENT, DISAGREEMENT, LONG_RANGE, FETCH_AGE, MISSING_MODELS, PARTIAL_DAY, HISTORICAL, UNKNOWN_CONDITION }
internal enum class ForecastDisagreement { TEMPERATURE, WIND, CLOUDS, CONDITIONS, RAIN_OCCURRENCE, RAIN_AMOUNT, DISPLAYED_CONDITION, DISPLAYED_PRECIPITATION }
internal data class ForecastConfidence(
    val level: ConfidenceLevel,
    val reason: ConfidenceReason,
    val modelCount: Int = 0,
    val evidence: HourlyModelAgreement? = null,
    val coveredHours: Int? = null,
    val expectedHours: Int? = null,
    val validTime: String? = null,
    val precipitationEvidence: HourlyModelAgreement? = null,
    val disagreements: List<ForecastDisagreement> = emptyList(),
    val skySourceModelId: String? = null,
    val shownConditionTime: String? = null,
)

internal fun hourlyForecastConfidence(
    hour: HourlyWeather,
    precipitationHour: HourlyWeather?,
    localNow: LocalDateTime?,
    fetchedAgeMillis: Long,
    shownCondition: WeatherCondition = conditionFor(hour.weatherCode, hour.isDay),
    skySourceModelId: String? = hour.skySourceModelId,
    shownConditionTime: String? = hour.time,
): ForecastConfidence {
    val time = runCatching { LocalDateTime.parse(hour.time) }.getOrNull()
    val shownTime = shownConditionTime?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }
    if (time == null || shownConditionTime != null && shownTime == null || localNow == null || fetchedAgeMillis < 0 || time.minute != 0 || time.second != 0 || time.nano != 0 ||
        !hour.temperature.isFinite() || hour.temperature !in -100.0..70.0 || !hour.windSpeed.isFinite() || hour.windSpeed !in 0.0..500.0) {
        return ForecastConfidence(ConfidenceLevel.LIMITED, ConfidenceReason.MISSING_MODELS)
    }
    val lead = Duration.between(localNow, time).toHours()
    if (time < localNow.minusHours(1)) return ForecastConfidence(ConfidenceLevel.LIMITED, ConfidenceReason.HISTORICAL,
        modelCount = hour.modelAgreement?.modelCount ?: 0, evidence = hour.modelAgreement,
        precipitationEvidence = precipitationHour?.modelAgreement, validTime = hour.time,
        skySourceModelId = skySourceModelId, shownConditionTime = shownConditionTime)
    val evidence = hour.modelAgreement ?: return ForecastConfidence(ConfidenceLevel.LIMITED, ConfidenceReason.MISSING_MODELS,
        validTime = hour.time, skySourceModelId = skySourceModelId, shownConditionTime = shownConditionTime)
    val rain = precipitationHour?.modelAgreement ?: return ForecastConfidence(ConfidenceLevel.LIMITED, ConfidenceReason.MISSING_MODELS,
        evidence = evidence, validTime = hour.time, skySourceModelId = skySourceModelId, shownConditionTime = shownConditionTime)
    if (!precipitationHour.precipitation.isFinite() || precipitationHour.precipitation < 0) {
        return ForecastConfidence(ConfidenceLevel.LIMITED, ConfidenceReason.MISSING_MODELS)
    }
    val count = minOf(evidence.modelCount, rain.modelCount)
    val wetShare = rain.wetModelCount.toDouble() / rain.modelCount
    val dominantGroup = evidence.modelInputs.takeIf { it.isNotEmpty() }
        ?.groupingBy { it.conditionGroup }?.eachCount()?.entries
        ?.sortedBy { it.key.first.ordinal }?.maxByOrNull { it.value }
    val conditionCount = dominantGroup?.value ?: evidence.agreeingConditionCount
    val conditionShare = conditionCount.toDouble() / evidence.modelCount
    // Current conditions can describe a later quarter-hour than the forecast evidence.
    val conditionMismatch = shownCondition.kind != WeatherKind.UNKNOWN && shownTime == time && (shownCondition.kind != evidence.dominantCondition ||
        dominantGroup != null && shownCondition.agreementGroup != dominantGroup.key)
    val strongMismatch = conditionMismatch && (shownCondition.kind !in SKY_CONDITION_KINDS || evidence.dominantCondition !in SKY_CONDITION_KINDS ||
        kotlin.math.abs(SKY_CONDITION_KINDS.indexOf(shownCondition.kind) - SKY_CONDITION_KINDS.indexOf(evidence.dominantCondition)) > 1)
    val wetMismatch = precipitationHour.precipitation < rain.minimumPrecipitationMm - 1e-6 ||
        precipitationHour.precipitation > rain.maximumPrecipitationMm + 1e-6
    val rainfallSpread = rain.maximumPrecipitationMm - rain.minimumPrecipitationMm
    val rainfallConflict = rainfallSpread > maxOf(1.0, rain.maximumPrecipitationMm * 0.5)
    // Adjacent sky codes can split at a cloud threshold despite a tight continuous spread.
    val skyAgreement = evidence.allSkyConditions == true
    val disagreements = buildList {
        if (evidence.temperatureRangeCelsius > 5) add(ForecastDisagreement.TEMPERATURE)
        if (evidence.windRangeKmh > 25) add(ForecastDisagreement.WIND)
        if (evidence.cloudRangePercent > 60) add(ForecastDisagreement.CLOUDS)
        if (conditionShare < 0.6 && !skyAgreement) add(ForecastDisagreement.CONDITIONS)
        if (wetShare > 0.2 && wetShare < 0.8) add(ForecastDisagreement.RAIN_OCCURRENCE)
        if (rainfallConflict) add(ForecastDisagreement.RAIN_AMOUNT)
        if (strongMismatch) add(ForecastDisagreement.DISPLAYED_CONDITION)
        if (wetMismatch) add(ForecastDisagreement.DISPLAYED_PRECIPITATION)
    }
    val conflicting = disagreements.isNotEmpty()
    val agreeing = count >= 5 && evidence.temperatureRangeCelsius <= 2 && evidence.windRangeKmh <= 10 &&
        evidence.cloudRangePercent <= 25 && (conditionShare >= 0.8 || skyAgreement) && (wetShare <= 0.2 || wetShare >= 0.8)
    val (level, reason) = when {
        fetchedAgeMillis > Duration.ofHours(12).toMillis() -> ConfidenceLevel.LOW to ConfidenceReason.FETCH_AGE
        lead > 168 -> ConfidenceLevel.LOW to ConfidenceReason.LONG_RANGE
        conflicting -> ConfidenceLevel.LOW to ConfidenceReason.DISAGREEMENT
        shownCondition.kind == WeatherKind.UNKNOWN -> ConfidenceLevel.LIMITED to ConfidenceReason.UNKNOWN_CONDITION
        lead > 48 -> ConfidenceLevel.MODERATE to ConfidenceReason.LONG_RANGE
        fetchedAgeMillis > Duration.ofHours(3).toMillis() -> ConfidenceLevel.MODERATE to ConfidenceReason.FETCH_AGE
        shownTime == null -> ConfidenceLevel.MODERATE to ConfidenceReason.MISSING_MODELS
        conditionMismatch -> ConfidenceLevel.MODERATE to ConfidenceReason.DISAGREEMENT
        agreeing -> ConfidenceLevel.HIGH to ConfidenceReason.AGREEMENT
        else -> ConfidenceLevel.MODERATE to ConfidenceReason.AGREEMENT
    }
    return ForecastConfidence(level, reason, count, evidence, validTime = hour.time, precipitationEvidence = rain,
        disagreements = disagreements, skySourceModelId = skySourceModelId, shownConditionTime = shownConditionTime)
}

internal fun dailyForecastConfidence(
    date: String,
    hourly: List<HourlyWeather>,
    precipitationByStart: Map<String, HourlyWeather?>,
    localNow: LocalDateTime?,
    fetchedAgeMillis: Long,
    current: CurrentWeather? = null,
    currentSkyModelId: String? = null,
    utcOffsetSeconds: Int? = null,
): ForecastConfidence {
    val day = runCatching { LocalDate.parse(date) }.getOrNull()
    if (day == null || localNow == null) return ForecastConfidence(ConfidenceLevel.LIMITED, ConfidenceReason.MISSING_MODELS)
    val historical = day < localNow.toLocalDate()
    val firstHour = if (day == localNow.toLocalDate()) localNow.hour else 0
    val expected = 24 - firstHour
    val currentHour = current?.takeIf {
        val time = runCatching { LocalDateTime.parse(it.time) }.getOrNull()
        time != null && time.toLocalDate() == localNow.toLocalDate() && time.hour == localNow.hour
    }
    val ratings = hourly.filter { hour ->
        val time = runCatching { LocalDateTime.parse(hour.time) }.getOrNull()
        time != null && time.toLocalDate() == day && time.hour >= firstHour
    }.distinctBy { it.time }.map { hour ->
        val shownCurrent = currentHour?.takeIf { hour.time.take(13) == localNow.toString().take(13) }
        hourlyForecastConfidence(hour, precipitationByStart[hour.time], localNow, fetchedAgeMillis,
            conditionFor(shownCurrent?.weatherCode ?: hour.weatherCode, shownCurrent?.isDay ?: hour.isDay),
            if (shownCurrent != null) currentSkyModelId else hour.skySourceModelId,
            shownConditionTime = if (shownCurrent != null) shownCurrent.skyConditionTime(utcOffsetSeconds) else hour.time)
    }
    val covered = ratings.filter { it.level != ConfidenceLevel.LIMITED }
    if (historical) return ratings.filter { it.evidence != null }.minByOrNull { it.validTime.orEmpty() }
        ?: ForecastConfidence(ConfidenceLevel.LIMITED, ConfidenceReason.HISTORICAL)
    if (covered.size * 4 < expected * 3) return ForecastConfidence(ConfidenceLevel.LIMITED, ConfidenceReason.PARTIAL_DAY,
        coveredHours = covered.size, expectedHours = expected)
    val leastCertain = covered.maxByOrNull { it.level.ordinal }
        ?: return ForecastConfidence(ConfidenceLevel.LIMITED, ConfidenceReason.MISSING_MODELS)
    return if (covered.size < expected && leastCertain.level == ConfidenceLevel.HIGH) {
        leastCertain.copy(level = ConfidenceLevel.MODERATE, reason = ConfidenceReason.PARTIAL_DAY, coveredHours = covered.size, expectedHours = expected)
    } else leastCertain.copy(coveredHours = covered.size, expectedHours = expected)
}

internal fun HourlyModelAgreement.toJson(): JSONObject = JSONObject().put("schema_version", 2)
    .put("model_count", modelCount).put("temperature_range_celsius", temperatureRangeCelsius)
    .put("wind_range_kmh", windRangeKmh).put("cloud_range_percent", cloudRangePercent)
    .put("agreeing_condition_count", agreeingConditionCount).put("wet_model_count", wetModelCount)
    .put("minimum_precipitation_mm", minimumPrecipitationMm).put("maximum_precipitation_mm", maximumPrecipitationMm)
    .put("dominant_condition", dominantCondition.name)
    .apply { allSkyConditions?.let { put("all_sky_conditions", it) } }
    .put("model_inputs", JSONArray(modelInputs.map { input -> JSONObject()
        .put("model_id", input.modelId).put("temperature_celsius", input.temperatureCelsius)
        .put("wind_speed_kmh", input.windSpeedKmh).put("wind_direction_degrees", input.windDirectionDegrees)
        .put("cloud_cover_percent", input.cloudCoverPercent).put("weather_code", input.weatherCode)
        .put("precipitation_mm", input.precipitationMm) }))

internal fun JSONObject.modelAgreementOrNull(): HourlyModelAgreement? = runCatching {
    fun number(key: String): Double {
        val value = get(key)
        require(value is Number && value.toDouble().isFinite())
        return value.toDouble()
    }
    fun count(key: String): Int = number(key).let { value -> require(value == value.toInt().toDouble()); value.toInt() }
    require(count("schema_version") in 1..2)
    val allSkyConditions = if (has("all_sky_conditions")) {
        val value = get("all_sky_conditions")
        require(value is Boolean)
        value
    } else null
    val inputs = if (has("model_inputs")) {
        val array = getJSONArray("model_inputs")
        require(array.length() <= MAX_FORECAST_MODEL_IDS)
        List(array.length()) { index ->
            val input = array.getJSONObject(index)
            fun value(key: String): Double {
                val value = input.get(key)
                require(value is Number && value.toDouble().isFinite())
                return value.toDouble()
            }
            val code = value("weather_code")
            require(code == code.toInt().toDouble())
            HourlyModelInput(input.getString("model_id"), value("temperature_celsius"), value("wind_speed_kmh"),
                value("wind_direction_degrees"), value("cloud_cover_percent"), code.toInt(), value("precipitation_mm"))
        }
    } else emptyList()
    HourlyModelAgreement(count("model_count"), number("temperature_range_celsius"), number("wind_range_kmh"),
        number("cloud_range_percent"), count("agreeing_condition_count"), count("wet_model_count"),
        number("minimum_precipitation_mm"), number("maximum_precipitation_mm"), enumValueOf(getString("dominant_condition")), allSkyConditions, inputs)
}.getOrNull()
