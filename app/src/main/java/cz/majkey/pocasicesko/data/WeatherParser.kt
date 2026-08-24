package cz.majkey.pocasicesko.data

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

object WeatherParser {
    fun parseForecast(json: String, updatedAtEpochMillis: Long): WeatherSnapshot {
        val root = JSONObject(json)
        val currentJson = root.getJSONObject("current")
        val hourlyJson = root.getJSONObject("hourly")
        val dailyJson = root.getJSONObject("daily")

        val hourlyTimes = hourlyJson.getJSONArray("time")
        validateLengths(
            hourlyTimes,
            hourlyJson,
            "temperature_2m",
            "relative_humidity_2m",
            "precipitation_probability",
            "precipitation",
            "weather_code",
            "pressure_msl",
            "wind_speed_10m",
            "wind_direction_10m",
            "is_day",
        )

        val dailyTimes = dailyJson.getJSONArray("time")
        validateLengths(
            dailyTimes,
            dailyJson,
            "weather_code",
            "temperature_2m_max",
            "temperature_2m_min",
            "sunrise",
            "sunset",
            "precipitation_sum",
            "precipitation_probability_max",
            "wind_speed_10m_max",
        )

        if (hourlyTimes.length() == 0 || dailyTimes.length() == 0) {
            throw JSONException("Předpověď neobsahuje žádná data.")
        }

        return WeatherSnapshot(
            timezone = root.getString("timezone"),
            current = CurrentWeather(
                time = currentJson.getString("time"),
                temperature = currentJson.requiredDouble("temperature_2m"),
                feelsLike = currentJson.requiredDouble("apparent_temperature"),
                humidity = currentJson.requiredInt("relative_humidity_2m"),
                precipitation = currentJson.requiredDouble("precipitation"),
                weatherCode = currentJson.requiredInt("weather_code"),
                cloudCover = currentJson.requiredInt("cloud_cover"),
                pressure = currentJson.requiredDouble("pressure_msl"),
                windSpeed = currentJson.requiredDouble("wind_speed_10m"),
                windDirection = currentJson.requiredInt("wind_direction_10m"),
                windGusts = currentJson.requiredDouble("wind_gusts_10m"),
                isDay = currentJson.requiredInt("is_day") == 1,
            ),
            hourly = List(hourlyTimes.length()) { index ->
                HourlyWeather(
                    time = hourlyTimes.requiredString(index, "time"),
                    temperature = hourlyJson.getJSONArray("temperature_2m").requiredDouble(index, "temperature_2m"),
                    humidity = hourlyJson.getJSONArray("relative_humidity_2m").requiredInt(index, "relative_humidity_2m"),
                    precipitationProbability = hourlyJson.getJSONArray("precipitation_probability")
                        .requiredInt(index, "precipitation_probability"),
                    precipitation = hourlyJson.getJSONArray("precipitation").requiredDouble(index, "precipitation"),
                    weatherCode = hourlyJson.getJSONArray("weather_code").requiredInt(index, "weather_code"),
                    pressure = hourlyJson.getJSONArray("pressure_msl").requiredDouble(index, "pressure_msl"),
                    windSpeed = hourlyJson.getJSONArray("wind_speed_10m").requiredDouble(index, "wind_speed_10m"),
                    windDirection = hourlyJson.getJSONArray("wind_direction_10m")
                        .requiredInt(index, "wind_direction_10m"),
                    isDay = hourlyJson.getJSONArray("is_day").requiredInt(index, "is_day") == 1,
                )
            },
            daily = List(dailyTimes.length()) { index ->
                DailyWeather(
                    date = dailyTimes.requiredString(index, "time"),
                    weatherCode = dailyJson.getJSONArray("weather_code").requiredInt(index, "weather_code"),
                    temperatureMax = dailyJson.getJSONArray("temperature_2m_max")
                        .requiredDouble(index, "temperature_2m_max"),
                    temperatureMin = dailyJson.getJSONArray("temperature_2m_min")
                        .requiredDouble(index, "temperature_2m_min"),
                    sunrise = dailyJson.getJSONArray("sunrise").requiredString(index, "sunrise"),
                    sunset = dailyJson.getJSONArray("sunset").requiredString(index, "sunset"),
                    precipitationSum = dailyJson.getJSONArray("precipitation_sum")
                        .requiredDouble(index, "precipitation_sum"),
                    precipitationProbability = dailyJson.getJSONArray("precipitation_probability_max")
                        .requiredInt(index, "precipitation_probability_max"),
                    windSpeedMax = dailyJson.getJSONArray("wind_speed_10m_max")
                        .requiredDouble(index, "wind_speed_10m_max"),
                )
            },
            updatedAtEpochMillis = updatedAtEpochMillis,
        )
    }

    private fun validateLengths(reference: JSONArray, source: JSONObject, vararg names: String) {
        names.forEach { name ->
            val length = source.getJSONArray(name).length()
            if (length != reference.length()) {
                throw JSONException("Pole $name má délku $length místo ${reference.length()}.")
            }
        }
    }

    private fun JSONObject.requiredDouble(name: String): Double {
        if (isNull(name)) throw JSONException("Hodnota $name chybí.")
        return getDouble(name)
    }

    private fun JSONObject.requiredInt(name: String): Int {
        if (isNull(name)) throw JSONException("Hodnota $name chybí.")
        return getInt(name)
    }

    private fun JSONArray.requiredDouble(index: Int, name: String): Double {
        if (isNull(index)) throw JSONException("Hodnota $name[$index] chybí.")
        return getDouble(index)
    }

    private fun JSONArray.requiredInt(index: Int, name: String): Int {
        if (isNull(index)) throw JSONException("Hodnota $name[$index] chybí.")
        return getInt(index)
    }

    private fun JSONArray.requiredString(index: Int, name: String): String {
        if (isNull(index)) throw JSONException("Hodnota $name[$index] chybí.")
        return getString(index)
    }
}
