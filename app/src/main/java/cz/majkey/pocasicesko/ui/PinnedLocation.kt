package cz.majkey.pocasicesko.ui

import cz.majkey.pocasicesko.data.CzechLocation
import cz.majkey.pocasicesko.data.REGION_CZECHIA
import cz.majkey.pocasicesko.data.REGION_WORLD

internal data class MapCoordinates(val latitude: Double, val longitude: Double)

internal fun mapCoordinatesOrNull(latitude: String, longitude: String): MapCoordinates? {
    val lat = latitude.replace(',', '.').toDoubleOrNull()
    val lon = longitude.replace(',', '.').toDoubleOrNull()
    if (lat?.isFinite() != true || lon?.isFinite() != true ||
        lat !in WORLD_LATITUDE || lon !in WORLD_LONGITUDE
    ) return null
    return MapCoordinates(lat, lon)
}

internal fun pinnedLocationOrNull(
    name: String,
    latitude: String,
    longitude: String,
): CzechLocation? {
    val normalizedName = name.trim()
    val coordinates = mapCoordinatesOrNull(latitude, longitude) ?: return null
    if (normalizedName.isEmpty() || normalizedName.length > MAX_LOCATION_NAME_LENGTH) {
        return null
    }
    val region = if (coordinates.latitude in CZECH_LATITUDE && coordinates.longitude in CZECH_LONGITUDE) {
        REGION_CZECHIA
    } else {
        REGION_WORLD
    }
    return CzechLocation(normalizedName, region, coordinates.latitude, coordinates.longitude)
}

private const val MAX_LOCATION_NAME_LENGTH = 60
private val WORLD_LATITUDE = -90.0..90.0
private val WORLD_LONGITUDE = -180.0..180.0
private val CZECH_LATITUDE = 48.45..51.2
private val CZECH_LONGITUDE = 11.9..19.0
