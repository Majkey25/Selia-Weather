package cz.majkey.pocasicesko.data

import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject
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
        assertNull(delhi.pressureHpa)
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

    @Test
    fun seaLevelPressureDoesNotUseTheAltimeterSetting() {
        val report = JSONArray(METAR_JSON).getJSONObject(0).put("slp", 1008.2)
        val observation = parseMetarCurrentConditions(JSONArray().put(report).toString()).single()
        assertEquals(1008.2, requireNotNull(observation.pressureHpa), 0.0)
        report.put("slp", JSONObject.NULL)
        assertNull(parseMetarCurrentConditions(JSONArray().put(report).toString()).single().pressureHpa)
    }

    @Test
    fun limitedHeightCloudReportsDoNotClaimZeroTotalCloudCover() {
        listOf("CLR" to null, "CAVOK" to null, "SKC" to 0, "OVC" to 100).forEach { (cover, expected) ->
            val report = JSONArray(METAR_JSON).getJSONObject(0).put("cover", cover)
            assertEquals(expected, parseMetarCurrentConditions(JSONArray().put(report).toString()).single().cloudCoverPercent)
        }
    }

    @Test
    fun parsesExplicitPresentWeatherWithoutInventingAmounts() {
        mapOf(
            "-DZ" to 51, "DZ" to 53, "+DZ" to 55, "-RA BR" to 61, "RA" to 63, "+RA" to 65,
            "-FZDZ" to 56, "FZDZ" to 57, "-FZRA" to 66, "+FZRA" to 67,
            "-SN" to 71, "SN" to 73, "+SN" to 75, "SHRA" to 81, "+SHRA" to 81, "-SHSN" to 85,
            "FG" to 45, "FZFG" to 48, "TSRA" to 95, "RA FG" to 63,
        ).forEach { (encoded, expected) ->
            val report = JSONArray(METAR_JSON).getJSONObject(0).put("wxString", encoded)
            val observation = parseMetarCurrentConditions(JSONArray().put(report).toString()).single()
            assertEquals(encoded, expected, observation.weatherCode)
            assertNull(observation.precipitation)
        }
    }

    @Test
    fun absentRecentVicinityUnknownAndMixedPhaseWeatherDoesNotBecomeRain() {
        listOf("", "VCSH", "VCTS", "RERA", "-RASN", "RA SN", "FZRA RA", "UP", "BR", "light drizzle").forEach { encoded ->
            val report = JSONArray(METAR_JSON).getJSONObject(0).put("wxString", encoded)
            val observation = parseMetarCurrentConditions(JSONArray().put(report).toString()).single()
            assertNull(encoded, observation.weatherCode)
            assertNull(observation.precipitation)
        }
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
