package cz.majkey.pocasicesko.data

import java.io.IOException
import java.io.StringReader
import java.net.URI
import java.time.Duration
import java.time.Instant
import kotlin.math.abs
import javax.xml.parsers.DocumentBuilderFactory
import org.json.JSONObject
import org.w3c.dom.Element
import org.xml.sax.InputSource
import org.xml.sax.SAXException

enum class WeatherWarningSeverity { UNKNOWN, MINOR, MODERATE, SEVERE, EXTREME }

data class WeatherWarning(
    val id: String,
    val source: String,
    val headline: String,
    val description: String,
    val instruction: String,
    val severity: WeatherWarningSeverity,
    val onset: Instant?,
    val expires: Instant?,
    val webUrl: String,
)

enum class WeatherWarningsStatus { AVAILABLE, UNAVAILABLE, UNSUPPORTED }

data class WeatherWarningsResult(
    val status: WeatherWarningsStatus,
    val warnings: List<WeatherWarning>,
    val checkedAt: Instant,
    val sourceName: String,
    val sourceUrl: String,
)

internal fun parseChmiWarnings(
    xml: String,
    orpCode: String,
    language: String,
    now: Instant,
): List<WeatherWarning> {
    // Reject declarations before parsing; Android and desktop JAXP support different security flags.
    require(xml.length <= MAX_WARNING_RESPONSE_BYTES && !xml.contains("<!DOCTYPE") && !xml.contains("<!ENTITY")) {
        "Invalid warning XML."
    }
    val builder = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        isValidating = false
        isExpandEntityReferences = false
    }.newDocumentBuilder()
    builder.setEntityResolver { _, _ -> throw SAXException("External XML entities are not allowed.") }
    val alert = builder.parse(InputSource(StringReader(xml))).documentElement
    require(alert.localName == "alert" && alert.namespaceURI == CAP_NAMESPACE) { "Expected CAP alert." }
    val identifier = alert.requiredText("identifier")
    val sent = parseWeatherInstant(alert.requiredText("sent"))
    requireCurrentWarningFeed(sent, now)
    require(alert.text("status") == "Actual" && alert.text("scope") == "Public") { "Not a public actual alert." }
    val messageType = alert.requiredText("msgType")
    if (messageType == "Cancel") return emptyList()
    require(messageType == "Alert" || messageType == "Update") { "Unexpected CAP message type." }
    val infos = alert.children("info")
    require(infos.isNotEmpty()) { "CAP information is missing." }
    val preferred = if (language.startsWith("cs", ignoreCase = true)) "cs" else "en"
    val selectedLanguage = infos.firstOrNull { it.text("language")?.startsWith(preferred) == true }
        ?.text("language") ?: infos.first().text("language")
    return infos.filter { it.text("language") == selectedLanguage }.mapNotNull { info ->
        // CHMI publishes explicit green blocks. Minor by itself is a real warning in other CAP profiles.
        if (info.text("certainty") == "Unlikely" || info.children("responseType").any { it.textContent == "None" }) {
            return@mapNotNull null
        }
        val codes = info.children("area").flatMap { area ->
            area.children("geocode").filter { it.text("valueName") == "CISORP" }.map { it.requiredText("value") }
        }
        require(codes.isNotEmpty()) { "CHMI warning has no supported location code." }
        if (orpCode !in codes) return@mapNotNull null
        val effective = info.text("effective")?.let(::parseWeatherInstant) ?: sent
        val onset = info.text("onset")?.let(::parseWeatherInstant)
        val expires = parseWeatherInstant(info.requiredText("expires"))
        if (effective > now || expires <= now) return@mapNotNull null
        val event = info.requiredText("event")
        val eventCode = info.children("eventCode").firstOrNull { it.text("valueName") == "SIVS" }
            ?.text("value") ?: event
        WeatherWarning(
            id = "$identifier:$eventCode:${onset ?: sent}:$orpCode",
            source = "ČHMÚ",
            headline = info.text("headline") ?: event,
            description = info.text("description").orEmpty(),
            instruction = info.text("instruction").orEmpty(),
            severity = warningSeverity(info.requiredText("severity")),
            onset = onset,
            expires = expires,
            webUrl = safeWarningUrl(info.text("web"), CHMI_WARNING_PAGE, "chmi.cz"),
        )
    }.distinctBy { it.id }.sortedByDescending { it.severity.ordinal }
}

internal fun parseNwsWarnings(json: String, now: Instant): List<WeatherWarning> {
    val root = JSONObject(json)
    require(root.getString("type") == "FeatureCollection") { "Expected an NWS feature collection." }
    requireCurrentWarningFeed(parseWeatherInstant(root.getString("updated")), now)
    val features = root.getJSONArray("features")
    val properties = (0 until features.length()).map { features.getJSONObject(it).getJSONObject("properties") }
    val superseded = buildSet {
        properties.filter { it.optString("status") == "Actual" && it.optString("messageType") in listOf("Update", "Cancel") }
            .forEach { warning ->
                val references = warning.optJSONArray("references") ?: return@forEach
                for (index in 0 until references.length()) {
                    val reference = references.getJSONObject(index)
                    reference.optionalText("identifier")?.let(::add)
                    reference.optionalText("@id")?.let(::add)
                }
            }
    }
    return properties.mapNotNull { warning ->
        if (warning.optString("status") != "Actual" || warning.optString("messageType") == "Cancel") return@mapNotNull null
        val id = warning.getString("id").also { require(it.isNotBlank()) }
        if (id in superseded || warning.optionalText("@id") in superseded) return@mapNotNull null
        val expires = parseWeatherInstant(warning.getString("expires"))
        val ends = warning.optionalText("ends")?.let(::parseWeatherInstant)
        val effective = parseWeatherInstant(warning.getString("effective"))
        if (effective > now || expires <= now || (ends != null && ends <= now)) return@mapNotNull null
        WeatherWarning(
            id = id,
            source = warning.optionalText("senderName") ?: "National Weather Service",
            headline = warning.optionalText("headline") ?: warning.getString("event"),
            description = warning.optionalText("description").orEmpty(),
            instruction = warning.optionalText("instruction").orEmpty(),
            severity = warningSeverity(warning.getString("severity")),
            onset = warning.optionalText("onset")?.let(::parseWeatherInstant),
            expires = minOf(expires, ends ?: expires),
            webUrl = safeWarningUrl(warning.optionalText("web"), NWS_WARNING_PAGE, "weather.gov"),
        )
    }.distinctBy { it.id }.sortedByDescending { it.severity.ordinal }
}

