package cz.majkey.pocasicesko.data

import android.content.Context
import android.net.Uri
import cz.majkey.pocasicesko.astro.MoonCalculator
import androidx.core.content.edit
import cz.majkey.pocasicesko.BuildConfig
import cz.majkey.pocasicesko.locale.AppLocale
import cz.majkey.pocasicesko.widget.WeatherWidgetProvider
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject

class WeatherRepository(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val currentConditions = ChmiCurrentConditionsRepository(appContext)
    private val precipitationFieldRepository = PrecipitationFieldRepository()
    private val historyRepository = HistoryRepository(File(appContext.cacheDir, "history"))
    private val staticForecastRepository = StaticForecastRepository()
    @Volatile private var calibrationArtifact: CalibrationArtifact? = null

    fun lastLocation(): CzechLocation {
        val location = CzechLocation(
            name = preferences.getString(KEY_LOCATION_NAME, null) ?: DEFAULT_LOCATION.name,
            region = preferences.getString(KEY_LOCATION_REGION, null) ?: DEFAULT_LOCATION.region,
            latitude = preferences.getString(KEY_LOCATION_LATITUDE, null)?.toDoubleOrNull() ?: DEFAULT_LOCATION.latitude,
            longitude = preferences.getString(KEY_LOCATION_LONGITUDE, null)?.toDoubleOrNull() ?: DEFAULT_LOCATION.longitude,
            countryCode = preferences.getString(KEY_LOCATION_COUNTRY_CODE, null),
        )
        return normalizeLocationRegion(location).also { normalized ->
            if (normalized != location) selectLocation(normalized)
        }
    }

    fun selectLocation(location: CzechLocation) {
        preferences.edit {
            val normalized = normalizeLocationRegion(location)
            putString(KEY_LOCATION_NAME, normalized.name)
            putString(KEY_LOCATION_REGION, normalized.region)
            putString(KEY_LOCATION_LATITUDE, normalized.latitude.toString())
            putString(KEY_LOCATION_LONGITUDE, normalized.longitude.toString())
            normalized.countryCode?.let { putString(KEY_LOCATION_COUNTRY_CODE, it) }
                ?: remove(KEY_LOCATION_COUNTRY_CODE)
        }
    }

    fun favoriteLocations(): List<CzechLocation> {
        val json = preferences.getString(KEY_FAVORITE_LOCATIONS, null) ?: return emptyList()
        val locations = runCatching { LocationFavoritesCodec.decode(json) }.getOrElse { return emptyList() }
        preferences.edit { putString(KEY_FAVORITE_LOCATIONS, LocationFavoritesCodec.encode(locations)) }
        return locations
    }

    fun isFavorite(location: CzechLocation): Boolean = favoriteLocations().any { it.matches(location) }

    fun toggleFavorite(location: CzechLocation): Boolean {
        val favorites = favoriteLocations().toMutableList()
        val existingIndex = favorites.indexOfFirst { it.matches(location) }
        val added = existingIndex < 0
        if (added) {
            if (favorites.size >= MAX_FAVORITE_LOCATIONS) {
                throw IllegalStateException("Lze uložit nejvýše $MAX_FAVORITE_LOCATIONS lokalit.")
            }
            favorites.add(location)
        } else {
            favorites.removeAt(existingIndex)
        }
        preferences.edit { putString(KEY_FAVORITE_LOCATIONS, LocationFavoritesCodec.encode(favorites)) }
        return added
    }

    fun cachedForecast(location: CzechLocation): WeatherSnapshot? {
        val latitude = preferences.getString(KEY_CACHE_LATITUDE, null)?.toDoubleOrNull() ?: return null
        val longitude = preferences.getString(KEY_CACHE_LONGITUDE, null)?.toDoubleOrNull() ?: return null
        if (kotlin.math.abs(latitude - location.latitude) > COORDINATE_EPSILON ||
            kotlin.math.abs(longitude - location.longitude) > COORDINATE_EPSILON
        ) {
            return null
        }

        val json = preferences.getString(KEY_CACHE_JSON, null) ?: return null
        val updatedAt = preferences.getLong(KEY_CACHE_UPDATED_AT, 0L)
        return runCatching { WeatherParser.parseForecast(json, updatedAt) }.getOrNull()
    }

    suspend fun fetchForecast(location: CzechLocation): WeatherSnapshot = withContext(Dispatchers.IO) {
        fetchForecastBlocking(location)
    }

    internal suspend fun fetchPrecipitationField(location: CzechLocation): PrecipitationField =
        precipitationFieldRepository.fetch(location, calibrationArtifact)

    internal suspend fun fetchHistory(location: CzechLocation): HistoryArchive =
        historyRepository.fetch(location)

    fun fetchForecastBlocking(location: CzechLocation): WeatherSnapshot {
        val bestMatchJson = request(forecastUri(location).toString())
        val requestedModels = forecastApiModelsFor(location)
        val calibration = try {
            staticForecastRepository.fetchCalibrationArtifact(Instant.now())
        } catch (_: IOException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: JSONException) {
            null
        }
        calibrationArtifact = calibration
        val blend = try {
            blendModelForecast(
                bestMatchJson,
                request(modelForecastUrl(location)),
                location,
                calibration,
            )
        } catch (_: IOException) {
            ModelBlendResult(
                bestMatchJson,
                ForecastCalculationMode.BEST_MATCH,
                emptyList(),
                ForecastFallbackReason.PROVIDER_UNAVAILABLE,
            )
        } catch (_: JSONException) {
            ModelBlendResult(
                bestMatchJson,
                ForecastCalculationMode.BEST_MATCH,
                emptyList(),
                ForecastFallbackReason.PROVIDER_UNAVAILABLE,
            )
        }
        val calculation = ForecastCalculation(
            region = forecastRegionFor(location),
            mode = blend.mode,
            requestedModelIds = requestedModels,
            contributorIds = blend.contributorIds,
            fallbackReason = blend.fallbackReason,
            artifactVersion = blend.artifactVersion,
            truthClass = blend.truthClass,
            weights = blend.appliedWeights,
        )
        val forecastJson = JSONObject(blend.json).putForecastCalculation(calculation).toString()
        val updatedAt = System.currentTimeMillis()
        val modelSnapshot = WeatherParser.parseForecast(forecastJson, updatedAt)
        val now = Instant.ofEpochMilli(updatedAt)
        val observedCurrent = fuseCurrentConditions(
            model = modelSnapshot.current,
            location = location,
            observations = if (location.isInCzechia()) {
                currentConditions.fetch(location, now)
            } else {
                emptyList()
            },
            now = now,
        )
        val correctedJson = applyCurrentConditionsToForecastJson(forecastJson, observedCurrent)
        val snapshot = WeatherParser.parseForecast(correctedJson, updatedAt)
        persist(location, correctedJson, snapshot)
        WeatherWidgetProvider.updateAll(appContext)
        return snapshot
    }

    suspend fun searchLocations(query: String): List<CzechLocation> = withContext(Dispatchers.IO) {
        val cleanedQuery = query.trim()
        if (cleanedQuery.length < MINIMUM_SEARCH_LENGTH) return@withContext emptyList()

        parseLocationSearchResults(
            request(geocodingUrl(cleanedQuery, AppLocale.languageTag(appContext))),
        )
    }

    private fun persist(location: CzechLocation, json: String, snapshot: WeatherSnapshot) {
        val today = snapshot.currentDay()
        val condition = conditionFor(snapshot.current.weatherCode, snapshot.current.isDay)
        val currentHour = snapshot.current.time.take(13)
        val nextHours = snapshot.hourly.dropWhile { it.time.take(13) < currentHour }.take(3)
        val moon = runCatching {
            MoonCalculator.calculate(
                LocalDateTime.parse(snapshot.current.time).atZone(ZoneId.of(snapshot.timezone)),
                location.latitude,
                location.longitude,
            )
        }.getOrNull()
        preferences.edit {
            putString(KEY_CACHE_JSON, json)
            putString(KEY_CACHE_LATITUDE, location.latitude.toString())
            putString(KEY_CACHE_LONGITUDE, location.longitude.toString())
            putLong(KEY_CACHE_UPDATED_AT, snapshot.updatedAtEpochMillis)
            putString(KEY_WIDGET_CITY, location.name)
            putFloat(KEY_WIDGET_TEMPERATURE, snapshot.current.temperature.toFloat())
            putString(KEY_WIDGET_CONDITION_KEY, condition.key.name)
            putString(KEY_WIDGET_KIND, condition.kind.name)
            putBoolean(KEY_WIDGET_IS_DAY, snapshot.current.isDay)
            putFloat(KEY_WIDGET_HIGH, today.temperatureMax.toFloat())
            putFloat(KEY_WIDGET_LOW, today.temperatureMin.toFloat())
            putInt(
                KEY_WIDGET_PRECIPITATION_PROBABILITY,
                nextHours.firstOrNull()?.precipitationProbability ?: -1,
            )
            putFloat(KEY_WIDGET_WIND_SPEED, snapshot.current.windSpeed.toFloat())
            putInt(KEY_WIDGET_HUMIDITY, snapshot.current.humidity)
            putFloat(KEY_WIDGET_DEW_POINT, snapshot.current.dewPoint?.toFloat() ?: Float.NaN)
            putFloat(KEY_WIDGET_PRESSURE, snapshot.current.pressure.toFloat())
            putFloat(KEY_WIDGET_VISIBILITY, snapshot.current.visibilityMeters?.toFloat() ?: Float.NaN)
            putFloat(KEY_WIDGET_WIND_GUSTS, snapshot.current.windGusts.toFloat())
            putString(KEY_WIDGET_MOON_PHASE, moon?.phase?.name.orEmpty())
            putFloat(KEY_WIDGET_MOON_ILLUMINATION, moon?.illuminatedFraction?.toFloat() ?: Float.NaN)
            putLong(KEY_WIDGET_UPDATED_AT, snapshot.updatedAtEpochMillis)
            putString(KEY_WIDGET_HOURLY_TIMES, nextHours.joinToString("|") { it.time.takeLast(5) })
            putString(
                KEY_WIDGET_HOURLY_TEMPERATURES,
                nextHours.joinToString("|") { it.temperature.roundToInt().toString() },
            )
        }
    }

    private fun CzechLocation.matches(other: CzechLocation): Boolean =
        kotlin.math.abs(latitude - other.latitude) <= COORDINATE_EPSILON &&
            kotlin.math.abs(longitude - other.longitude) <= COORDINATE_EPSILON

    private fun request(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
            connection.readTimeout = READ_TIMEOUT_MILLIS
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", USER_AGENT)
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                throw IOException("Server odpověděl kódem $responseCode.")
            }
            connection.inputStream.use { readLimited(it, MAX_JSON_BYTES).toString(Charsets.UTF_8) }
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        internal fun forecastUri(location: CzechLocation): Uri = Uri.parse(forecastUrl(location))

        internal fun modelForecastUrl(location: CzechLocation): String =
            "https://api.open-meteo.com/v1/forecast?latitude=${location.latitude}" +
                "&longitude=${location.longitude}&forecast_days=14&past_days=7" +
                "&timezone=auto&hourly=$MODEL_VARIABLES" +
                "&models=${forecastApiModelsFor(location).joinToString(",")}"

        internal fun forecastUrl(location: CzechLocation): String =
            "https://api.open-meteo.com/v1/forecast?latitude=${location.latitude}" +
                "&longitude=${location.longitude}&forecast_days=14&past_days=7" +
                "&timezone=auto&current=$CURRENT_VARIABLES&hourly=$HOURLY_VARIABLES" +
                "&daily=$DAILY_VARIABLES"

        internal fun geocodingUrl(query: String, languageTag: String): String =
            "https://geocoding-api.open-meteo.com/v1/search" +
                "?name=${query.urlEncoded()}&count=8&language=${languageTag.urlEncoded()}&format=json"

        const val PREFERENCES_NAME = "weather"
        const val KEY_WIDGET_CITY = "widget_city"
        const val KEY_WIDGET_TEMPERATURE = "widget_temperature"
        const val KEY_WIDGET_CONDITION_KEY = "widget_condition_key"
        const val KEY_WIDGET_KIND = "widget_kind"
        const val KEY_WIDGET_IS_DAY = "widget_is_day"
        const val KEY_WIDGET_HIGH = "widget_high"
        const val KEY_WIDGET_LOW = "widget_low"
        const val KEY_WIDGET_PRECIPITATION_PROBABILITY = "widget_precipitation_probability"
        const val KEY_WIDGET_WIND_SPEED = "widget_wind_speed"
        const val KEY_WIDGET_HUMIDITY = "widget_humidity"
        const val KEY_WIDGET_DEW_POINT = "widget_dew_point"
        const val KEY_WIDGET_PRESSURE = "widget_pressure"
        const val KEY_WIDGET_VISIBILITY = "widget_visibility"
        const val KEY_WIDGET_WIND_GUSTS = "widget_wind_gusts"
        const val KEY_WIDGET_MOON_PHASE = "widget_moon_phase"
        const val KEY_WIDGET_MOON_ILLUMINATION = "widget_moon_illumination"
        const val KEY_WIDGET_UPDATED_AT = "widget_updated_at"
        const val KEY_WIDGET_HOURLY_TIMES = "widget_hourly_times"
        const val KEY_WIDGET_HOURLY_TEMPERATURES = "widget_hourly_temperatures"

        private const val KEY_LOCATION_NAME = "location_name"
        private const val KEY_LOCATION_REGION = "location_region"
        private const val KEY_LOCATION_LATITUDE = "location_latitude"
        private const val KEY_LOCATION_LONGITUDE = "location_longitude"
        private const val KEY_LOCATION_COUNTRY_CODE = "location_country_code"
        private const val KEY_CACHE_JSON = "cache_json"
        private const val KEY_CACHE_LATITUDE = "cache_latitude"
        private const val KEY_CACHE_LONGITUDE = "cache_longitude"
        private const val KEY_CACHE_UPDATED_AT = "cache_updated_at"
        private const val KEY_FAVORITE_LOCATIONS = "favorite_locations"
        private const val COORDINATE_EPSILON = 0.000_001
        private const val MAX_FAVORITE_LOCATIONS = 12
        private const val MINIMUM_SEARCH_LENGTH = 2
        private const val CONNECT_TIMEOUT_MILLIS = 10_000
        private const val READ_TIMEOUT_MILLIS = 15_000
        private const val MAX_JSON_BYTES = 5_000_000
        private val USER_AGENT = "Selia-Weather/${BuildConfig.VERSION_NAME} (Android; https://github.com/Majkey25/Selia-Weather)"

        private const val CURRENT_VARIABLES =
            "temperature_2m,relative_humidity_2m,dew_point_2m,apparent_temperature," +
                "wet_bulb_temperature_2m,is_day,precipitation,rain,snowfall," +
                "snow_depth_water_equivalent,weather_code,cloud_cover,cloud_cover_low," +
                "cloud_cover_mid,cloud_cover_high,visibility,pressure_msl,surface_pressure," +
                "wind_speed_10m,wind_direction_10m,wind_gusts_10m,cape," +
                "vapour_pressure_deficit,surface_temperature,uv_index,freezing_level_height," +
                "boundary_layer_height,total_column_integrated_water_vapour,lifted_index," +
                "convective_inhibition,soil_temperature_0cm,soil_moisture_0_to_1cm,showers"
        private const val HOURLY_VARIABLES =
            "$CURRENT_VARIABLES,precipitation_probability,et0_fao_evapotranspiration"
        private const val DAILY_VARIABLES =
            "weather_code,temperature_2m_max,temperature_2m_min,apparent_temperature_max," +
                "apparent_temperature_min,sunrise,sunset,daylight_duration,sunshine_duration," +
                "precipitation_sum,rain_sum,snowfall_sum,precipitation_hours," +
                "precipitation_probability_max,wind_speed_10m_max,wind_gusts_10m_max," +
                "wind_direction_10m_dominant,shortwave_radiation_sum," +
                "et0_fao_evapotranspiration,uv_index_max"

        private const val MODEL_VARIABLES =
            "temperature_2m,relative_humidity_2m,dew_point_2m,apparent_temperature," +
                "precipitation,rain,snowfall,weather_code,cloud_cover,cloud_cover_low," +
                "cloud_cover_mid,cloud_cover_high,visibility,pressure_msl,surface_pressure," +
                "wind_speed_10m,wind_direction_10m,wind_gusts_10m"

        val DEFAULT_LOCATION = CzechLocation(
            name = "Praha",
            region = REGION_PRAGUE,
            latitude = 50.0755,
            longitude = 14.4378,
            countryCode = "CZ",
        )
    }
}

