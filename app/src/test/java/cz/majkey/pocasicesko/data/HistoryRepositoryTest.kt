package cz.majkey.pocasicesko.data

import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.json.JSONException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryRepositoryTest {
    private val location = CzechLocation("Praha", REGION_PRAGUE, 50.0755, 14.4378, "CZ")
    private val now = Instant.parse("2026-08-30T12:00:00Z")

    @Test
    fun requests365DaysOnceThenUsesBoundedCache() = runBlocking {
        val cache = Files.createTempDirectory("history-cache").toFile()
        var calls = 0
        var requestedUrl = ""
        val repository = HistoryRepository(cache, fetchText = { url ->
            calls++
            requestedUrl = url
            SAMPLE_JSON
        }, now = { now })

        assertEquals(2, repository.fetch(location).days.size)
        assertEquals(2, repository.fetch(location).days.size)
        assertEquals(1, calls)
        val query = URI(requestedUrl).rawQuery.split('&').associate { parameter ->
            val parts = parameter.split('=', limit = 2)
            parts[0] to URLDecoder.decode(parts[1], StandardCharsets.UTF_8)
        }
        assertEquals("20250829", query["start"])
        assertEquals("20260828", query["end"])
        assertEquals("UTC", query["time-standard"])
        assertEquals("JSON", query["format"])
        assertTrue(query.getValue("parameters").contains("PRECTOTCORR"))
        assertEquals(1, cache.listFiles()?.size)
    }

    @Test
    fun fallsBackToStaleValidCacheWhenRefreshFails() = runBlocking {
        val cache = Files.createTempDirectory("history-cache").toFile()
        HistoryRepository(cache, fetchText = { SAMPLE_JSON }, now = { now }).fetch(location)
        cache.listFiles().orEmpty().single().setLastModified(now.minusSeconds(172_800).toEpochMilli())
        val offline = HistoryRepository(
            cache,
            fetchText = { throw IOException("offline") },
            now = { now },
        )

        assertEquals(2, offline.fetch(location).days.size)
    }

    @Test
    fun propagatesNetworkAndMalformedPayloadWithoutCache() {
        val networkCache = Files.createTempDirectory("history-network").toFile()
        val malformedCache = Files.createTempDirectory("history-malformed").toFile()

        assertThrows(IOException::class.java) {
            runBlocking {
                HistoryRepository(networkCache, fetchText = { throw IOException("offline") }, now = { now })
                    .fetch(location)
            }
        }
        assertThrows(JSONException::class.java) {
            runBlocking {
                HistoryRepository(malformedCache, fetchText = { "{}" }, now = { now }).fetch(location)
            }
        }
    }

    private companion object {
        val SAMPLE_JSON = """
            {
              "properties": {"parameter": {
                "T2M": {"20260101": -0.77, "20260102": -0.96},
                "T2M_MAX": {"20260101": 0.57, "20260102": 1.34},
                "T2M_MIN": {"20260101": -2.76, "20260102": -3.66},
                "PRECTOTCORR": {"20260101": 0.0, "20260102": 0.66},
                "RH2M": {"20260101": 85.81, "20260102": 85.93},
                "WS10M": {"20260101": 9.29, "20260102": 9.78},
                "ALLSKY_SFC_SW_DWN": {"20260101": 1.0, "20260102": 2.03}
              }},
              "header": {"api": {"version": "v2.9.7"}, "fill_value": -999.0}
            }
        """.trimIndent()
    }
}
