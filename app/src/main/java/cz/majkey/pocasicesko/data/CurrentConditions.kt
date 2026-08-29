package cz.majkey.pocasicesko.data

import java.time.Duration
import java.time.Instant
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import org.json.JSONObject

internal data class CurrentStationObservation(
    val stationId: String,
    val latitude: Double,
    val longitude: Double,
    val time: Instant,
    val temperature: Double,
    val humidity: Int,
    val precipitation: Double,
    val windSpeed: Double?,
    val windDirection: Double?,
    val sunshineSeconds: Double?,
) {
    init {
        require(stationId.isNotBlank())
        require(latitude.isFinite() && latitude in -90.0..90.0)
        require(longitude.isFinite() && longitude in -180.0..180.0)
        require(temperature.isFinite())
        require(humidity in 0..100)
        require(precipitation.isFinite() && precipitation >= 0)
        require(windSpeed == null || windSpeed.isFinite() && windSpeed >= 0)
        require(windDirection == null || windDirection.isFinite() && windDirection in 0.0..360.0)
        require(sunshineSeconds == null || sunshineSeconds.isFinite() && sunshineSeconds in 0.0..600.0)
    }
}

internal fun fuseCurrentConditions(
    model: CurrentWeather,
    location: CzechLocation,
    observations: List<CurrentStationObservation>,
    now: Instant,
): CurrentWeather {
    val nearby = observations
        .map { observation -> observation to distanceKm(location, observation) }
        .filter { (observation, distance) ->
            val ageSeconds = Duration.between(observation.time, now).seconds
            distance <= MAX_STATION_DISTANCE_KM && ageSeconds in -MAX_CLOCK_SKEW_SECONDS..MAX_OBSERVATION_AGE_SECONDS
        }
        .sortedBy { (_, distance) -> distance }
        .take(MAX_STATION_COUNT)
    if (nearby.isEmpty()) return model

    val weights = nearby.map { (_, distance) -> 1.0 / max(distance, 1.0).let { it * it } }
    fun weighted(values: List<Double>): Double {
        val pairs = values.zip(weights)
        return pairs.sumOf { (value, weight) -> value * weight } / pairs.sumOf { it.second }
    }

    val temperature = weighted(nearby.map { it.first.temperature })
    val humidity = weighted(nearby.map { it.first.humidity.toDouble() }).roundToInt().coerceIn(0, 100)
    val precipitation = weighted(nearby.map { it.first.precipitation })
    val sunshine = nearby.mapIndexedNotNull { index, (observation, _) ->
        observation.sunshineSeconds?.let { value -> value to weights[index] }
    }
    val sunshineFraction = sunshine.takeIf { it.isNotEmpty() }?.let { values ->
        values.sumOf { (value, weight) -> value * weight } / values.sumOf { it.second } / 600.0
    }
    val wind = nearby.mapIndexedNotNull { index, (observation, _) ->
        val speed = observation.windSpeed ?: return@mapIndexedNotNull null
        val direction = observation.windDirection ?: return@mapIndexedNotNull null
        Triple(speed, Math.toRadians(direction), weights[index])
    }
    val windVector = wind.takeIf { it.isNotEmpty() }?.let { values ->
        val totalWeight = values.sumOf { it.third }
        val east = values.sumOf { (speed, angle, weight) -> speed * sin(angle) * weight } / totalWeight
        val north = values.sumOf { (speed, angle, weight) -> speed * cos(angle) * weight } / totalWeight
        hypot(east, north) to (Math.toDegrees(kotlin.math.atan2(east, north)) + 360.0) % 360.0
    }
    val (weatherCode, cloudCover) = when {
        precipitation >= RAIN_THRESHOLD_MM -> 61 to 100
        model.isDay && sunshineFraction != null && sunshineFraction >= CLEAR_SUNSHINE_FRACTION -> 0 to 5
        model.isDay && sunshineFraction != null && sunshineFraction >= MOSTLY_CLEAR_SUNSHINE_FRACTION -> 1 to 25
        else -> model.weatherCode to model.cloudCover
    }
    val observedCloudCover = cloudCover.takeIf {
        precipitation < RAIN_THRESHOLD_MM && sunshineFraction != null &&
            sunshineFraction >= MOSTLY_CLEAR_SUNSHINE_FRACTION
    }
    val temperatureDelta = temperature - model.temperature
    return model.copy(
        temperature = temperature,
        feelsLike = model.feelsLike + temperatureDelta,
        humidity = humidity,
        precipitation = precipitation,
        rain = precipitation,
        weatherCode = weatherCode,
        cloudCover = cloudCover,
        cloudCoverLow = observedCloudCover ?: model.cloudCoverLow,
        cloudCoverMid = observedCloudCover ?: model.cloudCoverMid,
        cloudCoverHigh = observedCloudCover ?: model.cloudCoverHigh,
        windSpeed = windVector?.first ?: model.windSpeed,
        windDirection = windVector?.second?.roundToInt() ?: model.windDirection,
    )
}

internal fun applyCurrentConditionsToForecastJson(json: String, current: CurrentWeather): String {
    val root = JSONObject(json)
    val currentJson = root.getJSONObject("current")
    val values = mapOf(
        "temperature_2m" to current.temperature,
        "apparent_temperature" to current.feelsLike,
        "relative_humidity_2m" to current.humidity,
        "precipitation" to current.precipitation,
        "rain" to current.rain,
        "weather_code" to current.weatherCode,
        "cloud_cover" to current.cloudCover,
        "cloud_cover_low" to current.cloudCoverLow,
        "cloud_cover_mid" to current.cloudCoverMid,
        "cloud_cover_high" to current.cloudCoverHigh,
        "wind_speed_10m" to current.windSpeed,
        "wind_direction_10m" to current.windDirection,
    )
    values.forEach { (name, value) -> if (value != null) currentJson.put(name, value) }

    val hourly = root.optJSONObject("hourly") ?: return root.toString()
    val times = hourly.optJSONArray("time") ?: return root.toString()
    val currentHour = current.time.take(13)
    val index = (0 until times.length()).firstOrNull { times.optString(it).take(13) == currentHour }
        ?: return root.toString()
    values.forEach { (name, value) ->
        val array = hourly.optJSONArray(name)
        if (value != null && array != null && index < array.length()) array.put(index, value)
    }
    return root.toString()
}

private fun distanceKm(location: CzechLocation, observation: CurrentStationObservation): Double {
    val latitudeDelta = Math.toRadians(observation.latitude - location.latitude)
    val longitudeDelta = Math.toRadians(observation.longitude - location.longitude)
    val value = sin(latitudeDelta / 2) * sin(latitudeDelta / 2) +
        cos(Math.toRadians(location.latitude)) * cos(Math.toRadians(observation.latitude)) *
        sin(longitudeDelta / 2) * sin(longitudeDelta / 2)
    return EARTH_RADIUS_KM * 2 * asin(sqrt(value))
}

private const val MAX_STATION_COUNT = 3
private const val MAX_STATION_DISTANCE_KM = 50.0
private const val MAX_OBSERVATION_AGE_SECONDS = 90 * 60L
private const val MAX_CLOCK_SKEW_SECONDS = 5 * 60L
private const val RAIN_THRESHOLD_MM = 0.1
private const val CLEAR_SUNSHINE_FRACTION = 0.8
private const val MOSTLY_CLEAR_SUNSHINE_FRACTION = 0.4
private const val EARTH_RADIUS_KM = 6_371.0088
