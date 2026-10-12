package cz.majkey.pocasicesko.data

import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import org.json.JSONObject

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
    }
}

internal const val MODEL_AGREEMENT_KEY = "_selia_model_agreement"
internal enum class ConfidenceLevel { HIGH, MODERATE, LOW, LIMITED }
internal enum class ConfidenceReason { AGREEMENT, DISAGREEMENT, LONG_RANGE, FETCH_AGE, MISSING_MODELS, PARTIAL_DAY, HISTORICAL }
internal data class ForecastConfidence(
    val level: ConfidenceLevel,
    val reason: ConfidenceReason,
    val modelCount: Int = 0,
    val evidence: HourlyModelAgreement? = null,
    val coveredHours: Int? = null,
    val expectedHours: Int? = null,
    val validTime: String? = null,
    val precipitationEvidence: HourlyModelAgreement? = null,
)

internal fun hourlyForecastConfidence(
    hour: HourlyWeather,
    precipitationHour: HourlyWeather?,
    localNow: LocalDateTime?,
    fetchedAgeMillis: Long,
): ForecastConfidence {
    val time = runCatching { LocalDateTime.parse(hour.time) }.getOrNull()
    if (time == null || localNow == null || fetchedAgeMillis < 0 || time.minute != 0 || time.second != 0 || time.nano != 0 ||
        !hour.temperature.isFinite() || hour.temperature !in -100.0..70.0 || !hour.windSpeed.isFinite() || hour.windSpeed !in 0.0..500.0) {
        return ForecastConfidence(ConfidenceLevel.LIMITED, ConfidenceReason.MISSING_MODELS)
    }
    val lead = Duration.between(localNow, time).toHours()
    if (time < localNow.minusHours(1)) return ForecastConfidence(ConfidenceLevel.LIMITED, ConfidenceReason.HISTORICAL)
    val evidence = hour.modelAgreement ?: return ForecastConfidence(ConfidenceLevel.LIMITED, ConfidenceReason.MISSING_MODELS)
    val rain = precipitationHour?.modelAgreement ?: return ForecastConfidence(ConfidenceLevel.LIMITED, ConfidenceReason.MISSING_MODELS)
    if (!precipitationHour.precipitation.isFinite() || precipitationHour.precipitation < 0) {
        return ForecastConfidence(ConfidenceLevel.LIMITED, ConfidenceReason.MISSING_MODELS)
    }
    val count = minOf(evidence.modelCount, rain.modelCount)
    val wetShare = rain.wetModelCount.toDouble() / rain.modelCount
    val conditionShare = evidence.agreeingConditionCount.toDouble() / evidence.modelCount
    val shownCondition = conditionFor(hour.weatherCode, hour.isDay).kind
    val conditionMismatch = shownCondition != evidence.dominantCondition
    val strongMismatch = conditionMismatch && (shownCondition !in SKY_CONDITION_KINDS || evidence.dominantCondition !in SKY_CONDITION_KINDS ||
        kotlin.math.abs(SKY_CONDITION_KINDS.indexOf(shownCondition) - SKY_CONDITION_KINDS.indexOf(evidence.dominantCondition)) > 1)
    val wetMismatch = precipitationHour.precipitation < rain.minimumPrecipitationMm - 1e-6 ||
        precipitationHour.precipitation > rain.maximumPrecipitationMm + 1e-6
    val rainfallSpread = rain.maximumPrecipitationMm - rain.minimumPrecipitationMm
    val rainfallConflict = rainfallSpread > maxOf(1.0, rain.maximumPrecipitationMm * 0.5)
    // Adjacent sky codes can split at a cloud threshold despite a tight continuous spread.
    val skyAgreement = evidence.allSkyConditions == true
    val conflicting = evidence.temperatureRangeCelsius > 5 || evidence.windRangeKmh > 25 ||
        evidence.cloudRangePercent > 60 || conditionShare < 0.6 && !skyAgreement || wetShare > 0.2 && wetShare < 0.8 || strongMismatch || wetMismatch || rainfallConflict
    val agreeing = count >= 5 && evidence.temperatureRangeCelsius <= 2 && evidence.windRangeKmh <= 10 &&
        evidence.cloudRangePercent <= 25 && (conditionShare >= 0.8 || skyAgreement) && (wetShare <= 0.2 || wetShare >= 0.8)
    val (level, reason) = when {
        fetchedAgeMillis > Duration.ofHours(12).toMillis() -> ConfidenceLevel.LOW to ConfidenceReason.FETCH_AGE
        lead > 168 -> ConfidenceLevel.LOW to ConfidenceReason.LONG_RANGE
        conflicting -> ConfidenceLevel.LOW to ConfidenceReason.DISAGREEMENT
        lead > 48 -> ConfidenceLevel.MODERATE to ConfidenceReason.LONG_RANGE
        fetchedAgeMillis > Duration.ofHours(3).toMillis() -> ConfidenceLevel.MODERATE to ConfidenceReason.FETCH_AGE
        conditionMismatch -> ConfidenceLevel.MODERATE to ConfidenceReason.DISAGREEMENT
        agreeing -> ConfidenceLevel.HIGH to ConfidenceReason.AGREEMENT
        else -> ConfidenceLevel.MODERATE to ConfidenceReason.AGREEMENT
    }
    return ForecastConfidence(level, reason, count, evidence, validTime = hour.time, precipitationEvidence = rain)
}

