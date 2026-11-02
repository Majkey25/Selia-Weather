package cz.majkey.pocasicesko.data

import java.io.IOException
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.time.Instant
import java.time.format.DateTimeFormatterBuilder
import java.util.Collections
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.PI
import kotlin.math.ln
import kotlin.math.tan
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.w3c.dom.Element
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler

enum class NativeRadarMode { FORECAST, OBSERVED }

enum class NativeRadarSource(
    val providerName: String,
    val modelName: String,
    val accumulationHours: Int,
    val layer: String,
    val style: String,
) {
    GDPS("Environment and Climate Change Canada", "GDPS", 6, "GDPS_15km_Precip-Accum6h", "PRECIPMM-LINEAR"),
    EUROPE("Deutscher Wetterdienst", "ICON-EU", 1, "Icon-eu_reg00625_fd_sl_TOTPREC01H", "icon-eu_reg00625_fd_sl_totprec01h_lawa"),
    GLOBAL("Deutscher Wetterdienst", "ICON", 6, "Icon_reg025_fd_sl_TOTPREC06H", "icon_reg025_fd_sl_totprec06h_wmc_isoarea"),
    RAINVIEWER("RainViewer", "RainViewer", 0, "", ""),
}

data class NativeRadarBounds(val south: Double, val west: Double, val north: Double, val east: Double) {
    init {
        require(listOf(south, west, north, east).all(Double::isFinite) &&
            south >= -90 && north <= 90 && south < north && east > west && east - west <= 360) {
            "Invalid radar bounds."
        }
    }

    internal fun contains(viewport: NativeRadarBounds): Boolean {
        val west = ((viewport.west + 180) % 360 + 360) % 360 - 180
        return viewport.east - viewport.west < 360 && viewport.south >= south && viewport.north <= north &&
            west >= this.west && west + viewport.east - viewport.west <= east
    }
}

data class NativeRadarFrame(
    val validTime: Instant,
    val run: Instant?,
    val source: NativeRadarSource,
    val accumulationHours: Int = source.accumulationHours,
    val path: String? = null,
) {
    init {
        require(accumulationHours == source.accumulationHours &&
            if (source == NativeRadarSource.RAINVIEWER) run == null && path != null && RADAR_PATH.matches(path)
            else run != null && validTime > run && path == null) { "Invalid radar frame."
        }
    }
}

data class NativeRadarSnapshot(
    val mode: NativeRadarMode,
    val source: NativeRadarSource,
    val frames: List<NativeRadarFrame>,
    val coverage: NativeRadarBounds?,
    val loadedAt: Instant,
) {
    val horizon: Instant get() = frames.last().validTime
}

enum class NativeRadarFailure { NETWORK, INVALID_METADATA, STALE, UNAVAILABLE }

sealed interface NativeRadarResult {
    data class Success(val snapshot: NativeRadarSnapshot) : NativeRadarResult
    data class Failure(
        val source: NativeRadarSource,
        val reason: NativeRadarFailure,
        val message: String,
    ) : NativeRadarResult
}

