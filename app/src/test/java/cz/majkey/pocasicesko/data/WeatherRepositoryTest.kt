package cz.majkey.pocasicesko.data

import java.net.SocketTimeoutException
import java.util.concurrent.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherRepositoryTest {
    @Test
    fun stageDiagnosticsMeasureMonotonicTimeWithoutLoggingResultsOrExceptionMessages() {
        val lines = mutableListOf<String>()
        var clock = 100L
        val privateData = "https://example.test/?latitude=50.12345&longitude=14.98765 raw-weather-payload"
        val result = loggedForecastStage(
            ForecastFetchStage.BEST_MATCH,
            monotonicMillis = { clock.also { clock += 25 } },
            writeLog = { lines.add(it) },
        ) { privateData }
        assertEquals(privateData, result)
        assertEquals("stage=BEST_MATCH elapsed_ms=25 outcome=ok error=- http_status=-", lines.single())
        val timeout = SocketTimeoutException(privateData)
        val caught = assertThrows(SocketTimeoutException::class.java) {
            loggedForecastStage(
                ForecastFetchStage.MODELS,
                monotonicMillis = { clock.also { clock += 50 } },
                writeLog = { lines.add(it) },
            ) { throw timeout }
        }
        assertSame(timeout, caught)
        assertEquals("stage=MODELS elapsed_ms=50 outcome=failed error=SocketTimeoutException http_status=-", lines.last())
        assertFalse(lines.joinToString().contains(privateData))
    }

    @Test
    fun stageDiagnosticsRecordKnownHttpStatusAndDoNotSwallowCancellation() {
        val lines = mutableListOf<String>()
        val httpError = WeatherHttpException(429)
        assertSame(httpError, assertThrows(WeatherHttpException::class.java) {
            loggedForecastStage(ForecastFetchStage.MODELS, { 0L }, { lines.add(it) }) { throw httpError }
        })
        assertEquals("stage=MODELS elapsed_ms=0 outcome=failed error=WeatherHttpException http_status=429", lines.last())
        val cancelled = CancellationException("Private cancellation context")
        assertSame(cancelled, assertThrows(CancellationException::class.java) {
            loggedForecastStage(ForecastFetchStage.METAR, { 0L }, { lines.add(it) }) { throw cancelled }
        })
        assertEquals("stage=METAR elapsed_ms=0 outcome=cancelled error=CancellationException http_status=-", lines.last())
    }

    @Test
    fun unavailableOptionalStagesAndBrokenLoggingPreserveResults() {
        val lines = mutableListOf<String>()
        assertEquals(null, loggedForecastStage<String?>(ForecastFetchStage.CALIBRATION, { 0L }, { lines.add(it) }) { null })
        assertEquals("stage=CALIBRATION elapsed_ms=0 outcome=unavailable error=- http_status=-", lines.single())
        assertEquals(emptyList<String>(), loggedForecastStage(ForecastFetchStage.CHMI, { 0L }, { lines.add(it) }) { emptyList<String>() })
        assertTrue(lines.last().contains("outcome=unavailable"))
        assertEquals(42, loggedForecastStage(ForecastFetchStage.BLEND, { 0L }, { error("Log failure") }) { 42 })
        val cancelled = CancellationException()
        assertSame(cancelled, assertThrows(CancellationException::class.java) {
            loggedForecastStage(ForecastFetchStage.METAR, { 0L }, { error("Log failure") }) { throw cancelled }
        })
        val fatal = AssertionError("Private fatal context")
        assertSame(fatal, assertThrows(AssertionError::class.java) {
            loggedForecastStage(ForecastFetchStage.BLEND, { 0L }, { lines.add(it) }) { throw fatal }
        })
        assertEquals("stage=BLEND elapsed_ms=0 outcome=failed error=- http_status=-", lines.last())
    }

    @Test
    fun requestsSevenPastDaysAndFourteenForecastDays() {
        val url = WeatherRepository.forecastUrl(CzechLocation("Praha", REGION_PRAGUE, 50.0755, 14.4378))

        assertTrue(url.contains("past_days=7"))
        assertTrue(url.contains("forecast_days=14"))
        assertTrue(url.contains("timezone=auto"))
        assertFalse(url.contains("Europe%2FPrague"))
        assertFalse(url.contains("models="))
        listOf(
            "uv_index",
            "freezing_level_height",
            "boundary_layer_height",
            "total_column_integrated_water_vapour",
            "lifted_index",
            "convective_inhibition",
            "soil_temperature_0cm",
            "soil_moisture_0_to_1cm",
            "showers",
            "uv_index_max",
        ).forEach { field -> assertTrue("Missing $field", url.contains(field)) }
    }

    @Test
    fun searchesAndParsesLocationsWorldwide() {
        val url = WeatherRepository.geocodingUrl("Berlin", "en")
        val results = parseLocationSearchResults(
            """
                {
                  "results":[
                    {"name":"Berlin","country_code":"DE","country":"Germany","admin1":"Berlin","latitude":52.52,"longitude":13.405},
                    {"name":"Praha","country_code":"CZ","country":"Czechia","admin1_id":3067695,"admin1":"Capital City of Prague","latitude":50.0755,"longitude":14.4378}
                  ]
                }
            """.trimIndent(),
        )

        assertFalse(url.contains("countryCode="))
        assertEquals(CzechLocation("Berlin", "Berlin", 52.52, 13.405, "DE"), results[0])
        assertEquals(CzechLocation("Praha", REGION_PRAGUE, 50.0755, 14.4378, "CZ"), results[1])
    }
}
