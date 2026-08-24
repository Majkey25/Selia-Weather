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
)

data class WeatherSnapshot(
    val timezone: String,
    val current: CurrentWeather,
    val hourly: List<HourlyWeather>,
    val daily: List<DailyWeather>,
    val updatedAtEpochMillis: Long,
)

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

data class WeatherCondition(
    val label: String,
    val kind: WeatherKind,
)

fun conditionFor(code: Int, isDay: Boolean = true): WeatherCondition = when (code) {
    0 -> WeatherCondition(if (isDay) "Jasno" else "Jasná noc", WeatherKind.CLEAR)
    1, 2 -> WeatherCondition("Polojasno", WeatherKind.PARTLY_CLOUDY)
    3 -> WeatherCondition("Zataženo", WeatherKind.CLOUDY)
    45, 48 -> WeatherCondition("Mlha", WeatherKind.FOG)
    in 51..57 -> WeatherCondition("Mrholení", WeatherKind.RAIN)
    in 61..67 -> WeatherCondition("Déšť", WeatherKind.RAIN)
    in 71..77 -> WeatherCondition("Sněžení", WeatherKind.SNOW)
    in 80..82 -> WeatherCondition("Přeháňky", WeatherKind.RAIN)
    85, 86 -> WeatherCondition("Sněhové přeháňky", WeatherKind.SNOW)
    in 95..99 -> WeatherCondition("Bouřky", WeatherKind.STORM)
    else -> WeatherCondition("Neznámý stav", WeatherKind.UNKNOWN)
}
