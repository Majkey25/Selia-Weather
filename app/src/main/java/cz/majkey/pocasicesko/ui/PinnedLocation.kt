package cz.majkey.pocasicesko.ui

import cz.majkey.pocasicesko.data.CzechLocation
import cz.majkey.pocasicesko.data.REGION_CZECHIA
import cz.majkey.pocasicesko.data.REGION_WORLD
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.tan

internal data class MapCoordinates(val latitude: Double, val longitude: Double)

internal fun pinnedLocationOrNull(
    name: String,
    latitude: String,
    longitude: String,
): CzechLocation? {
    val normalizedName = name.trim()
    val parsedLatitude = latitude.replace(',', '.').toDoubleOrNull()
    val parsedLongitude = longitude.replace(',', '.').toDoubleOrNull()
    if (normalizedName.isEmpty() || normalizedName.length > MAX_LOCATION_NAME_LENGTH ||
        parsedLatitude?.isFinite() != true || parsedLongitude?.isFinite() != true ||
        parsedLatitude !in WORLD_LATITUDE || parsedLongitude !in WORLD_LONGITUDE
    ) {
        return null
    }
    val region = if (parsedLatitude in CZECH_LATITUDE && parsedLongitude in CZECH_LONGITUDE) {
        REGION_CZECHIA
    } else {
        REGION_WORLD
    }
    return CzechLocation(normalizedName, region, parsedLatitude, parsedLongitude)
}

internal fun coordinatesFromMapPosition(x: Double, y: Double): MapCoordinates? {
    if (!x.isFinite() || !y.isFinite() || x !in 0.0..1.0 || y !in 0.0..1.0) return null
    val north = mercator(MAP_NORTH)
    val south = mercator(MAP_SOUTH)
    val latitude = inverseMercator(north - y * (north - south))
    return MapCoordinates(latitude, MAP_WEST + x * (MAP_EAST - MAP_WEST))
}

internal fun imagePositionToMapFractions(
    x: Double,
    y: Double,
    viewWidth: Double,
    viewHeight: Double,
    imageWidth: Int,
    imageHeight: Int,
): Pair<Double, Double>? {
    if (!listOf(x, y, viewWidth, viewHeight).all(Double::isFinite) ||
        viewWidth <= 0 || viewHeight <= 0 || imageWidth <= 0 || imageHeight <= 0
    ) {
        return null
    }
    val scale = min(viewWidth / imageWidth, viewHeight / imageHeight)
    val width = imageWidth * scale
    val height = imageHeight * scale
    val left = (viewWidth - width) / 2
    val top = (viewHeight - height) / 2
    if (x !in left..left + width || y !in top..top + height) return null
    return (x - left) / width to (y - top) / height
}

private fun mercator(latitude: Double): Double = ln(tan(PI / 4 + Math.toRadians(latitude) / 2))

private fun inverseMercator(value: Double): Double = Math.toDegrees(2 * atan(exp(value)) - PI / 2)

private const val MAX_LOCATION_NAME_LENGTH = 60
private val WORLD_LATITUDE = -90.0..90.0
private val WORLD_LONGITUDE = -180.0..180.0
private val CZECH_LATITUDE = 48.45..51.2
private val CZECH_LONGITUDE = 11.9..19.0
private const val MAP_WEST = 11.267
private const val MAP_EAST = 20.770
private const val MAP_SOUTH = 48.047
private const val MAP_NORTH = 52.167
