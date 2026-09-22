package cz.majkey.pocasicesko.data

import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.concurrent.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherWarningsTest {
    private val now = Instant.parse("2026-09-22T10:00:00Z")
    private val brno = CzechLocation("Brno", REGION_SOUTH_MORAVIAN, 49.1951, 16.6068, "CZ")

    @Test
    fun capUsesExactOrpPreferredLanguageAndIncludesIssuedFutureWarnings() {
        val xml = cap(
            info("cs", "6203", "Silný vítr") + info("en-GB", "6203", "Strong wind") +
                info("en-GB", "1100", "Prague only"),
        )
        val warning = parseChmiWarnings(xml, "6203", "en", now).single()
        assertEquals("Strong wind", warning.headline)
        assertEquals(Instant.parse("2026-09-22T11:00:00Z"), warning.onset)
        assertEquals("ČHMÚ", warning.source)
        assertEquals("Silný vítr", parseChmiWarnings(xml, "6203", "cs-CZ", now).single().headline)
        assertTrue(parseChmiWarnings(xml, "2101", "en", now).isEmpty())
    }

    @Test
    fun capDropsExplicitAllClearExpiredAndCancelledButKeepsRealMinorWarnings() {
        val clear = info("cs", "6203", "No warning").replace("Likely", "Unlikely").replace("Prepare", "None")
        val minor = info("cs", "6203", "Frost").replace("Moderate", "Minor")
        val expired = info("cs", "6203", "Expired").replace("2026-09-22T14:00:00Z", "2026-09-22T09:00:00Z")
        val warning = parseChmiWarnings(cap(clear + minor + expired), "6203", "cs", now).single()
        assertEquals("Frost", warning.headline)
        assertEquals(WeatherWarningSeverity.MINOR, warning.severity)
        assertTrue(parseChmiWarnings(cap(minor).replace("<msgType>Update", "<msgType>Cancel"), "6203", "cs", now).isEmpty())
    }

    @Test
    fun capRejectsDoctypesMalformedLocationsAndStaleSnapshots() {
        val xml = cap(info("cs", "6203", "Wind"))
        assertThrows(IllegalArgumentException::class.java) {
            parseChmiWarnings("<!DOCTYPE alert [<!ENTITY x SYSTEM 'file:///never-read'>]>$xml", "6203", "cs", now)
        }
        assertThrows(IllegalArgumentException::class.java) {
            parseChmiWarnings(xml.replace("CISORP", "UnknownCode"), "6203", "cs", now)
        }
        assertThrows(IllegalArgumentException::class.java) {
            parseChmiWarnings(xml, "6203", "cs", now.plusSeconds(3 * 24 * 60 * 60))
        }
        assertEquals(CHMI_WARNING_PAGE, safeWarningUrl("https://chmi.cz.evil.test/", CHMI_WARNING_PAGE, "chmi.cz"))
        assertEquals(CHMI_WARNING_PAGE, safeWarningUrl("javascript:alert(1)", CHMI_WARNING_PAGE, "chmi.cz"))
    }

    @Test
    fun capRequiresExpiryForLocalWarningsButAcceptsOfficialAllClearWithoutExpiry() {
        val noExpiry = info("cs", "6203", "Wind").replace("<expires>2026-09-22T14:00:00Z</expires>", "")
        assertThrows(IOException::class.java) { parseChmiWarnings(cap(noExpiry), "6203", "cs", now) }
        val clear = noExpiry.replace("Likely", "Unlikely").replace("Prepare", "None")
        assertTrue(parseChmiWarnings(cap(clear), "6203", "cs", now).isEmpty())
    }

    @Test
    fun nwsRemovesExpiredAndSupersededAlertsAndKeepsMinor() {
        val json = """{"type":"FeatureCollection","updated":"2026-09-22T10:00:00Z","features":[
            ${nws("old")},
            ${nws("updated", "Update", """, "references":[{"identifier":"old"}]""")},
            ${nws("expired").replace("2026-09-22T14:00:00Z", "2026-09-22T09:00:00Z")}
        ]}"""
        val warning = parseNwsWarnings(json, now).single()
        assertEquals("updated", warning.id)
        assertEquals(WeatherWarningSeverity.MINOR, warning.severity)
        assertEquals(NWS_WARNING_PAGE, warning.webUrl)
        assertThrows(IllegalArgumentException::class.java) {
            parseNwsWarnings(json, now.plusSeconds(3 * 24 * 60 * 60))
        }
    }

    @Test
    fun officialNumericMapResolvesRuianCodesWithoutGuessingNames() {
        val mapping = parseWarningOrpMapping(File("src/main/assets/csu-ruian-orp.csv").readText())
        assertEquals(206, mapping.size)
        assertEquals("6203", parseWarningOrpCode("""{"features":[{"attributes":{"kod":1317}}]}""", mapping))
        assertEquals("1100", mapping[19])
        assertThrows(IllegalArgumentException::class.java) { parseWarningOrpCode("""{"features":[]}""", mapping) }
        assertThrows(IllegalArgumentException::class.java) {
            parseWarningOrpCode("""{"features":[{"attributes":{"kod":19}},{"attributes":{"kod":1317}}]}""", mapping)
        }
        assertThrows(IOException::class.java) { parseWarningOrpCode("""{"features":[{"attributes":{"kod":1}}]}""", mapping) }
    }

    @Test
    fun repositoryCachesOneExactPointAndRechecksExpiryWithoutReturningStaleSuccess() = runBlocking {
        val requests = mutableListOf<String>()
        var fail = false
        val repository = WeatherWarningsRepository(mapOf(1317 to "6203")) { url ->
            requests.add(url)
            if (fail) throw IOException("Offline")
            if (url.contains("/query?")) """{"features":[{"attributes":{"kod":1317}}]}"""
            else cap(info("cs", "6203", "Wind"))
        }
        assertEquals(WeatherWarningsStatus.AVAILABLE, repository.fetch(brno, "cs", now).status)
        assertEquals(1, repository.fetch(brno, "cs", now.plusSeconds(20)).warnings.size)
        assertEquals(2, requests.size)
        repository.fetch(brno.copy(longitude = brno.longitude + 0.000001), "cs", now.plusSeconds(25))
        assertEquals(3, requests.size)
        assertTrue(requests.first().contains("geometry=16.6068%2C49.1951"))
        fail = true
        val failure = repository.fetch(brno, "cs", now.plusSeconds(61))
        assertEquals(WeatherWarningsStatus.UNAVAILABLE, failure.status)
        assertTrue(failure.warnings.isEmpty())
    }

    @Test
    fun repositoryDistinguishesUnsupportedAndUnavailableAndPropagatesCancellation() = runBlocking {
        val repository = WeatherWarningsRepository(emptyMap()) { throw AssertionError("No network expected") }
        val uk = repository.fetch(brno.copy(countryCode = "GB"), now = now)
        assertEquals(WeatherWarningsStatus.UNAVAILABLE, uk.status)
        assertTrue(uk.sourceUrl.startsWith("https://weather.metoffice.gov.uk/"))
        assertEquals(WeatherWarningsStatus.UNSUPPORTED, repository.fetch(brno.copy(countryCode = "JP"), now = now).status)
        val cancelled = WeatherWarningsRepository(emptyMap()) { throw CancellationException() }
        var propagated = false
        try {
            cancelled.fetch(brno.copy(countryCode = "US"), now = now)
        } catch (_: CancellationException) {
            propagated = true
        }
        assertTrue(propagated)
        assertFalse(WeatherWarningsRepository.nwsUrl(brno).contains("zone="))
    }

    @Test
    fun unknownCountryPinUsesVerifiedNwsCoverageAndCachesOnlyThatExactCoordinate() = runBlocking {
        val pin = CzechLocation("Pin", REGION_WORLD, 40.712812345, -74.006012345)
        val requests = mutableListOf<String>()
        val repository = WeatherWarningsRepository(emptyMap()) { url ->
            requests.add(url)
            if (url.contains("/points/")) nwsPoint()
            else """{"type":"FeatureCollection","updated":"2026-09-22T10:00:00Z","features":[]}"""
        }
        val result = repository.fetch(pin, now = now)
        assertEquals(WeatherWarningsStatus.AVAILABLE, result.status)
        assertEquals("National Weather Service", result.sourceName)
        assertTrue(result.warnings.isEmpty())
        assertEquals("https://api.weather.gov/points/40.7128,-74.0060", requests.first())
        assertTrue(requests.last().contains("point=40.712812345,-74.006012345"))
        repository.fetch(pin, now = now.plusSeconds(61))
        assertEquals(1, requests.count { it.contains("/points/") })
        repository.fetch(pin, now = now.plusSeconds(24 * 60 * 60))
        assertEquals(2, requests.count { it.contains("/points/") })
        val foreign = repository.fetch(CzechLocation("London pin", REGION_WORLD, 51.5074, -0.1278), now = now)
        assertEquals(WeatherWarningsStatus.UNAVAILABLE, foreign.status)
        assertTrue(foreign.warnings.isEmpty())
        assertTrue(requests.last().contains("/points/51.5074,-0.1278"))
    }

    @Test
    fun unknownCountryCoverageFailureOrUntrustedMetadataNeverBecomesAnAllClear() = runBlocking {
        val pin = CzechLocation("Pin", REGION_WORLD, 40.7128, -74.006)
        val offline = WeatherWarningsRepository(emptyMap()) { throw IOException("HTTP 404 or unavailable") }
        assertEquals(WeatherWarningsStatus.UNAVAILABLE, offline.fetch(pin, now = now).status)
        listOf(
            nwsPoint().replace("https://api.weather.gov/points/", "https://api.weather.gov.evil.test/points/"),
            nwsPoint().replace("https://api.weather.gov/zones/", "https://untrusted.test/zones/"),
            nwsPoint().replace("\"coordinates\":[-74.006,40.7128]", "\"coordinates\":[-0.1278,51.5074]"),
        ).forEach { payload ->
            val repository = WeatherWarningsRepository(emptyMap()) { payload }
            assertEquals(WeatherWarningsStatus.UNAVAILABLE, repository.fetch(pin, now = now).status)
        }
    }

    @Test
    fun unknownCountryCzechGpsRequiresExactOrpEvenWhenRegionIsWorld() = runBlocking {
        val requests = mutableListOf<String>()
        var insideCzechia = true
        val repository = WeatherWarningsRepository(mapOf(1317 to "6203")) { url ->
            requests.add(url)
            if (url.contains("/query?")) {
                if (insideCzechia) """{"features":[{"attributes":{"kod":1317}}]}""" else """{"features":[]}"""
            } else cap(info("cs", "6203", "Wind"))
        }
        val result = repository.fetch(brno.copy(countryCode = null, region = REGION_WORLD), "cs", now)
        assertEquals(WeatherWarningsStatus.AVAILABLE, result.status)
        assertEquals("ČHMÚ", result.sourceName)
        assertTrue(requests.first().startsWith("https://ags.cuzk.gov.cz/"))
        insideCzechia = false
        val foreign = repository.fetch(CzechLocation("Dresden", REGION_WORLD, 51.0504, 13.7373), now = now)
        assertEquals(WeatherWarningsStatus.UNAVAILABLE, foreign.status)
        assertTrue(foreign.warnings.isEmpty())
        assertTrue(requests.last().startsWith("https://ags.cuzk.gov.cz/"))
    }

    private fun nwsPoint(): String = """
        {"id":"https://api.weather.gov/points/40.7128,-74.006","type":"Feature",
        "geometry":{"type":"Point","coordinates":[-74.006,40.7128]},
        "properties":{"forecastZone":"https://api.weather.gov/zones/forecast/NYZ072"}}
    """.trimIndent()

    private fun cap(infos: String): String = """
        <alert xmlns="urn:oasis:names:tc:emergency:cap:1.2">
            <identifier>chmi-current</identifier><sender>chmi@chmi.cz</sender>
            <sent>2026-09-22T08:00:00Z</sent><status>Actual</status><msgType>Update</msgType><scope>Public</scope>
            $infos
        </alert>
    """.trimIndent()

    private fun info(language: String, orp: String, event: String): String = """
        <info><language>$language</language><event>$event</event><responseType>Prepare</responseType>
            <certainty>Likely</certainty><severity>Moderate</severity>
            <onset>2026-09-22T11:00:00Z</onset><expires>2026-09-22T14:00:00Z</expires>
            <headline>$event</headline><description>Provider text</description><instruction>Provider advice</instruction>
            <web>https://vystrahy-cr.chmi.cz/</web>
            <area><geocode><valueName>CISORP</valueName><value>$orp</value></geocode></area>
        </info>
    """.trimIndent()

    private fun nws(id: String, type: String = "Alert", additional: String = ""): String = """
        {"properties":{"id":"$id","status":"Actual","messageType":"$type","event":"Frost",
        "severity":"Minor","effective":"2026-09-22T08:00:00Z","expires":"2026-09-22T14:00:00Z",
        "web":"http://unsafe.test/"$additional}}
    """.trimIndent()
}