private fun String.urlEncoded(): String = URLEncoder.encode(this, StandardCharsets.UTF_8.toString())

internal fun parseLocationSearchResults(json: String): List<CzechLocation> {
    val results = JSONObject(json).optJSONArray("results") ?: return emptyList()
    return buildList {
        for (index in 0 until results.length()) {
            val result = results.optJSONObject(index) ?: continue
            val name = result.optString("name").trim()
            val countryCode = result.optString("country_code")
                .trim()
                .uppercase(Locale.ROOT)
                .takeIf { it.length == 2 && it.all(Char::isLetter) }
            val latitude = result.optDouble("latitude", Double.NaN)
            val longitude = result.optDouble("longitude", Double.NaN)
            if (name.isEmpty() || countryCode == null || !latitude.isFinite() || !longitude.isFinite() ||
                latitude !in -90.0..90.0 || longitude !in -180.0..180.0
            ) {
                continue
            }
            val region = if (countryCode == "CZ") {
                regionKeyForAdmin1Id(result.optInt("admin1_id"))
            } else {
                result.optString("admin1").trim()
                    .ifEmpty { result.optString("country").trim() }
                    .ifEmpty { REGION_WORLD }
            }
            add(CzechLocation(name, region, latitude, longitude, countryCode))
        }
    }
}
