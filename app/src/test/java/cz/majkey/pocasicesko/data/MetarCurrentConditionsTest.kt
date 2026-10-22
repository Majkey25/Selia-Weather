package cz.majkey.pocasicesko.data

import java.io.IOException
import java.time.Instant
import java.util.concurrent.CancellationException
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MetarCurrentConditionsTest {
    @Test
    fun obscuredSkyDoesNotInventACloudAmountOrFog() {
        for (cover in listOf("OVX", "VV")) {
            val report = JSONObject().put("icaoId", "WIOO").put("obsTime", 1792056600)
                .put("lat", -0.151).put("lon", 109.404).put("qcField", 16)
                .put("cover", cover).put("wxString", "FU")
            val observed = parseMetarCurrentConditions(JSONArray().put(report).toString()).single()
            assertTrue(observed.skyObscured)
            assertNull(observed.cloudCoverPercent)
            assertNull(observed.cloudCoverRange)
            assertNull(observed.weatherCode)
            assertNull(observed.precipitation)
        }
    }

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
    fun queryCoversNearbyHighLatitudeStationsAndKeepsEquatorialAreaSmall() {
        for (latitude in listOf(-75.0, 0.0, 75.0)) {
            val requested = mutableListOf<String>()
            MetarCurrentConditionsRepository { url -> requested.add(url); "[]" }
                .fetch(CzechLocation("Point", REGION_WORLD, latitude, 0.0))
            val (south, west, north, east) = bounds(requested.single())
            // At 75 degrees latitude, 1.7 degrees longitude is only 48.92 km away.
            val nearbyLongitude = if (latitude == 0.0) 0.44966 else 1.7
            assertTrue(west <= -nearbyLongitude && east >= nearbyLongitude)
            assertTrue(south <= latitude - 0.44966 && north >= latitude + 0.44966)
            assertTrue(north - south < 1.0)
            if (latitude == 0.0) assertTrue(east - west < 1.0)
        }
        for (latitude in listOf(-90.0, -89.8, 89.8, 90.0)) {
            val requested = mutableListOf<String>()
            MetarCurrentConditionsRepository { url -> requested.add(url); "[]" }
                .fetch(CzechLocation("Pole", REGION_WORLD, latitude, 40.0))
            val (south, west, north, east) = bounds(requested.single())
            assertEquals(-180.0, west, 0.0)
            assertEquals(180.0, east, 0.0)
            assertTrue(south >= -90.0 && north <= 90.0 && north - south < 1.0)
        }
    }

    @Test
    fun datelineQueriesCoverBothSidesAndKeepLatestReportPerStation() {
        for (longitude in listOf(-180.0, -179.9, 179.9, 180.0)) {
            val requested = mutableListOf<String>()
            val reports = JSONArray(METAR_JSON)
            val observations = MetarCurrentConditionsRepository { url ->
                requested.add(url)
                JSONArray().put(reports.getJSONObject(if (requested.size == 1) 1 else 0)).toString()
            }.fetch(CzechLocation("Dateline", REGION_WORLD, 0.0, longitude))
            assertEquals(2, requested.size)
            val boxes = requested.map(::bounds)
            for (point in listOf(-179.9, 179.9)) {
                assertTrue(boxes.any { (_, west, _, east) -> point in west..east })
            }
            assertTrue(boxes.all { (south, west, north, east) ->
                south < 0 && north > 0 && west >= -180 && east <= 180 && west < east && east - west < 1
            })
            assertEquals(1, observations.size)
            assertEquals(Instant.parse("2026-08-31T20:30:00Z"), observations.single().time)
        }
    }

    @Test
    fun datelinePartialFailureKeepsOtherResponseAndTotalFailureRemainsExplicit() {
        val location = CzechLocation("Dateline", REGION_WORLD, 0.0, 179.9)
        for (failure in listOf(IOException("Unavailable"), JSONException("Malformed response"))) {
            for (failedRequest in listOf(1, 2)) {
                var requests = 0
                val observations = MetarCurrentConditionsRepository {
                    requests++
                    if (requests == failedRequest) throw failure
                    METAR_JSON
                }.fetch(location)
                assertEquals(2, requests)
                assertEquals(listOf("VIDD", "VIDP"), observations.map { it.stationId })
            }
            assertThrows(failure.javaClass) {
                MetarCurrentConditionsRepository { throw failure }.fetch(location)
            }
        }
        var requests = 0
        assertThrows(CancellationException::class.java) {
            MetarCurrentConditionsRepository {
                requests++
                throw CancellationException("Cancelled")
            }.fetch(location)
        }
        assertEquals(1, requests)
    }

    private fun bounds(url: String): List<Double> =
        url.substringAfter("bbox=").substringBefore('&').split(',').map(String::toDouble)

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
    fun invalidObservationTimeKeepsOtherStationsAndPreviousValidReport() {
        val reports = JSONArray(METAR_JSON)
        reports.getJSONObject(0).put("obsTime", Double.MAX_VALUE)
        val observations = parseMetarCurrentConditions(reports.toString())

        assertEquals(listOf("VIDD", "VIDP"), observations.map { it.stationId })
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
            val observation = parseMetarCurrentConditions(JSONArray().put(report).toString()).single()
            assertEquals(expected, observation.cloudCoverPercent)
            if (expected == null) assertNull(observation.cloudCoverRange)
        }
    }

    @Test
    fun manualCloudCategoriesKeepNominalAmountsAndConservativeTotalCloudBounds() {
        listOf(
            "SKC" to (0 to (0..0)), "FEW" to (13 to (13..100)), "SCT" to (38 to (38..100)),
            "BKN" to (75 to (63..100)), "OVC" to (100 to (100..100)),
        ).forEach { (cover, expected) ->
            val report = JSONArray(METAR_JSON).getJSONObject(0).put("cover", cover)
            val observation = parseMetarCurrentConditions(JSONArray().put(report).toString()).single()
            assertEquals(cover, expected.first, observation.cloudCoverPercent)
            assertEquals(cover, expected.second, observation.cloudCoverRange)
        }
    }

    @Test
    fun automatedCloudReportsKeepLowerBoundsWithoutLosingPresentWeather() {
        listOf(
            "SKC" to (null to null), "FEW" to (13 to (13..100)), "SCT" to (38 to (38..100)),
            "BKN" to (75 to (63..100)), "OVC" to (100 to (100..100)), "VV" to (null to null),
            "CLR" to (null to null), "CAVOK" to (null to null),
        ).forEach { (cover, expected) ->
            val report = JSONArray(METAR_JSON).getJSONObject(0)
                .put("cover", cover)
                .put("rawOb", "METAR VIDP 312030Z AUTO 25005KT 9999 -RA $cover 30/23 Q1003")
                .put("wxString", "-RA")
            val observation = parseMetarCurrentConditions(JSONArray().put(report).toString()).single()
            assertEquals(cover, expected.first, observation.cloudCoverPercent)
            assertEquals(cover, expected.second, observation.cloudCoverRange)
            assertEquals(61, observation.weatherCode)
            assertEquals(30.0, requireNotNull(observation.temperature), 0.0)
        }
    }

    @Test
    fun automaticMarkerMustBeAnExactWhitespaceDelimitedToken() {
        listOf("RMK AUTOTEST" to 0, "\tAUTO\n" to null).forEach { (marker, expected) ->
            val report = JSONArray(METAR_JSON).getJSONObject(0)
                .put("cover", "SKC")
                .put("rawOb", "METAR VIDP 312030Z $marker 25005KT SKC 30/23 Q1003")
            val observation = parseMetarCurrentConditions(JSONArray().put(report).toString()).single()
            assertEquals(marker, expected, observation.cloudCoverPercent)
        }
    }

    @Test
    fun mixedRainAndSnowKeepsBothPhasesWithoutInventingAmounts() {
        mapOf(
            "-RASN" to 68, "-SNRA" to 68, "-RA -SN" to 68, "-SN -RA" to 68,
            "RASN" to 69, "SNRA" to 69, "+RASN" to 69, "+SNRA" to 69,
            "RA SN" to 69, "SN RA" to 69, "-RA SN" to 69, "-RASN VCSH" to 68,
            "-DZSN" to 68, "SNDZ" to 69, "-DZ -SN" to 68,
            "RASN TS" to 95, "TS -SNRA" to 95, "RASN TSGR" to 96,
        ).forEach { (encoded, expected) ->
            val report = JSONArray(METAR_JSON).getJSONObject(0).put("wxString", encoded)
            val observation = parseMetarCurrentConditions(JSONArray().put(report).toString()).single()
            assertEquals(encoded, expected, observation.weatherCode)
            assertTrue(encoded, hasPrecipitationEvidence(expected))
            assertEquals(encoded, if (expected >= 95) WeatherKind.STORM else WeatherKind.SNOW,
                conditionFor(expected).kind)
            assertNull(encoded, observation.precipitation)
        }
    }

    @Test
    fun mixedShowersKeepBothPhasesAndIntensityWithoutInventingAmounts() {
        mapOf(
            "-SHRASN" to 83, "-SHSNRA" to 83, "SHRASN" to 84, "SHSNRA" to 84,
            "+SHRASN" to 84, "+SHSNRA" to 84, "-SHRA -SHSN" to 83,
            "-SHSN -SHRA" to 83, "SHRA SHSN" to 84, "-RA -SHSN" to 83,
            "-SHRASN VCSH" to 83, "SHRASN TS" to 95, "TSGR SHRASN" to 96,
        ).forEach { (encoded, expected) ->
            val report = JSONArray(METAR_JSON).getJSONObject(0).put("wxString", encoded)
            val observation = parseMetarCurrentConditions(JSONArray().put(report).toString()).single()
            assertEquals(encoded, expected, observation.weatherCode)
            assertTrue(encoded, hasPrecipitationEvidence(expected))
            assertNull(encoded, observation.precipitation)
        }
        for (encoded in listOf("SHRASN UP", "-FZSHRASN", "SHRASN FZRA", "SHRAUNKNOWN", "RESH RASN")) {
            val report = JSONArray(METAR_JSON).getJSONObject(0).put("wxString", encoded)
            assertNull(encoded, parseMetarCurrentConditions(JSONArray().put(report).toString()).single().weatherCode)
        }
    }

    @Test
    fun parsesExplicitPresentWeatherWithoutInventingAmounts() {
        mapOf(
            "-DZ" to 51, "DZ" to 53, "+DZ" to 55, "-RA BR" to 61, "RA" to 63, "+RA" to 65,
            "-DZRA" to 58, "DZRA" to 59, "+DZRA" to 59,
            "-RADZ" to 58, "RADZ" to 59, "+RADZ" to 59,
            "-FZDZ" to 56, "FZDZ" to 57, "-FZRA" to 66, "+FZRA" to 67,
            "-SN" to 71, "SN" to 73, "+SN" to 75, "SHRA" to 81, "+SHRA" to 81, "-SHSN" to 85,
            "FG" to 45, "FZFG" to 48, "TSRA" to 95, "RA FG" to 63,
            "BCFG" to 45, "MIFG" to 45, "PRFG" to 45, "-RA BCFG" to 61,
            "TSSN" to 95, "TSSN SN" to 95, "TSGR" to 96,
            "-TSRAGR" to 96, "TSRAGR" to 96, "+TSRAGR" to 96,
        ).forEach { (encoded, expected) ->
            val report = JSONArray(METAR_JSON).getJSONObject(0).put("wxString", encoded)
            val observation = parseMetarCurrentConditions(JSONArray().put(report).toString()).single()
            assertEquals(encoded, expected, observation.weatherCode)
            assertNull(observation.precipitation)
        }
    }

    @Test
    fun preservesExplicitLocalWeatherBesideDocumentedNonlocalGroups() {
        mapOf(
            "-RA VCSH" to 61, "VCSH -RA" to 61, "-RA BR VCTS" to 61,
            "-SN VCBLSN" to 71, "FG VCFG" to 45, "RA VCBLSA" to 63,
            "RA VCBLDU" to 63, "RA VCPO" to 63, "RA VCSS" to 63, "RA VCDS" to 63,
            "SHRA RERA" to 81, "-SHRA RETS" to 80, "-TSRA RESHRA" to 95,
            "-RA RESN" to 61, "-SN REFZRA" to 71, "-RA REFZDZ" to 61,
            "-RA REDZ" to 61, "-RA RESHSN" to 61, "-RA RETSRA" to 61,
        ).forEach { (encoded, expected) ->
            val report = JSONArray(METAR_JSON).getJSONObject(0).put("wxString", encoded)
            val observation = parseMetarCurrentConditions(JSONArray().put(report).toString()).single()
            assertEquals(encoded, expected, observation.weatherCode)
            assertNull(encoded, observation.precipitation)
        }
    }

    @Test
    fun nonlocalGroupsNeverSupplyLocalWeatherOrBypassUnknownAndMixedPhaseGuards() {
        listOf(
            "VCSH RETS", "VCFG", "VCBLSN", "VCBLSA", "VCBLDU", "VCPO", "VCSS", "VCDS",
            "REDZ", "RESN", "REFZRA", "REFZDZ", "RESHRA", "RESHSN", "RETSRA",
            "-RA VCUNKNOWN", "-RA REUNKNOWN", "-RA +VCSH", "-RA VCSHRA",
            "-RA RE-RA", "-RA REFG", "-RA UP VCSH", "RASN FZRA",
            "FZRA RA RETS", "-FZRASN VCSH", "-RA VCSH BR HZ",
            "TSRA SN", "TSSN RA", "TSSN FZRA", "+TSRAGR SN",
            "TSGR UP", "-RA BCUNKNOWN", "TSSNRA", "TSUNKNOWN",
            "TSRAGR SN", "-TSRAGR FZRA", "TSRAGR UP", "VCTSRAGR",
            "DZRA FZSN", "RADZ FZRA", "-DZRA UP", "REDZRA", "VCDZRA",
        ).forEach { encoded ->
            val report = JSONArray(METAR_JSON).getJSONObject(0).put("wxString", encoded)
            val observation = parseMetarCurrentConditions(JSONArray().put(report).toString()).single()
            assertNull(encoded, observation.weatherCode)
            assertNull(encoded, observation.precipitation)
        }
    }

    @Test
    fun absentRecentVicinityUnknownAndMixedPhaseWeatherDoesNotBecomeRain() {
        listOf("", "VCSH", "VCTS", "RERA", "-FZRASN", "RA FZSN", "FZRA RA", "UP", "BR", "light drizzle").forEach { encoded ->
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
