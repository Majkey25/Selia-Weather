package cz.majkey.pocasicesko.data

import java.time.Instant
import kotlin.math.abs
import kotlin.math.floor

internal fun interpolateStaticValues(
    values: List<StaticModelValue>,
    grid: StaticFeedGrid,
    latitude: Double,
    longitude: Double,
): List<StaticModelValue> {
    require(latitude.isFinite() && longitude.isFinite()) { "Interpolation coordinates must be finite." }
    require(latitude in grid.south..grid.north && longitude in grid.west..grid.east) {
        "Interpolation coordinates are outside the forecast grid."
    }
    val continuousValues = values.filter(StaticModelValue::supportsScalarInterpolation)
    require(values.isEmpty() || continuousValues.isNotEmpty()) {
        "Directional or categorical values cannot use scalar interpolation."
    }
    val south = grid.south + floor((latitude - grid.south) / grid.step) * grid.step
    val west = grid.west + floor((longitude - grid.west) / grid.step) * grid.step

    return continuousValues.groupBy(StaticModelValue::seriesKey).mapNotNull { (_, series) ->
        val exact = series.valueAt(latitude, longitude)
        if (exact != null) {
            exact.value?.let { exact.copy(latitude = latitude, longitude = longitude) }
        } else {
            series.interpolateCell(south, west, grid.step, latitude, longitude)
        }
    }
}

private fun List<StaticModelValue>.interpolateCell(
    south: Double,
    west: Double,
    step: Double,
    latitude: Double,
    longitude: Double,
): StaticModelValue? {
    val north = south + step
    val east = west + step
    val corners = listOf(
        valueAt(south, west),
        valueAt(south, east),
        valueAt(north, west),
        valueAt(north, east),
    )
    if (corners.any { it?.value == null }) return null
    val present = corners.filterNotNull()
    val x = (longitude - west) / step
    val y = (latitude - south) / step
    val interpolatedValue = bilinear(present.map { requireNotNull(it.value) }, x, y)
    val interpolatedElevation = bilinear(present.map(StaticModelValue::elevationMeters), x, y)
    return present.first().copy(
        latitude = latitude,
        longitude = longitude,
        elevationMeters = interpolatedElevation,
        value = interpolatedValue,
    )
}

private fun List<StaticModelValue>.valueAt(latitude: Double, longitude: Double): StaticModelValue? =
    firstOrNull { abs(it.latitude - latitude) < EPSILON && abs(it.longitude - longitude) < EPSILON }

private fun bilinear(values: List<Double>, x: Double, y: Double): Double =
    values[0] * (1 - x) * (1 - y) +
        values[1] * x * (1 - y) +
        values[2] * (1 - x) * y +
        values[3] * x * y

private data class StaticModelSeriesKey(
    val sourceId: String,
    val modelId: String,
    val runTime: Instant,
    val validTime: Instant,
    val variable: String,
    val unit: String,
)

private fun StaticModelValue.seriesKey() = StaticModelSeriesKey(
    sourceId = sourceId,
    modelId = modelId,
    runTime = runTime,
    validTime = validTime,
    variable = variable,
    unit = unit,
)

private fun StaticModelValue.supportsScalarInterpolation(): Boolean =
    "direction" !in variable.lowercase() && variable != "weather_code" && variable != "is_day"

private const val EPSILON = 1e-8
