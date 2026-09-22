package cz.majkey.pocasicesko.data

import android.content.Context
import cz.majkey.pocasicesko.BuildConfig
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.Duration
import java.time.Instant
import java.util.Locale
import java.util.concurrent.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class WeatherWarningsRepository internal constructor(
    private val orpMapping: Map<Int, String>,
    private val fetchText: (String) -> String = ::requestWarningText,
) {
    constructor(context: Context) : this(
        context.assets.open("csu-ruian-orp.csv").bufferedReader().use { parseWarningOrpMapping(it.readText()) },
    )

    private val mutex = Mutex()
    private var cachedOrp: CachedOrp? = null
    private var cachedNwsPoint: CachedNwsPoint? = null
    private var cachedFeed: CachedFeed? = null

    suspend fun fetch(
        location: CzechLocation,
        language: String = "en",
        now: Instant = Instant.now(),
    ): WeatherWarningsResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            val countryCode = location.countryCode?.trim()?.takeIf { it.isNotEmpty() }?.uppercase(Locale.ROOT)
            val czechCandidate = location.isInCzechia() || (countryCode == null &&
                location.latitude in 48.45..51.2 && location.longitude in 11.9..19.0)
            // The bounds only select the lookup; ČÚZK polygon membership must still resolve an ORP.
            var country = if (czechCandidate) "CZ" else countryCode
            fun result(status: WeatherWarningsStatus, warnings: List<WeatherWarning> = emptyList(), checkedAt: Instant = now) =
                WeatherWarningsResult(status, warnings, checkedAt,
                    sourceName = when (country) {
                        "CZ" -> "ČHMÚ"
                        "US" -> "National Weather Service"
                        "GB", "UK" -> "Met Office"
                        else -> "WMO"
                    },
                    sourceUrl = when (country) {
                        "CZ" -> CHMI_WARNING_PAGE
                        "US" -> NWS_WARNING_PAGE
                        "GB", "UK" -> "https://weather.metoffice.gov.uk/warnings-and-advice/uk-warnings"
                        else -> "https://severeweather.wmo.int/sources.html"
                    },
                )
            try {
                require(location.latitude.isFinite() && location.latitude in -90.0..90.0 &&
                    location.longitude.isFinite() && location.longitude in -180.0..180.0)
                currentCoroutineContext().ensureActive()
                if (country == null) {
                    verifyNwsPoint(location, now)
                    country = "US"
                }
                if (country == "GB" || country == "UK") return@withLock result(WeatherWarningsStatus.UNAVAILABLE)
                if (country != "CZ" && country != "US") return@withLock result(WeatherWarningsStatus.UNSUPPORTED)
                val orp = if (country == "CZ") orp(location, now) else null
                val url = if (country == "CZ") CHMI_CAP_URL else nwsUrl(location)
                val feed = cachedFeed?.takeIf { it.url == url && it.checkedAt.isFreshAt(now, FEED_CACHE_SECONDS) }
                    ?: CachedFeed(url, fetchText(url), now).also { cachedFeed = it }
                currentCoroutineContext().ensureActive()
                val warnings = if (orp != null) parseChmiWarnings(feed.text, orp, language, now) else parseNwsWarnings(feed.text, now)
                result(WeatherWarningsStatus.AVAILABLE, warnings, feed.checkedAt)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                cachedFeed = null
                result(WeatherWarningsStatus.UNAVAILABLE)
            }
        }
    }

    private fun verifyNwsPoint(location: CzechLocation, now: Instant) {
        cachedNwsPoint?.takeIf {
            it.latitude == location.latitude && it.longitude == location.longitude && it.checkedAt.isFreshAt(now, NWS_POINT_CACHE_SECONDS)
        }?.let { return }
        validateNwsWarningPoint(fetchText(nwsPointUrl(location)), location)
        cachedNwsPoint = CachedNwsPoint(location.latitude, location.longitude, now)
    }

    private fun orp(location: CzechLocation, now: Instant): String {
        cachedOrp?.takeIf {
            it.latitude == location.latitude && it.longitude == location.longitude && it.checkedAt.isFreshAt(now, ORP_CACHE_SECONDS)
        }?.let { return it.code }
        val code = parseWarningOrpCode(fetchText(orpUrl(location)), orpMapping)
        cachedOrp = CachedOrp(location.latitude, location.longitude, code, now)
        return code
    }

    companion object {
        internal fun orpUrl(location: CzechLocation): String =
            "https://ags.cuzk.gov.cz/arcgis/rest/services/RUIAN/MapServer/14/query" +
                "?geometry=${location.longitude}%2C${location.latitude}&geometryType=esriGeometryPoint&inSR=4326" +
                "&spatialRel=esriSpatialRelIntersects&outFields=kod&returnGeometry=false&f=json"

        internal fun nwsUrl(location: CzechLocation): String =
            "https://api.weather.gov/alerts/active?point=${location.latitude},${location.longitude}"

        internal fun nwsPointUrl(location: CzechLocation): String =
            "https://api.weather.gov/points/" + String.format(Locale.US, "%.4f,%.4f", location.latitude, location.longitude)
    }
}

private data class CachedOrp(val latitude: Double, val longitude: Double, val code: String, val checkedAt: Instant)
private data class CachedNwsPoint(val latitude: Double, val longitude: Double, val checkedAt: Instant)
private data class CachedFeed(val url: String, val text: String, val checkedAt: Instant)

private fun Instant.isFreshAt(now: Instant, lifetimeSeconds: Long): Boolean = Duration.between(this, now).seconds in 0 until lifetimeSeconds

private fun requestWarningText(url: String): String {
    val connection = URL(url).openConnection() as HttpURLConnection
    return try {
        connection.connectTimeout = 5_000
        connection.readTimeout = 8_000
        connection.instanceFollowRedirects = false
        connection.useCaches = false
        connection.setRequestProperty("Accept", "application/geo+json, application/json, application/cap+xml, text/xml")
        connection.setRequestProperty("User-Agent", "Selia-Weather/${BuildConfig.VERSION_NAME} (Android; https://github.com/Majkey25/Selia-Weather)")
        if (connection.responseCode != HttpURLConnection.HTTP_OK) throw IOException("Warning source returned HTTP ${connection.responseCode}.")
        connection.inputStream.use { readLimited(it, MAX_WARNING_RESPONSE_BYTES).toString(Charsets.UTF_8) }
    } finally {
        connection.disconnect()
    }
}

private const val CHMI_CAP_URL = "https://vystrahy-cr.chmi.cz/data/XOCZ50_OKPR.xml"
private const val FEED_CACHE_SECONDS = 60L
private const val ORP_CACHE_SECONDS = 24 * 60 * 60L
private const val NWS_POINT_CACHE_SECONDS = 24 * 60 * 60L
