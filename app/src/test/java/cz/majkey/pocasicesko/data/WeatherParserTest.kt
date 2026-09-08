package cz.majkey.pocasicesko.data

import org.json.JSONException
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class WeatherParserTest {
    @Test
    fun optionalPrecipitationSpreadPreservesLegacyForecastsAndRawHourAlignment() {
        val legacy = WeatherParser.parseForecast(VALID_FORECAST, 123L)
        assertEquals(null, legacy.hourly[0].precipitationSpread)
        val root = JSONObject(VALID_FORECAST)
        val spread = JSONObject("""{"model_count":3,"wet_model_count":1,"minimum_mm":0,"maximum_mm":0.03}""")
        root.getJSONObject("hourly").put(PRECIPITATION_SPREAD_KEY, JSONArray().put(JSONObject.NULL).put(spread))
        val parsed = WeatherParser.parseForecast(root.toString(), 123L)
        assertEquals(null, parsed.hourly[0].precipitationSpread)
        assertEquals(PrecipitationModelSpread(3, 1, 0.0, 0.03), parsed.hourly[1].precipitationSpread)
        assertEquals(legacy.hourly[1], parsed.hourly[1].copy(precipitationSpread = null))
        assertEquals(legacy.current, parsed.current)
        assertEquals(legacy.daily, parsed.daily)
        root.getJSONObject("hourly").getJSONArray("temperature_2m").put(0, JSONObject.NULL)
        val skipped = WeatherParser.parseForecast(root.toString(), 123L)
        assertEquals(1, skipped.hourly.size)
        assertEquals(parsed.hourly[1], skipped.hourly.single())
    }

    @Test
    fun malformedOptionalPrecipitationSpreadDoesNotRejectTheForecast() {
        val valid = """{"model_count":3,"wet_model_count":1,"minimum_mm":0,"maximum_mm":0.03}"""
        val malformed = listOf(
            JSONObject.NULL, "not an object", JSONObject(),
            JSONObject(valid).put("model_count", 2), JSONObject(valid).put("model_count", 33),
            JSONObject(valid).put("model_count", 3.5), JSONObject(valid).put("model_count", "3"),
            JSONObject(valid).put("model_count", 4_294_967_299L),
            JSONObject(valid).put("wet_model_count", -1), JSONObject(valid).put("wet_model_count", 4),
            JSONObject(valid).put("wet_model_count", 1.5), JSONObject(valid).put("wet_model_count", true),
            JSONObject(valid).put("minimum_mm", -0.1), JSONObject(valid).put("minimum_mm", 0.04),
            JSONObject(valid).put("minimum_mm", "NaN"), JSONObject(valid).put("maximum_mm", "Infinity"),
            JSONObject(valid).put("wet_model_count", 0), JSONObject(valid).put("wet_model_count", 3),
            JSONObject(valid).put("minimum_mm", 0.01), JSONObject(valid).put("maximum_mm", 0),
        )
        val legacy = WeatherParser.parseForecast(VALID_FORECAST, 123L)
        malformed.forEach { value ->
            val root = JSONObject(VALID_FORECAST)
            root.getJSONObject("hourly").put(PRECIPITATION_SPREAD_KEY, JSONArray().put(value).put(JSONObject.NULL))
            assertEquals(legacy, WeatherParser.parseForecast(root.toString(), 123L))
        }
        listOf(JSONArray(), JSONArray().put(JSONObject(valid)), JSONArray().put(JSONObject(valid)).put(JSONObject.NULL).put(JSONObject.NULL), JSONObject(valid))
            .forEach { wrongLength ->
                val root = JSONObject(VALID_FORECAST)
                root.getJSONObject("hourly").put(PRECIPITATION_SPREAD_KEY, wrongLength)
                assertEquals(legacy, WeatherParser.parseForecast(root.toString(), 123L))
            }
    }

    @Test
    fun preservesResponseOffsetAndAcceptsLegacyCacheWithoutOffset() {
        assertEquals(null, WeatherParser.parseForecast(VALID_FORECAST, 123L).utcOffsetSeconds)
        val forecast = JSONObject(VALID_FORECAST).put("utc_offset_seconds", 19_800).toString()
        assertEquals(19_800, WeatherParser.parseForecast(forecast, 123L).utcOffsetSeconds)
    }

    @Test
    fun rejectsInvalidResponseOffsets() {
        for (offset in listOf(19_800.5, 64_801, -64_801, "3600", 4_294_967_296L)) {
            val forecast = JSONObject(VALID_FORECAST).put("utc_offset_seconds", offset).toString()
            assertThrows(JSONException::class.java) { WeatherParser.parseForecast(forecast, 123L) }
        }
    }

    @Test
    fun parsesForecastShape() {
        val snapshot = WeatherParser.parseForecast(VALID_FORECAST, updatedAtEpochMillis = 123L)

        assertEquals("Europe/Prague", snapshot.timezone)
        assertEquals(19.1, snapshot.current.temperature, 0.0)
        assertEquals(2, snapshot.hourly.size)
        assertEquals(1, snapshot.daily.size)
        assertEquals(123L, snapshot.updatedAtEpochMillis)
    }

    @Test
    fun parsesCalculationProvenanceFromCachedForecastJson() {
        val snapshot = WeatherParser.parseForecast(
            VALID_FORECAST.withCalculation(CALCULATION),
            updatedAtEpochMillis = 123L,
        )
        val calculation = requireNotNull(snapshot.calculation)

        assertEquals(ForecastRegion.EUROPE, calculation.region)
        assertEquals(ForecastCalculationMode.DIAGNOSTIC_MEDIAN, calculation.mode)
        assertEquals(listOf("a", "b", "c", "d"), calculation.requestedModelIds)
        assertEquals(listOf("a", "b", "c"), calculation.contributorIds)
        assertEquals(null, calculation.fallbackReason)
    }

    @Test
    fun rejectsCalculationContributorOutsideRequestedModels() {
        val invalid = CALCULATION.replace("[\"a\",\"b\",\"c\"]", "[\"a\",\"b\",\"x\"]")

        assertThrows(JSONException::class.java) {
            WeatherParser.parseForecast(VALID_FORECAST.withCalculation(invalid), 123L)
        }
    }

    @Test
    fun rejectsDiagnosticCalculationBelowThreeContributors() {
        val invalid = CALCULATION.replace("[\"a\",\"b\",\"c\"]", "[\"a\",\"b\"]")

        assertThrows(JSONException::class.java) {
            WeatherParser.parseForecast(VALID_FORECAST.withCalculation(invalid), 123L)
        }
    }

    @Test
    fun rejectsMalformedCalculationModelId() {
        val invalid = CALCULATION.replace("[\"a\",\"b\",\"c\",\"d\"]", "[\"a\",\"bad id\",\"c\",\"d\"]")

        assertThrows(JSONException::class.java) {
            WeatherParser.parseForecast(VALID_FORECAST.withCalculation(invalid), 123L)
        }
    }

    @Test
    fun roundTripsBestMatchCalculationMetadata() {
        val calculation = ForecastCalculation(
            region = ForecastRegion.NORTH_AMERICA,
            mode = ForecastCalculationMode.BEST_MATCH,
            requestedModelIds = listOf("a", "b", "c"),
            contributorIds = emptyList(),
            fallbackReason = ForecastFallbackReason.PROVIDER_UNAVAILABLE,
        )

        assertEquals(
            calculation,
            JSONObject().putForecastCalculation(calculation).forecastCalculationOrNull(),
        )
    }

    @Test
    fun roundTripsCalibratedCalculationMetadata() {
        val calculation = ForecastCalculation(
            region = ForecastRegion.AFRICA,
            mode = ForecastCalculationMode.CALIBRATED,
            requestedModelIds = listOf("a", "b", "c"),
            contributorIds = listOf("a", "b"),
            fallbackReason = null,
            artifactVersion = 2,
            artifactGeneratedAtEpochSeconds = 1_788_000_000L,
            truthClass = CalibrationTruthClass.STATION,
            weights = mapOf("a" to 0.4, "b" to 0.6),
        )

        assertEquals(
            calculation,
            JSONObject().putForecastCalculation(calculation).forecastCalculationOrNull(),
        )
    }

    @Test
    fun rejectsMismatchedHourlyArrays() {
        val broken = VALID_FORECAST.replace(
            "\"temperature_2m\":[19.0,20.0]",
            "\"temperature_2m\":[19.0]",
        )

        assertThrows(JSONException::class.java) {
            WeatherParser.parseForecast(broken, updatedAtEpochMillis = 123L)
        }
    }

    @Test
    fun skipsPartialHourlyRowsWithoutInventingZeroValues() {
        val partial = VALID_FORECAST.replace(
            "\"temperature_2m\":[19.0,20.0]",
            "\"temperature_2m\":[null,20.0]",
        )

        val snapshot = WeatherParser.parseForecast(partial, updatedAtEpochMillis = 123L)

        assertEquals(1, snapshot.hourly.size)
        assertEquals("2026-08-24T13:00", snapshot.hourly.single().time)
        assertEquals(20.0, snapshot.hourly.single().temperature, 0.0)
    }

    @Test
    fun rejectsForecastWhenEveryHourlyRowIsPartial() {
        val partial = VALID_FORECAST.replace(
            "\"temperature_2m\":[19.0,20.0]",
            "\"temperature_2m\":[null,null]",
        )

        assertThrows(JSONException::class.java) {
            WeatherParser.parseForecast(partial, updatedAtEpochMillis = 123L)
        }
    }

    @Test
    fun parsesAdvancedWeatherDetailsAndKeepsLegacyFieldsOptional() {
        val advanced = VALID_FORECAST
            .replace(
                "\"is_day\":1",
                """"is_day":1,"dew_point_2m":8.1,"wet_bulb_temperature_2m":13.2,
                    "rain":0.2,"snowfall":0.0,"snow_depth_water_equivalent":0.0,
                    "cloud_cover_low":10,"cloud_cover_mid":20,"cloud_cover_high":30,
                    "visibility":24000.0,"surface_pressure":985.2,"cape":120.0,
                    "vapour_pressure_deficit":0.7,"surface_temperature":20.4,
                    "uv_index":3.4,"freezing_level_height":2450.0,
                    "boundary_layer_height":820.0,"total_column_integrated_water_vapour":18.2,
                    "lifted_index":-1.5,"convective_inhibition":42.0,
                    "soil_temperature_0cm":21.3,"soil_moisture_0_to_1cm":0.24,
                    "showers":0.7""".replace("\n", ""),
            )
            .replace(
                "\"is_day\":[1,1]",
                """"is_day":[1,1],"dew_point_2m":[8.1,8.2],
                    "apparent_temperature":[18.2,19.0],"wet_bulb_temperature_2m":[13.2,13.5],
                    "rain":[0.2,0.0],"snowfall":[0.0,0.0],
                    "snow_depth_water_equivalent":[0.0,0.0],"cloud_cover_low":[10,11],
                    "cloud_cover_mid":[20,21],"cloud_cover_high":[30,31],
                    "visibility":[24000.0,25000.0],"surface_pressure":[985.2,984.9],
                    "wind_gusts_10m":[16.2,17.0],"cape":[120.0,140.0],
                    "vapour_pressure_deficit":[0.7,0.8],"surface_temperature":[20.4,21.1],
                    "et0_fao_evapotranspiration":[0.1,0.2],"uv_index":[3.4,3.6],
                    "freezing_level_height":[2450.0,2500.0],
                    "boundary_layer_height":[820.0,850.0],
                    "total_column_integrated_water_vapour":[18.2,18.4],
                    "lifted_index":[-1.5,-1.2],"convective_inhibition":[42.0,40.0],
                    "soil_temperature_0cm":[21.3,21.5],
                    "soil_moisture_0_to_1cm":[0.24,0.25],"showers":[0.7,0.0]""".replace("\n", ""),
            )
            .replace(
                "\"wind_speed_10m_max\":[16.6]",
                """"wind_speed_10m_max":[16.6],"apparent_temperature_max":[21.0],
                    "apparent_temperature_min":[9.0],"daylight_duration":[50000.0],
                    "sunshine_duration":[32000.0],"rain_sum":[0.2],"snowfall_sum":[0.0],
                    "precipitation_hours":[1.0],"wind_gusts_10m_max":[24.0],
                    "wind_direction_10m_dominant":[45],"shortwave_radiation_sum":[18.4],
                    "et0_fao_evapotranspiration":[2.8],"uv_index_max":[5.2]""".replace("\n", ""),
            )

        val snapshot = WeatherParser.parseForecast(advanced, updatedAtEpochMillis = 123L)

        assertEquals(8.1, requireNotNull(snapshot.current.dewPoint), 0.0)
        assertEquals(24000.0, requireNotNull(snapshot.current.visibilityMeters), 0.0)
        assertEquals(16.2, requireNotNull(snapshot.hourly.first().windGusts), 0.0)
        assertEquals(0.1, requireNotNull(snapshot.hourly.first().et0), 0.0)
        assertEquals(50000.0, requireNotNull(snapshot.daily.first().daylightDurationSeconds), 0.0)
        assertEquals(24.0, requireNotNull(snapshot.daily.first().windGustsMax), 0.0)
        assertEquals(3.4, requireNotNull(snapshot.current.uvIndex), 0.0)
        assertEquals(2450.0, requireNotNull(snapshot.current.freezingLevelHeightMeters), 0.0)
        assertEquals(820.0, requireNotNull(snapshot.current.boundaryLayerHeightMeters), 0.0)
        assertEquals(18.2, requireNotNull(snapshot.current.integratedWaterVapour), 0.0)
        assertEquals(-1.5, requireNotNull(snapshot.current.liftedIndex), 0.0)
        assertEquals(42.0, requireNotNull(snapshot.current.convectiveInhibition), 0.0)
        assertEquals(21.3, requireNotNull(snapshot.current.soilTemperature0Cm), 0.0)
        assertEquals(0.24, requireNotNull(snapshot.current.soilMoisture0To1Cm), 0.0)
        assertEquals(0.7, requireNotNull(snapshot.current.showers), 0.0)
        assertEquals(3.4, requireNotNull(snapshot.hourly.first().uvIndex), 0.0)
        assertEquals(5.2, requireNotNull(snapshot.daily.first().uvIndexMax), 0.0)
        assertEquals(null, WeatherParser.parseForecast(VALID_FORECAST, 123L).current.dewPoint)
    }

    @Test
    fun rejectsNonFiniteCoreAndOptionalNumbersAcrossAllForecastSections() {
        val fields = listOf(
            "current" to "temperature_2m",
            "current" to "dew_point_2m",
            "hourly" to "temperature_2m",
            "hourly" to "dew_point_2m",
            "daily" to "temperature_2m_max",
            "daily" to "wind_gusts_10m_max",
        )
        fields.forEach { (section, field) ->
            listOf("NaN", "Infinity", "-Infinity").forEach { value ->
                val root = JSONObject(VALID_FORECAST)
                val target = root.getJSONObject(section)
                if (section == "current") {
                    target.put(field, value)
                } else {
                    val values = org.json.JSONArray(List(target.getJSONArray("time").length()) { 20.0 })
                    target.put(field, values.put(0, value))
                }

                assertThrows("$section.$field=$value", JSONException::class.java) {
                    WeatherParser.parseForecast(root.toString(), updatedAtEpochMillis = 123L)
                }
            }
        }
    }

    @Test
    fun keepsMissingDetailNullAndRejectsPresentNonFiniteDetail() {
        assertEquals(null, WeatherParser.parseForecast(VALID_FORECAST, 123L).current.uvIndex)
        val nonFinite = VALID_FORECAST.replace("\"is_day\":1", "\"is_day\":1,\"uv_index\":\"NaN\"")

        assertThrows(JSONException::class.java) {
            WeatherParser.parseForecast(nonFinite, updatedAtEpochMillis = 123L)
        }
    }

    @Test
    fun mapsWeatherCodesToTypedConditions() {
        assertEquals(WeatherConditionKey.CLEAR_DAY, conditionFor(0, true).key)
        assertEquals(WeatherConditionKey.DRIZZLE, conditionFor(51, true).key)
        assertEquals(WeatherConditionKey.SHOWERS, conditionFor(80, true).key)
        assertEquals(WeatherConditionKey.STORM, conditionFor(95, true).key)
        assertEquals(WeatherConditionKey.UNKNOWN, conditionFor(999, true).key)
    }

    companion object {
        private fun String.withCalculation(calculation: String): String =
            JSONObject(this).put("_selia_calculation", JSONObject(calculation)).toString()

        private val CALCULATION = """
            {
              "schema_version":1,
              "region":"EUROPE",
              "mode":"DIAGNOSTIC_MEDIAN",
              "requested_model_ids":["a","b","c","d"],
              "contributor_ids":["a","b","c"],
              "fallback_reason":null
            }
        """.trimIndent()

        private val VALID_FORECAST = """
            {
              "timezone":"Europe/Prague",
              "current":{
                "time":"2026-08-24T12:00",
                "temperature_2m":19.1,
                "apparent_temperature":18.2,
                "relative_humidity_2m":48,
                "precipitation":0.0,
                "weather_code":0,
                "cloud_cover":2,
                "pressure_msl":1023.0,
                "wind_speed_10m":8.3,
                "wind_direction_10m":34,
                "wind_gusts_10m":16.2,
                "is_day":1
              },
              "hourly":{
                "time":["2026-08-24T12:00","2026-08-24T13:00"],
                "temperature_2m":[19.0,20.0],
                "relative_humidity_2m":[48,46],
                "precipitation_probability":[0,5],
                "precipitation":[0.0,0.0],
                "weather_code":[0,1],
                "pressure_msl":[1023.0,1022.5],
                "wind_speed_10m":[8.3,9.0],
                "wind_direction_10m":[34,40],
                "is_day":[1,1]
              },
              "daily":{
                "time":["2026-08-24"],
                "weather_code":[1],
                "temperature_2m_max":[22.2],
                "temperature_2m_min":[10.9],
                "sunrise":["2026-08-24T06:05"],
                "sunset":["2026-08-24T20:03"],
                "precipitation_sum":[0.0],
                "precipitation_probability_max":[5],
                "wind_speed_10m_max":[16.6]
              }
            }
        """.trimIndent()
    }
}
