package cz.majkey.pocasicesko.ui

import cz.majkey.pocasicesko.data.CzechLocation
import cz.majkey.pocasicesko.data.REGION_CZECHIA
import cz.majkey.pocasicesko.data.REGION_WORLD

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

private const val MAX_LOCATION_NAME_LENGTH = 60
private val WORLD_LATITUDE = -90.0..90.0
private val WORLD_LONGITUDE = -180.0..180.0
private val CZECH_LATITUDE = 48.45..51.2
private val CZECH_LONGITUDE = 11.9..19.0