class NativeRadarData internal constructor(
    private val fetchTimeoutMillis: Long = 20_000,
    private val fetchBytes: suspend (String, Int) -> ByteArray = ::requestNativeRadarBytes,
) {
    private val cache = mutableMapOf<NativeRadarSource, NativeRadarSnapshot>()

    suspend fun fetch(
        mode: NativeRadarMode,
        viewport: NativeRadarBounds,
        now: Instant = Instant.now(),
    ): NativeRadarResult {
        currentCoroutineContext().ensureActive()
        var source = if (mode == NativeRadarMode.OBSERVED) NativeRadarSource.RAINVIEWER else NativeRadarSource.GDPS
        return try {
            withTimeoutOrNull(fetchTimeoutMillis) {
                var probesRemaining = 4
                suspend fun verify(frame: NativeRadarFrame): Boolean {
                    requireAvailable(probesRemaining > 0)
                    probesRemaining--
                    return probe(frame)
                }
                var snapshot = try {
                    load(source, now, ::verify)
                } catch (error: Exception) {
                    currentCoroutineContext().ensureActive()
                    if (source != NativeRadarSource.GDPS || error is CancellationException && error !is TimeoutCancellationException) throw error
                    source = sourceFor(viewport)
                    load(source, now, ::verify)
                }
                if (source == NativeRadarSource.EUROPE && snapshot.coverage?.contains(viewport) != true) {
                    source = NativeRadarSource.GLOBAL
                    snapshot = load(source, now, ::verify)
                }
                NativeRadarResult.Success(snapshot)
            } ?: NativeRadarResult.Failure(source, NativeRadarFailure.NETWORK, "Radar update timed out.")
        } catch (_: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            NativeRadarResult.Failure(source, NativeRadarFailure.NETWORK, "Radar request timed out.")
        } catch (error: CancellationException) {
            throw error
        } catch (error: RadarDataException) {
            currentCoroutineContext().ensureActive()
            NativeRadarResult.Failure(source, error.reason, error.message.orEmpty())
        } catch (error: IOException) {
            currentCoroutineContext().ensureActive()
            NativeRadarResult.Failure(source, NativeRadarFailure.NETWORK, error.message.orEmpty())
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            NativeRadarResult.Failure(source, NativeRadarFailure.INVALID_METADATA, error.message.orEmpty())
        }
    }

    private suspend fun load(
        source: NativeRadarSource,
        now: Instant,
        verify: suspend (NativeRadarFrame) -> Boolean,
    ): NativeRadarSnapshot {
        synchronized(cache) { cache[source]?.usableAt(now) }?.let { return it }
        val snapshot = if (source == NativeRadarSource.RAINVIEWER) {
            parseNativeRadarObserved(metadata(RAINVIEWER_MANIFEST), now)
        } else {
            var xml = metadata(NativeRadarUrls.capabilities(source))
            val parsed = try {
                parseNativeRadarForecast(xml, source, now)
            } catch (error: RadarDataException) {
                if (error.reason != NativeRadarFailure.STALE || source == NativeRadarSource.GDPS) throw error
                xml = metadata(NativeRadarUrls.alternateCapabilities(source))
                parseNativeRadarForecast(xml, source, now)
            }
            val firstAvailable = verify(parsed.frames.first())
            if (firstAvailable && verify(parsed.frames.last())) parsed else {
                requireAvailable(source != NativeRadarSource.GDPS)
                val previous = try {
                    parseNativeRadarForecast(xml, source, now, beforeRun = parsed.frames.first().run)
                } catch (_: RadarDataException) {
                    null
                }
                if (previous != null) {
                    requireAvailable(verify(previous.frames.first()) && verify(previous.frames.last()))
                    previous
                } else {
                    // A publishing run may not have reached its advertised tail yet.
                    val frames = parsed.frames.take((parsed.frames.size + 1) / 2)
                    requireAvailable(firstAvailable && frames.size >= 3 &&
                        frames.last().validTime >= now.plusSeconds(MIN_FORECAST_SECONDS))
                    requireAvailable(verify(frames.last()))
                    parsed.copy(frames = immutableFrames(frames))
                }
            }
        }
        synchronized(cache) {
            cache[source] = snapshot
            if (cache.size > 3) cache.remove(cache.minBy { it.value.loadedAt }.key)
        }
        return snapshot
    }

    private suspend fun metadata(url: String): String = decodeRadarText(fetchBytes(url, MAX_RADAR_METADATA_BYTES))

    private suspend fun probe(frame: NativeRadarFrame): Boolean {
        val bytes = fetchBytes(NativeRadarUrls.wmsViewport(frame, PROBE_BOUNDS, 1, 1), MAX_RADAR_METADATA_BYTES)
        // The map renderer decodes the displayed image; these tiny probes reject WMS exception documents.
        return bytes.size >= 57 && bytes.take(8) == PNG_SIGNATURE.toList() &&
            bytes.copyOfRange(12, 16).contentEquals("IHDR".toByteArray(Charsets.US_ASCII)) &&
            ByteBuffer.wrap(bytes, 16, 8).let { it.int == 1 && it.int == 1 } &&
            bytes.copyOfRange(bytes.size - 8, bytes.size - 4).contentEquals("IEND".toByteArray(Charsets.US_ASCII))
    }

    companion object {
        fun sourceFor(viewport: NativeRadarBounds): NativeRadarSource =
            if (EUROPE_BOUNDS.contains(viewport)) NativeRadarSource.EUROPE else NativeRadarSource.GLOBAL
    }
}

