package cz.majkey.pocasicesko.data

import java.time.Instant
import kotlin.math.exp
import kotlin.math.roundToInt
import org.json.JSONArray
import org.json.JSONObject

internal fun parseMetarCurrentConditions(json: String): List<CurrentStationObservation> {
    val values = JSONArray(json)
    return List(values.length()) { index -> values.getJSONObject(index) }
        .mapNotNull(::parseMetarObservation)
        .groupBy(CurrentStationObservation::stationId)
        .mapNotNull { (_, observations) -> observations.maxByOrNull(CurrentStationObservation::time) }
        .sortedBy(CurrentStationObservation::stationId)
}

private fun parseMetarObservation(value: JSONObject): CurrentStationObservation? {
    val stationId = value.optString("icaoId")
    if (!METAR_STATION_ID.matches(stationId)) return null
    val latitude = value.numberOrNull("lat") ?: return null
    val longitude = value.numberOrNull("lon") ?: return null
    if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return null
    val obsTime = value.numberOrNull("obsTime")?.toLong() ?: return null
    if (obsTime <= 0) return null
    val quality = value.numberOrNull("qcField") ?: return null
    if (!quality.isFinite() || quality < 0) return null
    val temperature = value.numberOrNull("temp")
    val dewPoint = value.numberOrNull("dewp")
    if (temperature != null && temperature !in MIN_TEMPERATURE_C..MAX_TEMPERATURE_C) return null
    if (dewPoint != null && dewPoint !in MIN_DEW_POINT_C..MAX_TEMPERATURE_C) return null
    val humidity = if (temperature != null && dewPoint != null) {
        relativeHumidity(temperature, dewPoint)
    } else {
        null
    }
    val windSpeed = value.numberOrNull("wspd")?.times(KNOTS_TO_KILOMETRES_PER_HOUR)
    val windDirection = value.numberOrNull("wdir")?.takeIf { it in 0.0..360.0 }
    val visibility = value.textNumberOrNull("visib")?.times(STATUTE_MILES_TO_METRES)
    // AWC distinguishes sea-level pressure from the altimeter setting (altim/QNH).
    val pressure = value.numberOrNull("slp")
    val cloudCover = CLOUD_COVER[value.optString("cover")]
    return try {
        CurrentStationObservation(
            stationId = stationId,
            latitude = latitude,
            longitude = longitude,
            time = Instant.ofEpochSecond(obsTime),
            temperature = temperature,
            humidity = humidity,
            precipitation = null,
            windSpeed = windSpeed,
            windDirection = windDirection,
            sunshineSeconds = null,
            dewPoint = dewPoint,
            pressureHpa = pressure,
            visibilityMeters = visibility,
            cloudCoverPercent = cloudCover,
            weatherCode = parseMetarWeatherCode(value.opt("wxString") as? String),
        )
    } catch (_: IllegalArgumentException) {
        null
    }
}

private fun parseMetarWeatherCode(encoded: String?): Int? {
    if (encoded.isNullOrBlank() || encoded.length > 64) return null
    val groups = encoded.trim().split(Regex("\\s+"))
    if (groups.size > 3) return null
    // Recognized vicinity/recent groups do not erase separately reported local weather.
    // Unknown groups still fail closed; never find RA inside mixed RASN/FZRA or free text.
    if (groups.any {
        it !in METAR_WEATHER_CODES && it !in METAR_NONLOCAL_WEATHER_GROUPS && it !in setOf("BR", "HZ")
    }) return null
    val codes = groups.mapNotNull(METAR_WEATHER_CODES::get)
    val phases = listOf(
        codes.any { it in 51..55 || it in 61..65 || it in 80..82 },
        codes.any { it in 56..57 || it in 66..67 },
        codes.any { it in 71..77 || it in 85..86 },
    )
    if (phases.count { it } > 1) return null
    return codes.maxOrNull()
}

private fun relativeHumidity(temperature: Double, dewPoint: Double): Int {
    val numerator = exp(MAGNUS_A * dewPoint / (MAGNUS_B + dewPoint))
    val denominator = exp(MAGNUS_A * temperature / (MAGNUS_B + temperature))
    return (100.0 * numerator / denominator).roundToInt().coerceIn(0, 100)
}

private fun JSONObject.numberOrNull(name: String): Double? = when (val value = opt(name)) {
    is Number -> value.toDouble().takeIf(Double::isFinite)
    else -> null
}

private fun JSONObject.textNumberOrNull(name: String): Double? = when (val value = opt(name)) {
    is Number -> value.toDouble().takeIf(Double::isFinite)
    is String -> value.removeSuffix("+").toDoubleOrNull()?.takeIf(Double::isFinite)
    else -> null
}

private val METAR_STATION_ID = Regex("[A-Z0-9]{4}")
private val CLOUD_COVER = mapOf(
    "SKC" to 0,
    "FEW" to 13,
    "SCT" to 38,
    "BKN" to 75,
    "OVC" to 100,
    "VV" to 100,
)
// FAA METAR present-weather codes; WMO intensity classes. No amount or probability inference.
private val METAR_WEATHER_CODES = mapOf(
    "-DZ" to 51, "DZ" to 53, "+DZ" to 55,
    "-RA" to 61, "RA" to 63, "+RA" to 65,
    "-SHRA" to 80, "SHRA" to 81, "+SHRA" to 81,
    "-FZDZ" to 56, "FZDZ" to 57, "+FZDZ" to 57,
    "-FZRA" to 66, "FZRA" to 67, "+FZRA" to 67,
    "-SN" to 71, "SN" to 73, "+SN" to 75,
    "-SHSN" to 85, "SHSN" to 86, "+SHSN" to 86,
    "FG" to 45, "FZFG" to 48,
    "TS" to 95, "-TSRA" to 95, "TSRA" to 95, "+TSRA" to 95,
)
// Exact tokens only: FAA JO 7900.5E section 13.13 / Appendix E (VC),
// EU 2017/373 Annex V Appendix 1 METAR template (RE). These never supply local weather.
private val METAR_NONLOCAL_WEATHER_GROUPS = setOf(
    "VCSH", "VCTS", "VCFG", "VCBLSN", "VCBLSA", "VCBLDU", "VCPO", "VCSS", "VCDS",
    "REDZ", "RERA", "RESN", "REFZDZ", "REFZRA", "RESHRA", "RESHSN", "RETS", "RETSRA",
)
private const val KNOTS_TO_KILOMETRES_PER_HOUR = 1.852
private const val STATUTE_MILES_TO_METRES = 1_609.344
private const val MAGNUS_A = 17.625
private const val MAGNUS_B = 243.04
private const val MIN_TEMPERATURE_C = -100.0
private const val MIN_DEW_POINT_C = -120.0
private const val MAX_TEMPERATURE_C = 70.0
