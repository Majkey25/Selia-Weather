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
        validateLengths(hourlyTimes, hourlyJson, *HOURLY_FIELDS)

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

        val hourly = buildList {
            for (index in 0 until hourlyTimes.length()) {
                if (hourlyTimes.isNull(index) || HOURLY_FIELDS.any { hourlyJson.getJSONArray(it).isNull(index) }) {
                    continue
                }
                add(
                    HourlyWeather(
                        time = hourlyTimes.requiredString(index, "time"),
                        temperature = hourlyJson.getJSONArray("temperature_2m")
                            .requiredDouble(index, "temperature_2m"),
                        humidity = hourlyJson.getJSONArray("relative_humidity_2m")
                            .requiredInt(index, "relative_humidity_2m"),
                        precipitationProbability = hourlyJson.getJSONArray("precipitation_probability")
                            .requiredInt(index, "precipitation_probability"),
                        precipitation = hourlyJson.getJSONArray("precipitation")
                            .requiredDouble(index, "precipitation"),
                        weatherCode = hourlyJson.getJSONArray("weather_code")
                            .requiredInt(index, "weather_code"),
                        pressure = hourlyJson.getJSONArray("pressure_msl")
                            .requiredDouble(index, "pressure_msl"),
                        windSpeed = hourlyJson.getJSONArray("wind_speed_10m")
                            .requiredDouble(index, "wind_speed_10m"),
                        windDirection = hourlyJson.getJSONArray("wind_direction_10m")
                            .requiredInt(index, "wind_direction_10m"),
                        isDay = hourlyJson.getJSONArray("is_day").requiredInt(index, "is_day") == 1,
                        apparentTemperature = hourlyJson.optionalDoubleAt("apparent_temperature", index),
                        dewPoint = hourlyJson.optionalDoubleAt("dew_point_2m", index),
                        wetBulbTemperature = hourlyJson.optionalDoubleAt("wet_bulb_temperature_2m", index),
                        rain = hourlyJson.optionalDoubleAt("rain", index),
                        snowfall = hourlyJson.optionalDoubleAt("snowfall", index),
                        snowDepthWaterEquivalent = hourlyJson.optionalDoubleAt(
                            "snow_depth_water_equivalent",
                            index,
                        ),
                        cloudCoverLow = hourlyJson.optionalIntAt("cloud_cover_low", index),
                        cloudCoverMid = hourlyJson.optionalIntAt("cloud_cover_mid", index),
                        cloudCoverHigh = hourlyJson.optionalIntAt("cloud_cover_high", index),
                        visibilityMeters = hourlyJson.optionalDoubleAt("visibility", index),
                        surfacePressure = hourlyJson.optionalDoubleAt("surface_pressure", index),
                        windGusts = hourlyJson.optionalDoubleAt("wind_gusts_10m", index),
                        cape = hourlyJson.optionalDoubleAt("cape", index),
                        vapourPressureDeficit = hourlyJson.optionalDoubleAt(
                            "vapour_pressure_deficit",
                            index,
                        ),
                        surfaceTemperature = hourlyJson.optionalDoubleAt("surface_temperature", index),
                        et0 = hourlyJson.optionalDoubleAt("et0_fao_evapotranspiration", index),
                        uvIndex = hourlyJson.optionalFiniteDoubleAt("uv_index", index),
                        freezingLevelHeightMeters = hourlyJson.optionalFiniteDoubleAt(
                            "freezing_level_height",
                            index,
                        ),
                        boundaryLayerHeightMeters = hourlyJson.optionalFiniteDoubleAt(
                            "boundary_layer_height",
                            index,
                        ),
                        integratedWaterVapour = hourlyJson.optionalFiniteDoubleAt(
                            "total_column_integrated_water_vapour",
                            index,
                        ),
                        liftedIndex = hourlyJson.optionalFiniteDoubleAt("lifted_index", index),
                        convectiveInhibition = hourlyJson.optionalFiniteDoubleAt(
                            "convective_inhibition",
                            index,
                        ),
                        soilTemperature0Cm = hourlyJson.optionalFiniteDoubleAt(
                            "soil_temperature_0cm",
                            index,
                        ),
                        soilMoisture0To1Cm = hourlyJson.optionalFiniteDoubleAt(
                            "soil_moisture_0_to_1cm",
                            index,
                        ),
                        showers = hourlyJson.optionalFiniteDoubleAt("showers", index),
                    ),
                )
            }
        }
        if (hourly.isEmpty()) throw JSONException("Předpověď neobsahuje úplná hodinová data.")

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
                dewPoint = currentJson.optionalDouble("dew_point_2m"),
                wetBulbTemperature = currentJson.optionalDouble("wet_bulb_temperature_2m"),
                rain = currentJson.optionalDouble("rain"),
                snowfall = currentJson.optionalDouble("snowfall"),
                snowDepthWaterEquivalent = currentJson.optionalDouble("snow_depth_water_equivalent"),
                cloudCoverLow = currentJson.optionalInt("cloud_cover_low"),
                cloudCoverMid = currentJson.optionalInt("cloud_cover_mid"),
                cloudCoverHigh = currentJson.optionalInt("cloud_cover_high"),
                visibilityMeters = currentJson.optionalDouble("visibility"),
                surfacePressure = currentJson.optionalDouble("surface_pressure"),
                cape = currentJson.optionalDouble("cape"),
                vapourPressureDeficit = currentJson.optionalDouble("vapour_pressure_deficit"),
                surfaceTemperature = currentJson.optionalDouble("surface_temperature"),
                uvIndex = currentJson.optionalFiniteDouble("uv_index"),
                freezingLevelHeightMeters = currentJson.optionalFiniteDouble("freezing_level_height"),
                boundaryLayerHeightMeters = currentJson.optionalFiniteDouble("boundary_layer_height"),
                integratedWaterVapour = currentJson.optionalFiniteDouble(
                    "total_column_integrated_water_vapour",
                ),
                liftedIndex = currentJson.optionalFiniteDouble("lifted_index"),
                convectiveInhibition = currentJson.optionalFiniteDouble("convective_inhibition"),
                soilTemperature0Cm = currentJson.optionalFiniteDouble("soil_temperature_0cm"),
                soilMoisture0To1Cm = currentJson.optionalFiniteDouble("soil_moisture_0_to_1cm"),
                showers = currentJson.optionalFiniteDouble("showers"),
            ),
            hourly = hourly,
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
                    apparentTemperatureMax = dailyJson.optionalDoubleAt("apparent_temperature_max", index),
                    apparentTemperatureMin = dailyJson.optionalDoubleAt("apparent_temperature_min", index),
                    daylightDurationSeconds = dailyJson.optionalDoubleAt("daylight_duration", index),
                    sunshineDurationSeconds = dailyJson.optionalDoubleAt("sunshine_duration", index),
                    rainSum = dailyJson.optionalDoubleAt("rain_sum", index),
                    snowfallSum = dailyJson.optionalDoubleAt("snowfall_sum", index),
                    precipitationHours = dailyJson.optionalDoubleAt("precipitation_hours", index),
                    windGustsMax = dailyJson.optionalDoubleAt("wind_gusts_10m_max", index),
                    dominantWindDirection = dailyJson.optionalIntAt("wind_direction_10m_dominant", index),
                    shortwaveRadiationSum = dailyJson.optionalDoubleAt("shortwave_radiation_sum", index),
                    et0 = dailyJson.optionalDoubleAt("et0_fao_evapotranspiration", index),
                    uvIndexMax = dailyJson.optionalFiniteDoubleAt("uv_index_max", index),
                )
            },
            updatedAtEpochMillis = updatedAtEpochMillis,
            calculation = root.forecastCalculationOrNull(),
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

    private fun JSONObject.optionalDouble(name: String): Double? =
        if (!has(name) || isNull(name)) null else getDouble(name)

    private fun JSONObject.optionalFiniteDouble(name: String): Double? =
        optionalDouble(name)?.also { value ->
            if (!value.isFinite()) throw JSONException("Hodnota $name není konečná.")
        }

    private fun JSONObject.optionalInt(name: String): Int? =
        if (!has(name) || isNull(name)) null else getInt(name)

    private fun JSONObject.optionalDoubleAt(name: String, index: Int): Double? {
        val values = optJSONArray(name) ?: return null
        return if (index >= values.length() || values.isNull(index)) null else values.getDouble(index)
    }

    private fun JSONObject.optionalFiniteDoubleAt(name: String, index: Int): Double? =
        optionalDoubleAt(name, index)?.also { value ->
            if (!value.isFinite()) throw JSONException("Hodnota $name[$index] není konečná.")
        }

    private fun JSONObject.optionalIntAt(name: String, index: Int): Int? {
        val values = optJSONArray(name) ?: return null
        return if (index >= values.length() || values.isNull(index)) null else values.getInt(index)
    }

    private val HOURLY_FIELDS = arrayOf(
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
}