internal fun dailyForecastConfidence(
    date: String,
    hourly: List<HourlyWeather>,
    precipitationByStart: Map<String, HourlyWeather?>,
    localNow: LocalDateTime?,
    fetchedAgeMillis: Long,
): ForecastConfidence {
    val day = runCatching { LocalDate.parse(date) }.getOrNull()
    if (day == null || localNow == null) return ForecastConfidence(ConfidenceLevel.LIMITED, ConfidenceReason.MISSING_MODELS)
    if (day < localNow.toLocalDate()) return ForecastConfidence(ConfidenceLevel.LIMITED, ConfidenceReason.HISTORICAL)
    val firstHour = if (day == localNow.toLocalDate()) localNow.hour else 0
    val expected = 24 - firstHour
    val ratings = hourly.filter { hour ->
        val time = runCatching { LocalDateTime.parse(hour.time) }.getOrNull()
        time != null && time.toLocalDate() == day && time.hour >= firstHour
    }.distinctBy { it.time }.map { hourlyForecastConfidence(it, precipitationByStart[it.time], localNow, fetchedAgeMillis) }
    val covered = ratings.filter { it.level != ConfidenceLevel.LIMITED }
    if (covered.size * 4 < expected * 3) return ForecastConfidence(ConfidenceLevel.LIMITED, ConfidenceReason.PARTIAL_DAY,
        coveredHours = covered.size, expectedHours = expected)
    val leastCertain = covered.maxByOrNull { it.level.ordinal }
        ?: return ForecastConfidence(ConfidenceLevel.LIMITED, ConfidenceReason.MISSING_MODELS)
    return if (covered.size < expected && leastCertain.level == ConfidenceLevel.HIGH) {
        leastCertain.copy(level = ConfidenceLevel.MODERATE, reason = ConfidenceReason.PARTIAL_DAY, coveredHours = covered.size, expectedHours = expected)
    } else leastCertain.copy(coveredHours = covered.size, expectedHours = expected)
}

internal fun HourlyModelAgreement.toJson(): JSONObject = JSONObject().put("schema_version", 1)
    .put("model_count", modelCount).put("temperature_range_celsius", temperatureRangeCelsius)
    .put("wind_range_kmh", windRangeKmh).put("cloud_range_percent", cloudRangePercent)
    .put("agreeing_condition_count", agreeingConditionCount).put("wet_model_count", wetModelCount)
    .put("minimum_precipitation_mm", minimumPrecipitationMm).put("maximum_precipitation_mm", maximumPrecipitationMm)
    .put("dominant_condition", dominantCondition.name)
    .apply { allSkyConditions?.let { put("all_sky_conditions", it) } }

internal fun JSONObject.modelAgreementOrNull(): HourlyModelAgreement? = runCatching {
    fun number(key: String): Double {
        val value = get(key)
        require(value is Number && value.toDouble().isFinite())
        return value.toDouble()
    }
    fun count(key: String): Int = number(key).let { value -> require(value == value.toInt().toDouble()); value.toInt() }
    require(count("schema_version") == 1)
    val allSkyConditions = if (has("all_sky_conditions")) {
        val value = get("all_sky_conditions")
        require(value is Boolean)
        value
    } else null
    HourlyModelAgreement(count("model_count"), number("temperature_range_celsius"), number("wind_range_kmh"),
        number("cloud_range_percent"), count("agreeing_condition_count"), count("wet_model_count"),
        number("minimum_precipitation_mm"), number("maximum_precipitation_mm"), enumValueOf(getString("dominant_condition")), allSkyConditions)
}.getOrNull()
