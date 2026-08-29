package cz.majkey.pocasicesko.data

import java.time.Instant
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

internal enum class PrecipitationKind {
    DRY,
    RAIN,
    SNOW,
    MIXED,
    UNAVAILABLE,
}

internal data class PrecipitationFieldPoint(
    val row: Int,
    val column: Int,
    val latitude: Double,
    val longitude: Double,
    val offsetEastKm: Double,
    val offsetNorthKm: Double,
) {
    init {
        require(row in 0 until GRID_SIZE && column in 0 until GRID_SIZE)
        require(latitude.isFinite() && latitude in -90.0..90.0)
        require(longitude.isFinite() && longitude in -180.0..180.0)
        require(offsetEastKm.isFinite() && offsetNorthKm.isFinite())
        require(hypot(offsetEastKm, offsetNorthKm) <= FIELD_RADIUS_KM + EPSILON)
    }
}

internal data class PrecipitationFieldCell(
    val point: PrecipitationFieldPoint,
    val precipitationMm: Double?,
    val rainMm: Double?,
    val showersMm: Double?,
    val snowfallCm: Double?,
    val probabilityPercent: Int?,
    val agreementPercent: Int?,
    val contributorCount: Int,
    val minimumMm: Double?,
    val maximumMm: Double?,
    val kind: PrecipitationKind,
) {
    init {
        if (kind == PrecipitationKind.UNAVAILABLE) {
            require(
                listOf(
                    precipitationMm,
                    rainMm,
                    showersMm,
                    snowfallCm,
                    probabilityPercent,
                    agreementPercent,
                    minimumMm,
                    maximumMm,
                ).all { it == null } && contributorCount == 0,
            )
        } else {
            val amounts = listOfNotNull(
                precipitationMm,
                rainMm,
                showersMm,
                snowfallCm,
                minimumMm,
                maximumMm,
            )
            require(amounts.size == 6 && amounts.all { it.isFinite() && it >= 0 })
            require(probabilityPercent != null && probabilityPercent in 0..100)
            require(agreementPercent != null && agreementPercent in 0..100)
            require(contributorCount >= MINIMUM_CONTRIBUTORS)
            require(requireNotNull(minimumMm) <= requireNotNull(precipitationMm))
            require(requireNotNull(precipitationMm) <= requireNotNull(maximumMm))
        }
    }
}

internal data class PrecipitationFieldFrame(
    val validTime: Instant,
    val cells: List<PrecipitationFieldCell>,
) {
    init {
        require(cells.size == GRID_SIZE * GRID_SIZE)
        require(cells.map { it.point.row to it.point.column } == GRID_ORDER)
    }
}

internal data class PrecipitationField(val frames: List<PrecipitationFieldFrame>) {
    init {
        require(frames.size in 1..MAX_FRAMES)
        require(frames.zipWithNext().all { (first, second) -> first.validTime < second.validTime })
    }
}

internal fun precipitationFieldPoints(location: CzechLocation): List<PrecipitationFieldPoint> {
    require(location.latitude.isFinite() && location.latitude in -90.0..90.0)
    require(location.longitude.isFinite() && location.longitude in -180.0..180.0)
    return GRID_ORDER.map { (row, column) ->
        val east = GRID_OFFSETS_KM[column]
        val north = GRID_OFFSETS_KM[GRID_SIZE - 1 - row]
        val distance = hypot(east, north)
        val (latitude, longitude) = destination(
            location.latitude,
            location.longitude,
            distance,
            atan2(east, north),
        )
        PrecipitationFieldPoint(row, column, latitude, longitude, east, north)
    }
}

private fun destination(
    latitude: Double,
    longitude: Double,
    distanceKm: Double,
    bearingRadians: Double,
): Pair<Double, Double> {
    if (distanceKm == 0.0) return latitude to longitude
    val latitudeRadians = Math.toRadians(latitude)
    val longitudeRadians = Math.toRadians(longitude)
    val angularDistance = distanceKm / EARTH_RADIUS_KM
    val resultLatitude = asin(
        sin(latitudeRadians) * cos(angularDistance) +
            cos(latitudeRadians) * sin(angularDistance) * cos(bearingRadians),
    )
    val resultLongitude = longitudeRadians + atan2(
        sin(bearingRadians) * sin(angularDistance) * cos(latitudeRadians),
        cos(angularDistance) - sin(latitudeRadians) * sin(resultLatitude),
    )
    val normalizedLongitude = (Math.toDegrees(resultLongitude) + 540.0) % 360.0 - 180.0
    return Math.toDegrees(resultLatitude) to normalizedLongitude
}

private const val GRID_SIZE = 5
private const val FIELD_RADIUS_KM = 20.0
private const val EARTH_RADIUS_KM = 6_371.0088
private const val MINIMUM_CONTRIBUTORS = 3
private const val MAX_FRAMES = 24
private const val EPSILON = 1e-9
private val GRID_OFFSETS_KM = doubleArrayOf(-14.0, -7.0, 0.0, 7.0, 14.0)
private val GRID_ORDER = (0 until GRID_SIZE).flatMap { row ->
    (0 until GRID_SIZE).map { column -> row to column }
}
