package cz.majkey.pocasicesko.data

import android.content.Context
import cz.majkey.pocasicesko.BuildConfig
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

internal class ChmiCurrentConditionsRepository(context: Context) {
    private val stations = context.assets.open(STATION_CATALOG_ASSET).bufferedReader().use { source ->
        decodeCurrentStationCatalog(source.readText())
    }

    fun fetch(location: CzechLocation, now: Instant): List<CurrentStationObservation> {
        val date = now.atZone(ZoneOffset.UTC).toLocalDate().format(DATE_FORMAT)
        return buildList {
            for (station in nearestCurrentStations(location, stations, REQUIRED_STATION_COUNT)) {
                val observation = runCatching {
                    parseCurrentStationObservation(request(station, date), station)
                }.getOrNull()
                if (observation != null) add(observation)
            }
        }
    }

    private fun request(station: CurrentStation, date: String): String {
        val url = "$CHMI_CURRENT_ROOT/10m-${station.stationId}-$date.json"
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
            connection.readTimeout = READ_TIMEOUT_MILLIS
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", USER_AGENT)
            if (connection.responseCode !in 200..299) {
                throw IOException("ČHMÚ returned HTTP ${connection.responseCode}.")
            }
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val STATION_CATALOG_ASSET = "chmi_current_stations.json"
        private const val CHMI_CURRENT_ROOT = "https://opendata.chmi.cz/meteorology/climate/now/data"
        private const val REQUIRED_STATION_COUNT = 3
        private const val CONNECT_TIMEOUT_MILLIS = 4_000
        private const val READ_TIMEOUT_MILLIS = 6_000
        private val DATE_FORMAT = DateTimeFormatter.BASIC_ISO_DATE
        private val USER_AGENT =
            "Selia-Vetra/${BuildConfig.VERSION_NAME} (Android; https://github.com/Majkey25/Selia-Weather)"
    }
}
