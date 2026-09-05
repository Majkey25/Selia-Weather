package cz.majkey.pocasicesko.data

import cz.majkey.pocasicesko.BuildConfig
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONException

internal class HistoryRepository(
    private val cacheDirectory: File,
    private val fetchText: (String) -> String = ::requestPowerHistory,
    private val now: () -> Instant = Instant::now,
) {
    suspend fun fetch(location: CzechLocation): HistoryArchive = withContext(Dispatchers.IO) {
        val requestedAt = now()
        val cache = cacheFile(location)
        if (cache.isFresh(requestedAt)) readCacheOrNull(cache, location)?.let { return@withContext it }

        val end = requestedAt.atZone(ZoneOffset.UTC).toLocalDate().minusDays(SOURCE_LAG_DAYS)
        val start = end.minusYears(HISTORY_YEAR_COUNT).plusDays(1)
        try {
            val json = fetchText(url(location, start, end))
            currentCoroutineContext().ensureActive()
            val archive = parsePowerHistory(json, location, requestedAt.toEpochMilli())
            currentCoroutineContext().ensureActive()
            writeCache(cache, json, requestedAt.toEpochMilli())
            archive
        } catch (error: IOException) {
            readCacheOrNull(cache, location) ?: throw error
        } catch (error: JSONException) {
            readCacheOrNull(cache, location) ?: throw error
        }
    }

    private fun cacheFile(location: CzechLocation): File {
        val coordinates = String.format(Locale.US, "%.4f_%.4f", location.latitude, location.longitude)
        return File(cacheDirectory, "power_5y_$coordinates.json")
    }

    private fun File.isFresh(at: Instant): Boolean = isFile &&
        (at.toEpochMilli() - lastModified()) in 0..CACHE_TTL_MILLIS

    private fun readCacheOrNull(file: File, location: CzechLocation): HistoryArchive? = synchronized(CACHE_LOCK) {
        if (!file.isFile || file.length() > MAX_RESPONSE_BYTES) return@synchronized null
        try {
            parsePowerHistory(file.readText(Charsets.UTF_8), location, file.lastModified())
        } catch (_: IOException) {
            null
        } catch (_: JSONException) {
            null
        }
    }

    private fun writeCache(file: File, json: String, modifiedAtEpochMillis: Long) = synchronized(CACHE_LOCK) {
        if (!cacheDirectory.isDirectory && !cacheDirectory.mkdirs()) {
            throw IOException("History cache directory could not be created.")
        }
        cacheDirectory.listFiles { candidate ->
            candidate.isFile && candidate.name.startsWith(".power_") && candidate.extension == "tmp"
        }
            .orEmpty().forEach { Files.deleteIfExists(it.toPath()) }
        val temporary = Files.createTempFile(cacheDirectory.toPath(), ".power_", ".tmp")
        try {
            val bytes = json.toByteArray(Charsets.UTF_8)
            if (bytes.size > MAX_RESPONSE_BYTES) throw IOException("History response exceeds cache limit.")
            temporary.toFile().outputStream().use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            if (!temporary.toFile().setLastModified(modifiedAtEpochMillis)) {
                throw IOException("History cache timestamp could not be set.")
            }
            Files.move(temporary, file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
        cacheDirectory.listFiles { candidate ->
            candidate.isFile && candidate.name.startsWith("power_") && candidate.extension == "json"
        }
            .orEmpty()
            .sortedByDescending(File::lastModified)
            .drop(MAX_CACHE_FILES)
            .forEach { Files.deleteIfExists(it.toPath()) }
    }

    companion object {
        internal fun url(location: CzechLocation, start: LocalDate, end: LocalDate): String =
            "$BASE_URL?parameters=$PARAMETERS&community=AG" +
                "&longitude=${location.longitude}&latitude=${location.latitude}" +
                "&start=${start.format(DateTimeFormatter.BASIC_ISO_DATE)}" +
                "&end=${end.format(DateTimeFormatter.BASIC_ISO_DATE)}" +
                "&format=JSON&time-standard=UTC"

        private const val BASE_URL = "https://power.larc.nasa.gov/api/temporal/daily/point"
        private const val PARAMETERS =
            "T2M,T2M_MAX,T2M_MIN,PRECTOTCORR,RH2M,WS10M,ALLSKY_SFC_SW_DWN"
        private const val HISTORY_YEAR_COUNT = 5L
        private const val SOURCE_LAG_DAYS = 2L
        private const val CACHE_TTL_MILLIS = 24L * 60L * 60L * 1_000L
        private const val MAX_CACHE_FILES = 12
        private val CACHE_LOCK = Any()
    }
}

private fun requestPowerHistory(url: String): String {
    val connection = URL(url).openConnection() as HttpURLConnection
    return try {
        connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
        connection.readTimeout = READ_TIMEOUT_MILLIS
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("User-Agent", USER_AGENT)
        if (connection.responseCode !in 200..299) {
            throw IOException("NASA POWER returned HTTP ${connection.responseCode}.")
        }
        connection.inputStream.use { readLimited(it, MAX_RESPONSE_BYTES).toString(Charsets.UTF_8) }
    } finally {
        connection.disconnect()
    }
}

private const val CONNECT_TIMEOUT_MILLIS = 10_000
private const val READ_TIMEOUT_MILLIS = 30_000
private const val MAX_RESPONSE_BYTES = 2_000_000
private val USER_AGENT =
    "Selia-Weather/${BuildConfig.VERSION_NAME} (Android; https://github.com/Majkey25/Selia-Weather)"
