package cz.majkey.pocasicesko.data

import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PrecipitationFieldRepositoryTest {
    @Test
    fun requestsOneBoundedWorldwideField() = runBlocking {
        val location = CzechLocation("Praha", REGION_PRAGUE, 50.0755, 14.4378, "CZ")
        val points = precipitationFieldPoints(location)
        val models = forecastApiModelsFor(location)
        var requestedUrl = ""
        val repository = PrecipitationFieldRepository { url ->
            requestedUrl = url
            payload(points, models)
        }

        val field = repository.fetch(location)
        val query = URI(requestedUrl).rawQuery.split('&').associate { parameter ->
            val parts = parameter.split('=', limit = 2)
            parts[0] to URLDecoder.decode(parts[1], StandardCharsets.UTF_8)
        }

        assertEquals(1, field.frames.size)
        assertEquals(25, query.getValue("latitude").split(',').size)
        assertEquals(25, query.getValue("longitude").split(',').size)
        assertEquals("24", query["forecast_hours"])
        assertEquals("unixtime", query["timeformat"])
        assertEquals("GMT", query["timezone"])
        assertEquals("precipitation,rain,showers,snowfall", query["hourly"])
        assertEquals(models.joinToString(","), query["models"])
        assertTrue(requestedUrl.startsWith("https://api.open-meteo.com/v1/forecast?"))
    }

    @Test
    fun propagatesNetworkAndMalformedPayloadFailures() {
        val location = CzechLocation("Point", REGION_WORLD, 0.0, 0.0)
        val network = PrecipitationFieldRepository { throw IOException("offline") }
        val malformed = PrecipitationFieldRepository { "{}" }

        assertThrows(IOException::class.java) { runBlocking { network.fetch(location) } }
        assertThrows(JSONException::class.java) { runBlocking { malformed.fetch(location) } }
    }

    @Test
    fun spatialFieldUsesTheSharedNorthAmericanModelRoute() {
        val location = CzechLocation("New York", "New York", 40.7128, -74.006, "US")
        val models = forecastApiModelsFor(location)
        val url = PrecipitationFieldRepository.url(precipitationFieldPoints(location), models)

        assertTrue(url.contains("&models=${models.joinToString(",")}"))
        assertTrue(url.contains("gfs_seamless"))
        assertTrue(url.contains("gem_seamless"))
        assertFalse(url.contains("chmi_aladin_seamless"))
        assertFalse(url.contains("kma_seamless"))
    }

    private fun payload(
        points: List<PrecipitationFieldPoint>,
        models: List<String>,
    ): String = JSONArray().apply {
        points.forEachIndexed { index, point ->
            val units = JSONObject().put("time", "unixtime")
            val hourly = JSONObject().put("time", JSONArray(listOf(TIME)))
            VARIABLES.forEach { variable ->
                models.forEach { model ->
                    units.put("${variable}_$model", if (variable == "snowfall") "cm" else "mm")
                    hourly.put("${variable}_$model", JSONArray(listOf(0.0)))
                }
            }
            put(
                JSONObject()
                    .put("latitude", point.latitude)
                    .put("longitude", point.longitude)
                    .apply { if (index > 0) put("location_id", index) }
                    .put("hourly_units", units)
                    .put("hourly", hourly),
            )
        }
    }.toString()

    companion object {
        private const val TIME = 1_788_030_000L
        private val VARIABLES = listOf("precipitation", "rain", "showers", "snowfall")
    }
}
