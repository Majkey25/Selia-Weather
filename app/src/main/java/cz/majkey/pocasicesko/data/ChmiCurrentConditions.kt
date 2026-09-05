package cz.majkey.pocasicesko.data

import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject

internal data class CurrentStation(
    val stationId: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val elevation: Double,
    val sunshine: Boolean,
) {
    init {
        require(stationId.matches(STATION_ID_PATTERN) && name.isNotBlank())
        require(latitude.isFinite() && latitude in -90.0..90.0)
        require(longitude.isFinite() && longitude in -180.0..180.0)
        require(elevation.isFinite())
    }
}

internal fun decodeCurrentStationCatalog(json: String): List<CurrentStation> {
    val root = JSONObject(json)
    require(root.getInt("schema_version") == 1)
    val stations = root.getJSONArray("stations")
    return List(stations.length()) { index ->
        val value = stations.getJSONObject(index)
        CurrentStation(
            stationId = value.getString("station_id"),
            name = value.getString("name"),
            latitude = value.getDouble("latitude"),
            longitude = value.getDouble("longitude"),
            elevation = value.getDouble("elevation_m"),
            sunshine = value.getBoolean("sunshine"),
        )
    }.also { decoded ->
        require(decoded.isNotEmpty())
        require(decoded.map { it.stationId }.toSet().size == decoded.size)
    }
}

internal fun nearestCurrentStations(
    location: CzechLocation,
    stations: List<CurrentStation>,
    count: Int,
): List<CurrentStation> {
    require(count > 0)
    val ranked = stations.sortedWith(
        compareBy<CurrentStation>(
            { station -> distanceKm(location.latitude, location.longitude, station.latitude, station.longitude) },
            CurrentStation::stationId,
        ),
    )
    val selected = ranked.take(count).toMutableList()
    if (selected.isNotEmpty() && selected.none(CurrentStation::sunshine)) {
        ranked.drop(count).firstOrNull(CurrentStation::sunshine)?.let { sunshine ->
            selected[selected.lastIndex] = sunshine
        }
    }
    return selected
}

internal fun parseCurrentStationObservation(
    json: String,
    station: CurrentStation,
): CurrentStationObservation? {
    val table = JSONObject(json).getJSONObject("data").getJSONObject("data")
    val fields = table.getString("header").split(',')
    val stationIndex = fields.indexOf("STATION")
    val elementIndex = fields.indexOf("ELEMENT")
    val timeIndex = fields.indexOf("DT")
    val valueIndex = fields.indexOf("VAL")
    require(minOf(stationIndex, elementIndex, timeIndex, valueIndex) >= 0)
    val valuesByTime = mutableMapOf<Instant, MutableMap<String, Double>>()
    val rows = table.getJSONArray("values")
    for (index in 0 until rows.length()) {
        val row = rows.getJSONArray(index)
        if (row.getString(stationIndex) != station.stationId) continue
        val element = row.getString(elementIndex)
        if (element !in CURRENT_ELEMENTS) continue
        val value = row.numberOrNull(valueIndex) ?: continue
        val time = Instant.parse(row.getString(timeIndex))
        valuesByTime.getOrPut(time, ::mutableMapOf)[element] = value
    }
    val complete = valuesByTime.entries
        .sortedByDescending(Map.Entry<Instant, *>::key)
        .filter { (_, values) -> REQUIRED_CURRENT_ELEMENTS.all(values::containsKey) }
    val latest = complete.firstOrNull() ?: return null
    val values = latest.value
    val sunshine = complete.asSequence()
        .takeWhile { it.key.isAfter(latest.key.minusSeconds(SUNSHINE_SAMPLE_COUNT * 600L)) }
        .mapNotNull { it.value["SSV10M"] }
        .take(SUNSHINE_SAMPLE_COUNT)
        .toList()
        .takeIf(List<Double>::isNotEmpty)
        ?.average()
    return CurrentStationObservation(
        stationId = station.stationId,
        latitude = station.latitude,
        longitude = station.longitude,
        time = latest.key,
        temperature = requireNotNull(values["T"]),
        humidity = requireNotNull(values["H"]).toInt().coerceIn(0, 100),
        precipitation = requireNotNull(values["SRA10M"]),
        windSpeed = values["F"]?.times(3.6),
        windDirection = values["D"],
        sunshineSeconds = sunshine,
    )
}

private fun JSONArray.numberOrNull(index: Int): Double? = when (val value = opt(index)) {
    is Number -> value.toDouble()
    is String -> value.toDoubleOrNull()
    else -> null
}

private fun distanceKm(
    latitudeA: Double,
    longitudeA: Double,
    latitudeB: Double,
    longitudeB: Double,
): Double {
    val latitudeDelta = Math.toRadians(latitudeB - latitudeA)
    val longitudeDelta = Math.toRadians(longitudeB - longitudeA)
    val value = kotlin.math.sin(latitudeDelta / 2).let { it * it } +
        kotlin.math.cos(Math.toRadians(latitudeA)) * kotlin.math.cos(Math.toRadians(latitudeB)) *
        kotlin.math.sin(longitudeDelta / 2).let { it * it }
    return 6_371.0088 * 2 * kotlin.math.asin(kotlin.math.sqrt(value))
}

private val REQUIRED_CURRENT_ELEMENTS = setOf("T", "H", "SRA10M")
private val CURRENT_ELEMENTS = REQUIRED_CURRENT_ELEMENTS + setOf("F", "D", "SSV10M")
private val STATION_ID_PATTERN = Regex("[0-9-]+")
private const val SUNSHINE_SAMPLE_COUNT = 6