internal fun parseWarningOrpCode(json: String, mapping: Map<Int, String>): String {
    val features = JSONObject(json).getJSONArray("features")
    require(features.length() == 1) { "Location must resolve to exactly one ORP." }
    val ruianCode = features.getJSONObject(0).getJSONObject("attributes").getInt("kod")
    return mapping[ruianCode] ?: throw IOException("The ORP code is not in the official conversion table.")
}

internal fun validateNwsWarningPoint(json: String, location: CzechLocation) {
    val root = JSONObject(json)
    require(root.getString("type") == "Feature") { "Expected an NWS point feature." }
    val point = URI(root.getString("id"))
    val zone = URI(root.getJSONObject("properties").getString("forecastZone"))
    for (uri in listOf(point, zone)) {
        require(uri.scheme == "https" && uri.host == "api.weather.gov" && uri.userInfo == null &&
            (uri.port == -1 || uri.port == 443) && uri.query == null && uri.fragment == null) { "Untrusted NWS coverage metadata." }
    }
    require(point.path.startsWith("/points/") && zone.path.matches(Regex("/zones/forecast/[A-Z]{2}Z[0-9]{3}"))) {
        "Missing NWS point or forecast zone."
    }
    val pointCoordinates = point.path.removePrefix("/points/").split(',')
    require(pointCoordinates.size == 2) { "Invalid NWS point identifier." }
    val geometry = root.getJSONObject("geometry")
    require(geometry.getString("type") == "Point") { "NWS coverage has no point geometry." }
    val coordinates = geometry.getJSONArray("coordinates")
    require(coordinates.length() == 2) { "Invalid NWS point geometry." }
    val latitude = coordinates.getDouble(1)
    val longitude = coordinates.getDouble(0)
    // NWS redirects point lookups to four decimal places; alert queries keep the original coordinates.
    require(abs(latitude - location.latitude) <= 0.000051 && abs(longitude - location.longitude) <= 0.000051 &&
        abs(pointCoordinates[0].toDouble() - latitude) < 0.000001 &&
        abs(pointCoordinates[1].toDouble() - longitude) < 0.000001) { "NWS coverage is for another coordinate." }
}

internal fun parseWarningOrpMapping(csv: String): Map<Int, String> = buildMap {
    csv.lineSequence().drop(1).filter { it.isNotBlank() }.forEach { row ->
        val fields = row.split(';')
        require(fields.size == 2 && fields[1].length == 4 && fields[1].all(Char::isDigit)) { "Invalid ORP mapping." }
        require(put(fields[0].toInt(), fields[1]) == null) { "Duplicate ORP code." }
    }
    require(isNotEmpty()) { "The ORP conversion table is empty." }
}

internal fun safeWarningUrl(value: String?, fallback: String, domain: String): String {
    val uri = try { value?.let(::URI) } catch (_: Exception) { null }
    return if (uri?.scheme == "https" && uri.userInfo == null && (uri.port == -1 || uri.port == 443) &&
        (uri.host == domain || uri.host?.endsWith(".$domain") == true)
    ) uri.toString() else fallback
}

private fun requireCurrentWarningFeed(updated: Instant, now: Instant) {
    require(Duration.between(updated, now).seconds in -300..MAX_WARNING_FEED_AGE_SECONDS) { "Warning feed is stale or future-dated." }
}

private fun warningSeverity(value: String): WeatherWarningSeverity =
    WeatherWarningSeverity.entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: WeatherWarningSeverity.UNKNOWN

private fun Element.children(name: String): List<Element> = (0 until childNodes.length)
    .mapNotNull { childNodes.item(it) as? Element }
    .filter { it.localName == name && it.namespaceURI == CAP_NAMESPACE }

private fun Element.text(name: String): String? = children(name).firstOrNull()?.textContent?.trim()?.takeIf { it.isNotEmpty() }

private fun Element.requiredText(name: String): String = text(name) ?: throw IOException("Missing CAP $name.")

private fun JSONObject.optionalText(name: String): String? = if (isNull(name)) null else optString(name).trim().takeIf { it.isNotEmpty() }

internal const val CHMI_WARNING_PAGE = "https://vystrahy-cr.chmi.cz/"
internal const val NWS_WARNING_PAGE = "https://www.weather.gov/alerts"
internal const val MAX_WARNING_RESPONSE_BYTES = 4_000_000
private const val CAP_NAMESPACE = "urn:oasis:names:tc:emergency:cap:1.2"
// An old provider snapshot must never turn an outage into a current all-clear.
private const val MAX_WARNING_FEED_AGE_SECONDS = 48 * 60 * 60L