object NativeRadarUrls {
    const val rainViewerCoverageTemplate = "$RAINVIEWER_HOST/v2/coverage/0/256/{z}/{x}/{y}/0/0_0.png"

    fun capabilities(source: NativeRadarSource): String = if (source == NativeRadarSource.GDPS) {
        "${endpoint(source)}?service=WMS&version=1.3.0&request=GetCapabilities&layer=${source.layer}"
    } else {
        "${endpoint(source).removeSuffix("/ows")}/wms?service=WMS&version=1.3.0&request=GetCapabilities"
    }

    internal fun alternateCapabilities(source: NativeRadarSource): String =
        "${endpoint(source)}?service=WMS&version=1.3.0&request=GetCapabilities"

    fun tileTemplate(frame: NativeRadarFrame): String = if (frame.source == NativeRadarSource.RAINVIEWER) {
        "$RAINVIEWER_HOST${frame.path}/512/{z}/{x}/{y}/2/1_1.png"
    } else {
        mapUrl(frame, "{bbox-epsg-3857}", 256, 256)
    }

    fun wmsViewport(frame: NativeRadarFrame, bounds: NativeRadarBounds, width: Int, height: Int): String {
        require(bounds.west >= -180 && bounds.east <= 180) { "Split radar viewports at the antimeridian."
        }
        val south = bounds.south.coerceAtLeast(-MERCATOR_LATITUDE)
        val north = bounds.north.coerceAtMost(MERCATOR_LATITUDE)
        require(south < north) { "Viewport is outside Web Mercator."
        }
        fun x(longitude: Double) = MERCATOR_RADIUS * longitude * PI / 180
        fun y(latitude: Double) = MERCATOR_RADIUS * ln(tan(PI / 4 + latitude * PI / 360))
        return mapUrl(frame, "${x(bounds.west)},${y(south)},${x(bounds.east)},${y(north)}", width, height)
    }

    fun legend(source: NativeRadarSource): String = "${endpoint(source)}?" + query(
        "service" to "WMS", "version" to "1.1.1", "request" to "GetLegendGraphic",
        "format" to "image/png", "layer" to wmsLayer(source), "style" to source.style,
    )

    private fun mapUrl(frame: NativeRadarFrame, bbox: String, width: Int, height: Int): String {
        require(frame.source != NativeRadarSource.RAINVIEWER && width in 1..2048 && height in 1..2048)
        // GeoMet rejects fractional seconds; DWD requires its millisecond timestamp spelling.
        fun time(value: Instant): String = if (frame.source == NativeRadarSource.GDPS) value.toString()
            else RADAR_TIME_FORMAT.format(value)
        return "${endpoint(frame.source)}?" + query(
            "service" to "WMS", "request" to "GetMap", "version" to "1.3.0",
            "layers" to wmsLayer(frame.source), "styles" to frame.source.style,
            "format" to "image/png", "transparent" to "true", "crs" to "EPSG:3857",
            "width" to width.toString(), "height" to height.toString(),
            "time" to time(frame.validTime),
            "dim_reference_time" to time(requireNotNull(frame.run)),
        ) + "&bbox=$bbox" + if (frame.source == NativeRadarSource.GDPS) "&interpolation=true" else ""
    }

