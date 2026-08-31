package cz.majkey.pocasicesko.data

import cz.majkey.pocasicesko.BuildConfig
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

internal class MetarCurrentConditionsRepository(
    private val fetchText: (String) -> String = ::requestMetar,
) {
    fun fetch(location: CzechLocation): List<CurrentStationObservation> =
        parseMetarCurrentConditions(fetchText(url(location)))

    companion object {
        internal fun url(location: CzechLocation): String {
            val south = (location.latitude - BOUNDING_BOX_RADIUS_DEGREES).coerceAtLeast(-90.0)
            val north = (location.latitude + BOUNDING_BOX_RADIUS_DEGREES).coerceAtMost(90.0)
            val west = (location.longitude - BOUNDING_BOX_RADIUS_DEGREES).coerceAtLeast(-180.0)
            val east = (location.longitude + BOUNDING_BOX_RADIUS_DEGREES).coerceAtMost(180.0)
            val bounds = String.format(Locale.US, "%.4f,%.4f,%.4f,%.4f", south, west, north, east)
            return "$BASE_URL?bbox=$bounds&format=json&hours=2"
        }

        private const val BASE_URL = "https://aviationweather.gov/api/data/metar"
        private const val BOUNDING_BOX_RADIUS_DEGREES = 1.5
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
    "Selia-Vetra/${BuildConfig.VERSION_NAME} (Android; https://github.com/Majkey25/Selia-Weather)"
