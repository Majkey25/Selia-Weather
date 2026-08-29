package cz.majkey.pocasicesko.data

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import org.json.JSONArray
import org.json.JSONObject

internal fun blendModelForecast(bestMatchJson: String, modelsJson: String): String {
    val root = JSONObject(bestMatchJson)
    val target = root.getJSONObject("hourly")
    val source = JSONObject(modelsJson).getJSONObject("hourly")
    val suffixes = source.keys().asSequence()
        .filter { it.startsWith("temperature_2m_") }
        .map { it.removePrefix("temperature_2m_") }
        .sorted()
        .toList()
    if (suffixes.size < MINIMUM_MODELS) return bestMatchJson

    val sourceTimes = source.getJSONArray("time")
    val sourceIndices = (0 until sourceTimes.length()).associateBy { sourceTimes.getString(it) }
    val targetTimes = target.getJSONArray("time")
    for (targetIndex in 0 until targetTimes.length()) {
        val sourceIndex = sourceIndices[targetTimes.getString(targetIndex)] ?: continue
        CONTINUOUS_FIELDS.forEach { field ->
            modelValues(source, suffixes, field, sourceIndex).takeIf { it.size >= MINIMUM_MODELS }
                ?.let { values -> target.optJSONArray(field)?.put(targetIndex, values.median()) }
        }
        blendWind(source, target, suffixes, sourceIndex, targetIndex)
        derivePrecipitationAndCondition(source, target, suffixes, sourceIndex, targetIndex)
    }
    updateCurrent(root, target, targetTimes)
    updateDaily(root, target, targetTimes)
    return root.toString()
}

private fun blendWind(
    source: JSONObject,
    target: JSONObject,
    suffixes: List<String>,
    sourceIndex: Int,
    targetIndex: Int,
) {
    val vectors = suffixes.mapNotNull { suffix ->
        val speed = source.optJSONArray("wind_speed_10m_$suffix").numberOrNull(sourceIndex)
            ?: return@mapNotNull null
        val direction = source.optJSONArray("wind_direction_10m_$suffix").numberOrNull(sourceIndex)
            ?: return@mapNotNull null
        if (speed < 0 || direction !in 0.0..360.0) return@mapNotNull null
        speed to Math.toRadians(direction)
    }
    if (vectors.size < MINIMUM_MODELS) return
    val east = vectors.sumOf { (speed, angle) -> speed * sin(angle) } / vectors.size
    val north = vectors.sumOf { (speed, angle) -> speed * cos(angle) } / vectors.size
    target.optJSONArray("wind_speed_10m")?.put(targetIndex, hypot(east, north))
    target.optJSONArray("wind_direction_10m")?.put(
        targetIndex,
        ((Math.toDegrees(atan2(east, north)) + 360.0) % 360.0).roundToInt() % 360,
    )
}

private fun derivePrecipitationAndCondition(
    source: JSONObject,
    target: JSONObject,
    suffixes: List<String>,
    sourceIndex: Int,
    targetIndex: Int,
) {
    val precipitation = modelValues(source, suffixes, "precipitation", sourceIndex)
    val clouds = modelValues(source, suffixes, "cloud_cover", sourceIndex)
    if (precipitation.size < MINIMUM_MODELS || clouds.size < MINIMUM_MODELS) return
    val probability = (precipitation.count { it >= WET_THRESHOLD_MM } * 100.0 / precipitation.size)
        .roundToInt()
    val cloudCover = clouds.median().roundToInt().coerceIn(0, 100)
    target.optJSONArray("precipitation_probability")?.put(targetIndex, probability)
    target.optJSONArray("weather_code")?.put(
        targetIndex,
        deriveWeatherCode(
            modelValues(source, suffixes, "weather_code", sourceIndex).map(Double::roundToInt),
            probability,
            cloudCover,
        ),
    )
}

private fun deriveWeatherCode(codes: List<Int>, rainProbability: Int, cloudCover: Int): Int {
    val required = (codes.size + 1) / 2
    return when {
        codes.isNotEmpty() && codes.count { it in 95..99 } >= required -> 95
        codes.isNotEmpty() && codes.count { it in 71..77 || it == 85 || it == 86 } >= required -> 71
        codes.isNotEmpty() && codes.count { it in 45..48 } >= required -> 45
        rainProbability >= 50 -> 61
        cloudCover <= 20 -> 0
        cloudCover <= 50 -> 1
        cloudCover <= 80 -> 2
        else -> 3
    }
}

private fun updateCurrent(root: JSONObject, hourly: JSONObject, times: JSONArray) {
    val current = root.getJSONObject("current")
    val currentHour = current.getString("time").take(13)
    val index = (0 until times.length()).firstOrNull { times.getString(it).take(13) == currentHour }
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
        daily.putAt("precipitation_sum", dayIndex, hourly.values("precipitation", indices).sum())
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
private val CURRENT_FIELDS = CONTINUOUS_FIELDS + listOf(
    "weather_code",
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