    private fun endpoint(source: NativeRadarSource): String {
        require(source != NativeRadarSource.RAINVIEWER)
        if (source == NativeRadarSource.GDPS) return "https://geo.weather.gc.ca/geomet"
        return "https://maps.dwd.de/geoserver/dwd/${source.layer}/ows"
    }

    private fun wmsLayer(source: NativeRadarSource): String =
        if (source == NativeRadarSource.GDPS) source.layer else "dwd:${source.layer}"

    private fun query(vararg values: Pair<String, String>): String = values.joinToString("&") { (key, value) ->
        "$key=${URLEncoder.encode(value, Charsets.UTF_8.name())}"
    }
}

internal fun parseNativeRadarObserved(json: String, now: Instant): NativeRadarSnapshot {
    require(json.length <= MAX_RADAR_METADATA_BYTES)
    val root = JSONObject(json)
    require(root.getString("host") == RAINVIEWER_HOST) { "Unexpected RainViewer host."
    }
    val past = root.getJSONObject("radar").getJSONArray("past")
    require(past.length() > 0) { "No observed radar frames."
    }
    val frames = (maxOf(0, past.length() - 20) until past.length()).map { index ->
        val item = past.getJSONObject(index)
        val value = item.get("time") as? Number ?: error("Invalid radar timestamp.")
        val seconds = value.toLong()
        require(value.toDouble().isFinite() && value.toDouble() == seconds.toDouble()) { "Invalid radar timestamp."
        }
        val time = Instant.ofEpochSecond(seconds)
        require(time <= now.plusSeconds(300)) { "Future observed radar timestamp."
        }
        NativeRadarFrame(time, null, NativeRadarSource.RAINVIEWER, path = item.getString("path"))
    }
    require(frames.zipWithNext().all { (first, second) -> first.validTime < second.validTime }) {
        "Observed radar frames are not chronological."
    }
    requireFresh(frames.last().validTime >= now.minusSeconds(3600), "Stale observed radar frames.")
    return NativeRadarSnapshot(
        NativeRadarMode.OBSERVED, NativeRadarSource.RAINVIEWER,
        immutableFrames(frames.filter { it.validTime >= now.minusSeconds(7200) }), null, now,
    )
}

