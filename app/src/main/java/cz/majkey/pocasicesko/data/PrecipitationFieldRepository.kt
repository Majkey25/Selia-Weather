package cz.majkey.pocasicesko.data

import cz.majkey.pocasicesko.BuildConfig
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class PrecipitationFieldRepository(
    private val fetchText: (String) -> String = ::requestPrecipitationField,
) {
    suspend fun fetch(location: CzechLocation): PrecipitationField = withContext(Dispatchers.IO) {
        val points = precipitationFieldPoints(location)
        val models = forecastApiModelsFor(location)
        parsePrecipitationField(fetchText(url(points, models)), points, models)
    }

    companion object {
        internal fun url(
            points: List<PrecipitationFieldPoint>,
            models: List<String>,
        ): String = "$BASE_URL?latitude=${points.joinToString(",") { it.latitude.toString() }}" +
            "&longitude=${points.joinToString(",") { it.longitude.toString() }}" +
            "&forecast_hours=24&timeformat=unixtime&timezone=GMT" +
            "&hourly=precipitation,rain,showers,snowfall" +
            "&models=${models.joinToString(",")}"

        private const val BASE_URL = "https://api.open-meteo.com/v1/forecast"
    }
}

private fun requestPrecipitationField(url: String): String {
    val connection = URL(url).openConnection() as HttpURLConnection
    return try {
        connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
        connection.readTimeout = READ_TIMEOUT_MILLIS
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("User-Agent", USER_AGENT)
        if (connection.responseCode !in 200..299) {
            throw IOException("Spatial forecast returned HTTP ${connection.responseCode}.")
        }
        connection.inputStream.use { readLimited(it, MAX_RESPONSE_BYTES).toString(Charsets.UTF_8) }
    } finally {
        connection.disconnect()
    }
}

private const val CONNECT_TIMEOUT_MILLIS = 10_000
private const val READ_TIMEOUT_MILLIS = 15_000
private const val MAX_RESPONSE_BYTES = 5_000_000
private val USER_AGENT =
    "Selia-Vetra/${BuildConfig.VERSION_NAME} (Android; https://github.com/Majkey25/Selia-Weather)"
