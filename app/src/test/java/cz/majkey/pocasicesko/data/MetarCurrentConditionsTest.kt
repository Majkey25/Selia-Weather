package cz.majkey.pocasicesko.data

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MetarCurrentConditionsTest {
    @Test
    fun parsesLatestReportPerStationWithDocumentedUnits() {
        val observations = parseMetarCurrentConditions(METAR_JSON)

        assertEquals(listOf("VIDD", "VIDP"), observations.map { it.stationId })
        val delhi = observations.last()
        assertEquals(Instant.parse("2026-08-31T20:30:00Z"), delhi.time)
        assertEquals(30.0, requireNotNull(delhi.temperature), 0.0)
        assertEquals(23.0, requireNotNull(delhi.dewPoint), 0.0)
        assertEquals(66, delhi.humidity)
        assertEquals(9.26, requireNotNull(delhi.windSpeed), 0.0001)
        assertEquals(250.0, requireNotNull(delhi.windDirection), 0.0)
        assertEquals(4_506.1632, requireNotNull(delhi.visibilityMeters), 0.0001)
        assertEquals(1_003.0, requireNotNull(delhi.pressureHpa), 0.0)
        assertEquals(75, delhi.cloudCoverPercent)
        assertNull(delhi.precipitation)
        assertNull(delhi.sunshineSeconds)
    }

    @Test
    fun repositoryUsesBoundedWorldwideQuery() {
        var requestedUrl = ""
        val repository = MetarCurrentConditionsRepository { url ->
            requestedUrl = url
            METAR_JSON
        }

        val observations = repository.fetch(
            CzechLocation("Delhi", "Delhi", 28.6139, 77.209, "IN"),
        )

        assertEquals(2, observations.size)
        assertTrue(requestedUrl.startsWith("https://aviationweather.gov/api/data/metar?bbox="))
        assertTrue(requestedUrl.contains("&format=json&hours=2"))
    }

    @Test
    fun rejectsNonPhysicalReportAndKeepsPreviousValidStationReport() {
        val observations = parseMetarCurrentConditions(
            METAR_JSON.replaceFirst("\"temp\":30", "\"temp\":130"),
        )

        val delhi = observations.last { it.stationId == "VIDP" }
        assertEquals(29.0, requireNotNull(delhi.temperature), 0.0)
        assertEquals(Instant.parse("2026-08-31T20:00:00Z"), delhi.time)
    }

    companion object {
        private val METAR_JSON = """
            [
              {"icaoId":"VIDP","obsTime":1788208200,"reportTime":"2026-08-31T20:30:00.000Z","temp":30,"dewp":23,"wdir":250,"wspd":5,"visib":2.8,"altim":1003,"qcField":16,"lat":28.567,"lon":77.117,"cover":"BKN"},
              {"icaoId":"VIDP","obsTime":1788206400,"reportTime":"2026-08-31T20:00:00.000Z","temp":29,"dewp":22,"wdir":260,"wspd":4,"visib":2.8,"altim":1002,"qcField":16,"lat":28.567,"lon":77.117,"cover":"SCT"},
              {"icaoId":"VIDD","obsTime":1788206400,"reportTime":"2026-08-31T20:00:00.000Z","temp":30,"dewp":27,"wdir":"VRB","wspd":2,"visib":2.49,"altim":1003,"qcField":16,"lat":28.583,"lon":77.211,"cover":"SCT"}
            ]
        """.trimIndent()
    }
}