internal fun parseNativeRadarForecast(
    xml: String,
    source: NativeRadarSource,
    now: Instant,
    beforeRun: Instant? = null,
): NativeRadarSnapshot {
    require(source != NativeRadarSource.RAINVIEWER && xml.length <= MAX_RADAR_METADATA_BYTES &&
        !Regex("<!DOCTYPE|<!ENTITY", RegexOption.IGNORE_CASE).containsMatchIn(xml)) { "Invalid forecast XML."
    }
    val builder = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        isValidating = false
        isExpandEntityReferences = false
    }.newDocumentBuilder()
    builder.setEntityResolver { _, _ -> throw SAXException("External radar XML entities are not allowed.") }
    builder.setErrorHandler(object : DefaultHandler() {
        override fun error(error: org.xml.sax.SAXParseException) = throw error
        override fun fatalError(error: org.xml.sax.SAXParseException) = throw error
    })
    val root = builder.parse(InputSource(StringReader(xml))).documentElement
    require(root.localName == "WMS_Capabilities" && root.namespaceURI == "http://www.opengis.net/wms" &&
        root.getAttribute("version") == "1.3.0") { "Unexpected forecast capabilities."
    }
    val layers = root.getElementsByTagNameNS("*", "Layer")
    val layer = (0 until layers.length).map { layers.item(it) as Element }.single {
        it.radarChildren("Name").singleOrNull()?.textContent?.trim() in listOf(source.layer, "dwd:${source.layer}")
    }
    require(layer.radarChildren("Style").any {
        it.radarChildren("Name").singleOrNull()?.textContent?.trim() == source.style
    }) { "Unexpected forecast style."
    }
    fun dimension(name: String): Element = layer.radarChildren("Dimension").single {
        it.getAttribute("name") == name
    }.also { require(it.getAttribute("units") == "ISO8601") { "Invalid forecast dimension."
    } }
    if (source == NativeRadarSource.GDPS) {
        require(beforeRun == null)
        return parseGdpsRadarForecast(root, layer, dimension("reference_time"), dimension("time"), now)
    }
    val reference = dimension("REFERENCE_TIME")
    val defaultRun = radarInstant(reference.getAttribute("default"))
    val runs = reference.textContent.trim().split(',').map { radarInstant(it.trim()) }
    require(defaultRun in runs) { "Forecast run is absent from its domain."
    }
    requireFresh(defaultRun <= now && defaultRun >= now.minusSeconds(MAX_RUN_AGE_SECONDS), "Stale forecast run.")
    val run = if (source == NativeRadarSource.EUROPE || beforeRun != null) {
        runs.filter { it <= now && it >= now.minusSeconds(MAX_RUN_AGE_SECONDS) &&
            it.epochSecond % 21600 == 0L && (beforeRun == null || it < beforeRun) }
            .maxOrNull() ?: throw RadarDataException(NativeRadarFailure.STALE, "No fresh full-range forecast run.")
    } else defaultRun
    require(run.epochSecond % 21600 == 0L) { "Unsupported forecast cycle."
    }
    val range = dimension("time").textContent.trim().split('/')
    require(range.size == 3) { "Invalid forecast time range."
    }
    val start = radarInstant(range[0])
    val end = radarInstant(range[1])
    val periodHours = Regex("PT([1-6])H").matchEntire(range[2])?.groupValues?.get(1)?.toLong()
        ?: error("Invalid forecast time period.")
    val periodSeconds = periodHours * 3600
    require(end > start && end.epochSecond - start.epochSecond <= 14 * 86400 &&
        (end.epochSecond - start.epochSecond) % periodSeconds == 0L && source.accumulationHours % periodHours == 0L) {
        "Invalid forecast time domain."
    }
    val box = layer.radarChildren("BoundingBox").single { it.getAttribute("CRS") == "EPSG:4326" }
    fun bound(name: String): Double = box.getAttribute(name).toDouble()
    val coverage = NativeRadarBounds(bound("minx"), bound("miny"), bound("maxx"), bound("maxy"))
    require(coverage.west >= -180 && coverage.east <= 180)
    // DWD WMS exposes hourly EU totals through +78 h; ICON main cycles cover +180 h or +120 h.
    val runHours = if (source == NativeRadarSource.EUROPE) 78 else if (run.epochSecond % 43200 == 0L) 180 else 120
    val step = source.accumulationHours * 3600L
    val limit = minOf(end.epochSecond, run.epochSecond + runHours * 3600L)
    var time = maxOf((now.epochSecond / step + 1) * step, ((start.epochSecond + step - 1) / step) * step, run.epochSecond + step)
    val frames = mutableListOf<NativeRadarFrame>()
    while (time <= limit && frames.size < 80) {
        if ((time - start.epochSecond) % periodSeconds == 0L) frames += NativeRadarFrame(Instant.ofEpochSecond(time), run, source)
        time += step
    }
    requireAvailable(frames.size >= 3 && frames.first().validTime <= now.plusSeconds(step) &&
        frames.last().validTime >= now.plusSeconds(MIN_FORECAST_SECONDS))
    return NativeRadarSnapshot(NativeRadarMode.FORECAST, source, immutableFrames(frames), coverage, now)
}

