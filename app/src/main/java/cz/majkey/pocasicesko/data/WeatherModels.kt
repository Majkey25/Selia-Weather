package cz.majkey.pocasicesko.data

data class CzechLocation(
    val name: String,
    val region: String,
    val latitude: Double,
    val longitude: Double,
)

data class CurrentWeather(
    val time: String,
    val temperature: Double,
    val feelsLike: Double,
    val humidity: Int,
    val precipitation: Double,
    val weatherCode: Int,
    val cloudCover: Int,
    val pressure: Double,
    val windSpeed: Double,
    val windDirection: Int,
    val windGusts: Double,
    val isDay: Boolean,
    val dewPoint: Double? = null,
    val wetBulbTemperature: Double? = null,
    val rain: Double? = null,
    val snowfall: Double? = null,
    val snowDepthWaterEquivalent: Double? = null,
    val cloudCoverLow: Int? = null,
    val cloudCoverMid: Int? = null,
    val cloudCoverHigh: Int? = null,
    val visibilityMeters: Double? = null,
    val surfacePressure: Double? = null,
    val cape: Double? = null,
    val vapourPressureDeficit: Double? = null,
    val surfaceTemperature: Double? = null,
)

data class HourlyWeather(
    val time: String,
    val temperature: Double,
    val humidity: Int,
    val precipitationProbability: Int,
    val precipitation: Double,
    val weatherCode: Int,
    val pressure: Double,
    val windSpeed: Double,
    val windDirection: Int,
    val isDay: Boolean,
    val apparentTemperature: Double? = null,
    val dewPoint: Double? = null,
    val wetBulbTemperature: Double? = null,
    val rain: Double? = null,
    val snowfall: Double? = null,
    val snowDepthWaterEquivalent: Double? = null,
    val cloudCoverLow: Int? = null,
    val cloudCoverMid: Int? = null,
    val cloudCoverHigh: Int? = null,
    val visibilityMeters: Double? = null,
    val surfacePressure: Double? = null,
    val windGusts: Double? = null,
    val cape: Double? = null,
    val vapourPressureDeficit: Double? = null,
    val surfaceTemperature: Double? = null,
    val et0: Double? = null,
)

data class DailyWeather(
    val date: String,
    val weatherCode: Int,
    val temperatureMax: Double,
    val temperatureMin: Double,
    val sunrise: String,
    val sunset: String,
    val precipitationSum: Double,
    val precipitationProbability: Int,
    val windSpeedMax: Double,
    val apparentTemperatureMax: Double? = null,
    val apparentTemperatureMin: Double? = null,
    val daylightDurationSeconds: Double? = null,
    val sunshineDurationSeconds: Double? = null,
    val rainSum: Double? = null,
    val snowfallSum: Double? = null,
    val precipitationHours: Double? = null,
    val windGustsMax: Double? = null,
    val dominantWindDirection: Int? = null,
    val shortwaveRadiationSum: Double? = null,
    val et0: Double? = null,
)

data class WeatherSnapshot(
    val timezone: String,
    val current: CurrentWeather,
    val hourly: List<HourlyWeather>,
    val daily: List<DailyWeather>,
    val updatedAtEpochMillis: Long,
)

internal fun WeatherSnapshot.currentDay(): DailyWeather =
    daily.firstOrNull { it.date == current.time.take(10) } ?: daily.first()

enum class WeatherKind {
    CLEAR,
    PARTLY_CLOUDY,
    CLOUDY,
    FOG,
    RAIN,
    STORM,
    SNOW,
    UNKNOWN,
}

enum class WeatherConditionKey {
    CLEAR_DAY,
    CLEAR_NIGHT,
    PARTLY_CLOUDY,
    CLOUDY,
    FOG,
    DRIZZLE,
    RAIN,
    SNOW,
    SHOWERS,
    SNOW_SHOWERS,
    STORM,
    UNKNOWN,
}

data class WeatherCondition(
    val key: WeatherConditionKey,
    val kind: WeatherKind,
)

fun conditionFor(code: Int, isDay: Boolean = true): WeatherCondition = when (code) {
    0 -> WeatherCondition(if (isDay) WeatherConditionKey.CLEAR_DAY else WeatherConditionKey.CLEAR_NIGHT, WeatherKind.CLEAR)
    1, 2 -> WeatherCondition(WeatherConditionKey.PARTLY_CLOUDY, WeatherKind.PARTLY_CLOUDY)
    3 -> WeatherCondition(WeatherConditionKey.CLOUDY, WeatherKind.CLOUDY)
    45, 48 -> WeatherCondition(WeatherConditionKey.FOG, WeatherKind.FOG)
    in 51..57 -> WeatherCondition(WeatherConditionKey.DRIZZLE, WeatherKind.RAIN)
    in 61..67 -> WeatherCondition(WeatherConditionKey.RAIN, WeatherKind.RAIN)
    in 71..77 -> WeatherCondition(WeatherConditionKey.SNOW, WeatherKind.SNOW)
    in 80..82 -> WeatherCondition(WeatherConditionKey.SHOWERS, WeatherKind.RAIN)
    85, 86 -> WeatherCondition(WeatherConditionKey.SNOW_SHOWERS, WeatherKind.SNOW)
    in 95..99 -> WeatherCondition(WeatherConditionKey.STORM, WeatherKind.STORM)
    else -> WeatherCondition(WeatherConditionKey.UNKNOWN, WeatherKind.UNKNOWN)
}
