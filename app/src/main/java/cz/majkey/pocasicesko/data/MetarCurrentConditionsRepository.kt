package cz.majkey.pocasicesko.data

import cz.majkey.pocasicesko.BuildConfig
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.asin
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import org.json.JSONException

internal class MetarCurrentConditionsRepository(
    private val fetchText: (String) -> String = ::requestMetar,
) {
    fun fetch(location: CzechLocation): List<CurrentStationObservation> {
        var failure: Exception? = null
        val responses = urls(location).mapNotNull { url ->
            ensureForecastThreadActive()
            try {
                parseMetarCurrentConditions(fetchText(url))
            } catch (error: Exception) {
                if (error !is IOException && error !is JSONException && error !is IllegalArgumentException) throw error
                if (failure == null) failure = error
                null
            }
        }
        ensureForecastThreadActive()
        if (responses.isEmpty()) failure?.let { throw it }
        return responses.flatten().groupBy(CurrentStationObservation::stationId)
            .mapNotNull { (_, observations) -> observations.maxByOrNull(CurrentStationObservation::time) }
            .sortedBy(CurrentStationObservation::stationId)
    }

    companion object {
        private fun urls(location: CzechLocation): List<String> {
            require(location.latitude in -90.0..90.0 && location.longitude in -180.0..180.0)
            val angularRadius = STATION_RADIUS_KM / EARTH_RADIUS_KM
            val latitudeRadius = Math.toDegrees(angularRadius)
            val south = (location.latitude - latitudeRadius).coerceAtLeast(-90.0)
            val north = (location.latitude + latitudeRadius).coerceAtMost(90.0)
            fun url(west: Double, east: Double): String {
                // Round outward so query precision cannot exclude a station on the radius.
                val bounds = String.format(Locale.US, "%.4f,%.4f,%.4f,%.4f",
                    floor(south * 10_000) / 10_000, floor(west * 10_000) / 10_000,
                    ceil(north * 10_000) / 10_000, ceil(east * 10_000) / 10_000)
                return "$BASE_URL?bbox=$bounds&format=json&hours=2"
            }
            if (south == -90.0 || north == 90.0) return listOf(url(-180.0, 180.0))
            val longitudeRadius = Math.toDegrees(asin(sin(angularRadius) / cos(Math.toRadians(location.latitude))))
            val west = location.longitude - longitudeRadius
            val east = location.longitude + longitudeRadius
            return when {
                west < -180.0 -> listOf(url(west + 360.0, 180.0), url(-180.0, east))
                east > 180.0 -> listOf(url(west, 180.0), url(-180.0, east - 360.0))
                else -> listOf(url(west, east))
            }
        }

        private const val BASE_URL = "https://aviationweather.gov/api/data/metar"
        // Match the spherical distance and maximum station radius used by fuseCurrentConditions.
        private const val STATION_RADIUS_KM = 50.0
        private const val EARTH_RADIUS_KM = 6_371.0088
    }
}

private fun requestMetar(url: String): String {
    val connection = URL(url).openConnection() as HttpURLConnection
    return try {
        connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
        connection.readTimeout = READ_TIMEOUT_MILLIS
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("User-Agent", USER_AGENT)
        when (val responseCode = connection.responseCode) {
            HttpURLConnection.HTTP_NO_CONTENT -> "[]"
            in 200..299 -> connection.inputStream.use {
                readLimited(it, MAX_RESPONSE_BYTES).toString(Charsets.UTF_8)
            }
            else -> throw IOException("AviationWeather returned HTTP $responseCode.")
        }
    } finally {
        connection.disconnect()
    }
}

private const val CONNECT_TIMEOUT_MILLIS = 10_000
private const val READ_TIMEOUT_MILLIS = 15_000
private const val MAX_RESPONSE_BYTES = 1_000_000
private val USER_AGENT =
    "Selia-Weather/${BuildConfig.VERSION_NAME} (Android; https://github.com/Majkey25/Selia-Weather)"