private fun parseGdpsRadarForecast(
    root: Element,
    layer: Element,
    reference: Element,
    timeDimension: Element,
    now: Instant,
): NativeRadarSnapshot {
    require(layer.radarChildren("Title").singleOrNull()?.textContent?.trim() ==
        "GDPS - Precipitation - 6-hour accumulation [mm]") { "Unexpected GDPS accumulation units."
    }
    val domain = reference.textContent.trim().split('/')
    require(domain.size == 3 && domain[2] == "PT12H") { "Invalid GDPS reference domain."
    }
    val start = radarInstant(domain[0])
    val end = radarInstant(domain[1])
    val run = radarInstant(reference.getAttribute("default"))
    require(end >= start && end.epochSecond - start.epochSecond <= 7 * 86400 &&
        (end.epochSecond - start.epochSecond) % 43200 == 0L && run in start..end &&
        (run.epochSecond - start.epochSecond) % 43200 == 0L && run.epochSecond % 43200 == 0L) {
        "GDPS run is absent from its reference domain."
    }
    requireFresh(run <= now && run >= now.minusSeconds(MAX_GDPS_RUN_AGE_SECONDS), "Stale GDPS forecast run.")
    val times = timeDimension.textContent.trim().split(',').map { radarInstant(it.trim()) }
    require(timeDimension.getAttribute("nearestValue") == "0" && times.size in 3..80 &&
        times.zipWithNext().all { (first, second) -> first < second } && times.all {
            it > run && it <= run.plusSeconds(240 * 3600) && (it.epochSecond - run.epochSecond) % 21600 == 0L
        }) { "Invalid GDPS six-hour time domain."
    }
    val boxes = root.getElementsByTagNameNS("http://www.opengis.net/wms", "BoundingBox")
    require((0 until boxes.length).map { boxes.item(it) as Element }.any {
        it.getAttribute("CRS") == "EPSG:4326" && it.getAttribute("minx").toDoubleOrNull() == -90.0 &&
            it.getAttribute("miny").toDoubleOrNull() == -180.0 && it.getAttribute("maxx").toDoubleOrNull() == 90.0 &&
            it.getAttribute("maxy").toDoubleOrNull() == 180.0
    }) { "Missing GDPS global bounds."
    }
    val frames = times.filter { it > now }.map { NativeRadarFrame(it, run, NativeRadarSource.GDPS) }
    requireAvailable(frames.size >= 3 && frames.first().validTime <= now.plusSeconds(21600) &&
        frames.last().validTime >= now.plusSeconds(MIN_FORECAST_SECONDS))
    return NativeRadarSnapshot(NativeRadarMode.FORECAST, NativeRadarSource.GDPS, immutableFrames(frames), WORLD_BOUNDS, now)
}

private fun NativeRadarSnapshot.usableAt(now: Instant): NativeRadarSnapshot? {
    if (now < loadedAt || now >= loadedAt.plusSeconds(600)) return null
    val currentFrames = if (mode == NativeRadarMode.OBSERVED) {
        if (horizon < now.minusSeconds(3600) || horizon > now.plusSeconds(300)) return null
        frames.filter { it.validTime >= now.minusSeconds(7200) }
    } else {
        val run = frames.first().run ?: return null
        val maxRunAge = if (source == NativeRadarSource.GDPS) MAX_GDPS_RUN_AGE_SECONDS else MAX_RUN_AGE_SECONDS
        if (run > now || run < now.minusSeconds(maxRunAge) || horizon < now.plusSeconds(MIN_FORECAST_SECONDS)) return null
        frames.filter { it.validTime > now }
    }
    return copy(frames = immutableFrames(currentFrames))
}

private fun Element.radarChildren(name: String): List<Element> = (0 until childNodes.length)
    .mapNotNull { childNodes.item(it) as? Element }.filter { it.localName == name && it.namespaceURI == namespaceURI }

private fun radarInstant(value: String): Instant {
    require(value.endsWith('Z')) { "Expected UTC radar timestamp."
    }
    return Instant.parse(value).also { require(it.nano == 0 && it.epochSecond >= 0) { "Invalid radar timestamp."
    } }
}

private fun immutableFrames(frames: List<NativeRadarFrame>): List<NativeRadarFrame> =
    Collections.unmodifiableList(frames.toList())

private fun requireFresh(valid: Boolean, message: String) {
    if (!valid) throw RadarDataException(NativeRadarFailure.STALE, message)
}

private fun requireAvailable(valid: Boolean) {
    if (!valid) throw RadarDataException(NativeRadarFailure.UNAVAILABLE, "Forecast frames are unavailable for this model run.")
}

