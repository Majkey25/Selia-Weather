package cz.majkey.pocasicesko.data

import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import cz.majkey.pocasicesko.widget.WeatherWidgetProvider
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

class WeatherRepository(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun lastLocation(): CzechLocation = CzechLocation(
        name = preferences.getString(KEY_LOCATION_NAME, null) ?: DEFAULT_LOCATION.name,
        region = preferences.getString(KEY_LOCATION_REGION, null) ?: DEFAULT_LOCATION.region,
        latitude = preferences.getString(KEY_LOCATION_LATITUDE, null)?.toDoubleOrNull() ?: DEFAULT_LOCATION.latitude,
        longitude = preferences.getString(KEY_LOCATION_LONGITUDE, null)?.toDoubleOrNull() ?: DEFAULT_LOCATION.longitude,
    )

    fun selectLocation(location: CzechLocation) {
        preferences.edit {
            putString(KEY_LOCATION_NAME, location.name)
            putString(KEY_LOCATION_REGION, location.region)
            putString(KEY_LOCATION_LATITUDE, location.latitude.toString())
            putString(KEY_LOCATION_LONGITUDE, location.longitude.toString())
        }
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

    fun fetchForecastBlocking(location: CzechLocation): WeatherSnapshot {
        val json = request(forecastUri(location).toString())
        val updatedAt = System.currentTimeMillis()
        val snapshot = WeatherParser.parseForecast(json, updatedAt)
        persist(location, json, snapshot)
        WeatherWidgetProvider.updateAll(appContext)
        return snapshot
    }

    suspend fun searchLocations(query: String): List<CzechLocation> = withContext(Dispatchers.IO) {
        val cleanedQuery = query.trim()
        if (cleanedQuery.length < MINIMUM_SEARCH_LENGTH) return@withContext emptyList()

        val root = JSONObject(request(geocodingUri(cleanedQuery).toString()))
        val results = root.optJSONArray("results") ?: return@withContext emptyList()
        buildList {
            for (index in 0 until results.length()) {
                val result = results.getJSONObject(index)
                if (result.optString("country_code") != "CZ") continue
                add(
                    CzechLocation(
                        name = result.getString("name"),
                        region = result.optString("admin1").ifBlank { "Česko" },
                        latitude = result.getDouble("latitude"),
                        longitude = result.getDouble("longitude"),
                    ),
                )
            }
        }
    }

    private fun persist(location: CzechLocation, json: String, snapshot: WeatherSnapshot) {
        val today = snapshot.daily.first()
        val condition = conditionFor(snapshot.current.weatherCode, snapshot.current.isDay)
        preferences.edit {
            putString(KEY_CACHE_JSON, json)
            putString(KEY_CACHE_LATITUDE, location.latitude.toString())
            putString(KEY_CACHE_LONGITUDE, location.longitude.toString())
            putLong(KEY_CACHE_UPDATED_AT, snapshot.updatedAtEpochMillis)
            putString(KEY_WIDGET_CITY, location.name)
            putFloat(KEY_WIDGET_TEMPERATURE, snapshot.current.temperature.toFloat())
            putString(KEY_WIDGET_CONDITION, condition.label)
            putString(KEY_WIDGET_KIND, condition.kind.name)
            putBoolean(KEY_WIDGET_IS_DAY, snapshot.current.isDay)
            putFloat(KEY_WIDGET_HIGH, today.temperatureMax.toFloat())
            putFloat(KEY_WIDGET_LOW, today.temperatureMin.toFloat())
        }
    }

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
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun forecastUri(location: CzechLocation): Uri = Uri.Builder()
        .scheme("https")
        .authority("api.open-meteo.com")
        .appendPath("v1")
        .appendPath("forecast")
        .appendQueryParameter("latitude", location.latitude.toString())
        .appendQueryParameter("longitude", location.longitude.toString())
        .appendQueryParameter("models", "chmi_aladin_seamless")
        .appendQueryParameter("forecast_days", "14")
        .appendQueryParameter("timezone", "Europe/Prague")
        .appendQueryParameter("current", CURRENT_VARIABLES)
        .appendQueryParameter("hourly", HOURLY_VARIABLES)
        .appendQueryParameter("daily", DAILY_VARIABLES)
        .build()

    private fun geocodingUri(query: String): Uri = Uri.Builder()
        .scheme("https")
        .authority("geocoding-api.open-meteo.com")
        .appendPath("v1")
        .appendPath("search")
        .appendQueryParameter("name", query)
        .appendQueryParameter("count", "8")
        .appendQueryParameter("language", "cs")
        .appendQueryParameter("countryCode", "CZ")
        .appendQueryParameter("format", "json")
        .build()

    companion object {
        const val PREFERENCES_NAME = "weather"
        const val KEY_WIDGET_CITY = "widget_city"
        const val KEY_WIDGET_TEMPERATURE = "widget_temperature"
        const val KEY_WIDGET_CONDITION = "widget_condition"
        const val KEY_WIDGET_KIND = "widget_kind"
        const val KEY_WIDGET_IS_DAY = "widget_is_day"
        const val KEY_WIDGET_HIGH = "widget_high"
        const val KEY_WIDGET_LOW = "widget_low"

        private const val KEY_LOCATION_NAME = "location_name"
        private const val KEY_LOCATION_REGION = "location_region"
        private const val KEY_LOCATION_LATITUDE = "location_latitude"
        private const val KEY_LOCATION_LONGITUDE = "location_longitude"
        private const val KEY_CACHE_JSON = "cache_json"
        private const val KEY_CACHE_LATITUDE = "cache_latitude"
        private const val KEY_CACHE_LONGITUDE = "cache_longitude"
        private const val KEY_CACHE_UPDATED_AT = "cache_updated_at"
        private const val COORDINATE_EPSILON = 0.000_001
        private const val MINIMUM_SEARCH_LENGTH = 2
        private const val CONNECT_TIMEOUT_MILLIS = 10_000
        private const val READ_TIMEOUT_MILLIS = 15_000
        private const val USER_AGENT = "PocasiCesko/0.1.0 (Android)"

        private const val CURRENT_VARIABLES =
            "temperature_2m,relative_humidity_2m,apparent_temperature,is_day,precipitation," +
                "weather_code,cloud_cover,pressure_msl,wind_speed_10m,wind_direction_10m,wind_gusts_10m"
        private const val HOURLY_VARIABLES =
            "temperature_2m,relative_humidity_2m,precipitation_probability,precipitation," +
                "weather_code,pressure_msl,wind_speed_10m,wind_direction_10m,is_day"
        private const val DAILY_VARIABLES =
            "weather_code,temperature_2m_max,temperature_2m_min,sunrise,sunset," +
                "precipitation_sum,precipitation_probability_max,wind_speed_10m_max"

        val DEFAULT_LOCATION = CzechLocation(
            name = "Praha",
            region = "Hlavní město Praha",
            latitude = 50.0755,
            longitude = 14.4378,
        )
    }
}
