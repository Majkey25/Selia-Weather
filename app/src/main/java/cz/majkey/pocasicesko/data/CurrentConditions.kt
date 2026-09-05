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
    val temperature: Double?,
    val humidity: Int?,
    val precipitation: Double?,
    val windSpeed: Double?,
    val windDirection: Double?,
    val sunshineSeconds: Double?,
    val dewPoint: Double? = null,
    val pressureHpa: Double? = null,
    val visibilityMeters: Double? = null,
    val cloudCoverPercent: Int? = null,
) {
    init {
        require(stationId.isNotBlank())
        require(latitude.isFinite() && latitude in -90.0..90.0)
        require(longitude.isFinite() && longitude in -180.0..180.0)
        require(temperature == null || temperature.isFinite() && temperature in -100.0..70.0)
        require(humidity == null || humidity in 0..100)
        require(precipitation == null || precipitation.isFinite() && precipitation >= 0)
        require(windSpeed == null || windSpeed.isFinite() && windSpeed >= 0)
        require(windDirection == null || windDirection.isFinite() && windDirection in 0.0..360.0)
        require(sunshineSeconds == null || sunshineSeconds.isFinite() && sunshineSeconds in 0.0..600.0)
        require(dewPoint == null || dewPoint.isFinite() && dewPoint in -120.0..70.0)
        require(pressureHpa == null || pressureHpa.isFinite() && pressureHpa > 0)
        require(visibilityMeters == null || visibilityMeters.isFinite() && visibilityMeters >= 0)
        require(cloudCoverPercent == null || cloudCoverPercent in 0..100)
        require(
            listOf(
                temperature,
                humidity,
                precipitation,
                windSpeed,
                windDirection,
                sunshineSeconds,
                dewPoint,
                pressureHpa,
                visibilityMeters,
                cloudCoverPercent,
            ).any { it != null },
        )
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
    if (nearby.isEmpty()) return model

    fun weighted(value: (CurrentStationObservation) -> Double?): Double? {
        val pairs = nearby.mapNotNull { (observation, distance) ->
            value(observation)?.let { observed -> observed to stationWeight(distance) }
        }.take(MAX_STATION_COUNT)
        if (pairs.isEmpty()) return null
        return pairs.sumOf { (observed, weight) -> observed * weight } / pairs.sumOf { it.second }
    }

    val temperature = weighted(CurrentStationObservation::temperature)
    val humidity = weighted { it.humidity?.toDouble() }?.roundToInt()?.coerceIn(0, 100)
    val precipitation = weighted(CurrentStationObservation::precipitation)
    val dewPoint = weighted(CurrentStationObservation::dewPoint)
    val pressure = weighted(CurrentStationObservation::pressureHpa)
    val visibility = weighted(CurrentStationObservation::visibilityMeters)
    val reportedCloudCover = weighted { it.cloudCoverPercent?.toDouble() }
        ?.roundToInt()
        ?.coerceIn(0, 100)
    val sunshineFraction = weighted(CurrentStationObservation::sunshineSeconds)?.div(600.0)
    val wind = nearby.mapNotNull { (observation, distance) ->
        val speed = observation.windSpeed ?: return@mapNotNull null
        val direction = observation.windDirection ?: return@mapNotNull null
        Triple(speed, Math.toRadians(direction), stationWeight(distance))
    }.take(MAX_STATION_COUNT)
    val windVector = wind.takeIf { it.isNotEmpty() }?.let { values ->
        val totalWeight = values.sumOf { it.third }
        val east = values.sumOf { (speed, angle, weight) -> speed * sin(angle) * weight } / totalWeight
        val north = values.sumOf { (speed, angle, weight) -> speed * cos(angle) * weight } / totalWeight
        hypot(east, north) to (Math.toDegrees(kotlin.math.atan2(east, north)) + 360.0) % 360.0
    }
    val (weatherCode, cloudCover) = when {
        precipitation != null && precipitation >= RAIN_THRESHOLD_MM -> 61 to 100
        model.isDay && sunshineFraction != null && sunshineFraction >= CLEAR_SUNSHINE_FRACTION -> 0 to 5
        model.isDay && sunshineFraction != null && sunshineFraction >= MOSTLY_CLEAR_SUNSHINE_FRACTION -> 1 to 25
        reportedCloudCover != null && model.weatherCode in 0..3 -> {
            cloudWeatherCode(reportedCloudCover) to reportedCloudCover
        }
        else -> model.weatherCode to (reportedCloudCover ?: model.cloudCover)
    }
    val observedLayerCover = cloudCover.takeIf {
        precipitation != null && precipitation < RAIN_THRESHOLD_MM && sunshineFraction != null &&
            sunshineFraction >= MOSTLY_CLEAR_SUNSHINE_FRACTION
    }
    val temperatureDelta = temperature?.minus(model.temperature)
    return model.copy(
        temperature = temperature ?: model.temperature,
        feelsLike = temperatureDelta?.let(model.feelsLike::plus) ?: model.feelsLike,
        humidity = humidity ?: model.humidity,
        precipitation = precipitation ?: model.precipitation,
        rain = precipitation ?: model.rain,
        weatherCode = weatherCode,
        cloudCover = cloudCover,
        cloudCoverLow = observedLayerCover ?: model.cloudCoverLow,
        cloudCoverMid = observedLayerCover ?: model.cloudCoverMid,
        cloudCoverHigh = observedLayerCover ?: model.cloudCoverHigh,
        windSpeed = windVector?.first ?: model.windSpeed,
        windDirection = windVector?.second?.roundToInt() ?: model.windDirection,
        dewPoint = dewPoint ?: model.dewPoint,
        pressure = pressure ?: model.pressure,
        visibilityMeters = visibility ?: model.visibilityMeters,
    )
}

internal fun applyCurrentConditionsToForecastJson(json: String, current: CurrentWeather): String {
    val root = JSONObject(json)
    val currentJson = root.getJSONObject("current")
    val values = mapOf(
        "temperature_2m" to current.temperature,
        "apparent_temperature" to current.feelsLike,
        "relative_humidity_2m" to current.humidity,
        "dew_point_2m" to current.dewPoint,
        "precipitation" to current.precipitation,
        "rain" to current.rain,
        "weather_code" to current.weatherCode,
        "cloud_cover" to current.cloudCover,
        "cloud_cover_low" to current.cloudCoverLow,
        "cloud_cover_mid" to current.cloudCoverMid,
        "cloud_cover_high" to current.cloudCoverHigh,
        "pressure_msl" to current.pressure,
        "visibility" to current.visibilityMeters,
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
        // Station totals cover ten minutes, not the forecast hour.
        if (name == "precipitation" || name == "rain") return@forEach
        val array = hourly.optJSONArray(name)
        if (value != null && array != null && index < array.length()) array.put(index, value)
    }
    return root.toString()
}

private fun cloudWeatherCode(cloudCover: Int): Int = when {
    cloudCover <= 20 -> 0
    cloudCover <= 50 -> 1
    cloudCover <= 80 -> 2
    else -> 3
}

private fun stationWeight(distanceKm: Double): Double =
    1.0 / max(distanceKm, 1.0).let { it * it }

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