private class RadarDataException(val reason: NativeRadarFailure, message: String) : IOException(message)

private fun decodeRadarText(bytes: ByteArray): String {
    require(bytes.size <= MAX_RADAR_METADATA_BYTES) { "Radar metadata is too large."
    }
    return try {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: CharacterCodingException) {
        throw RadarDataException(NativeRadarFailure.INVALID_METADATA, "Invalid radar text encoding.")
    }
}

internal class NativeRadarHttpException(val status: Int) : IOException("Radar source returned HTTP $status.")

internal suspend fun retryNativeRadarRequest(request: suspend () -> ByteArray): ByteArray = withTimeoutOrNull(15_000) {
    try { request() } catch (error: IOException) {
        currentCoroutineContext().ensureActive()
        val transient = error is SocketTimeoutException || error is SocketException || error is UnknownHostException ||
            error is NativeRadarHttpException && error.status in listOf(500, 502, 503, 504)
        if (!transient) throw error
        delay(350)
        request()
    }
} ?: throw IOException("Radar request timed out.")

internal suspend fun requestNativeRadarBytes(url: String, maxBytes: Int): ByteArray = retryNativeRadarRequest {
    withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { continuation ->
            val address = URL(url)
            require(maxBytes in 1..4_000_000 && address.protocol == "https" && address.userInfo == null &&
                address.ref == null && address.port in listOf(-1, 443) &&
                address.host in listOf("geo.weather.gc.ca", "maps.dwd.de", "api.rainviewer.com", "tilecache.rainviewer.com")) {
                "Untrusted radar request."
            }
            val connection = address.openConnection() as HttpURLConnection
            continuation.invokeOnCancellation { connection.disconnect() }
            try {
                if (!continuation.isActive) return@suspendCancellableCoroutine
                connection.connectTimeout = 5_000
                connection.readTimeout = 8_000
                connection.instanceFollowRedirects = false
                connection.useCaches = false
                connection.setRequestProperty("Cache-Control", "no-cache")
                connection.setRequestProperty("Pragma", "no-cache")
                connection.setRequestProperty("Accept", "application/json, text/xml, image/png")
                if (connection.responseCode != HttpURLConnection.HTTP_OK) throw NativeRadarHttpException(connection.responseCode)
                if (connection.contentLengthLong > maxBytes) throw IOException("Radar response is too large.")
                val bytes = connection.inputStream.use { readLimited(it, maxBytes) }
                continuation.resume(bytes)
            } catch (error: Exception) {
                if (continuation.isActive) continuation.resumeWithException(error)
            } finally {
                connection.disconnect()
            }
        }
    }
}

internal const val MAX_RADAR_METADATA_BYTES = 131_072
private const val RAINVIEWER_HOST = "https://tilecache.rainviewer.com"
private const val RAINVIEWER_MANIFEST = "https://api.rainviewer.com/public/weather-maps.json"
private const val MAX_RUN_AGE_SECONDS = 12 * 3600L
// Allow one verified 12-hour GDPS cycle plus a bounded six-hour publication grace period.
private const val MAX_GDPS_RUN_AGE_SECONDS = 18 * 3600L
private const val MIN_FORECAST_SECONDS = 12 * 3600L
private const val MERCATOR_RADIUS = 6_378_137.0
private const val MERCATOR_LATITUDE = 85.0511287798066
private val EUROPE_BOUNDS = NativeRadarBounds(29.46875, -23.53125, 70.53125, 62.53125)
private val WORLD_BOUNDS = NativeRadarBounds(-90.0, -180.0, 90.0, 180.0)
private val PROBE_BOUNDS = NativeRadarBounds(47.0, 12.0, 53.0, 17.0)
private val RADAR_PATH = Regex("/v2/radar/[A-Za-z0-9_-]{1,128}")
private val PNG_SIGNATURE = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
private val RADAR_TIME_FORMAT = DateTimeFormatterBuilder().appendInstant(3).toFormatter()
