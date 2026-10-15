package cz.majkey.pocasicesko.data

import androidx.test.platform.app.InstrumentationRegistry
import cz.majkey.pocasicesko.R
import cz.majkey.pocasicesko.ui.labelResource
import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class WeatherParserAndroidTest {
    @Test
    fun partialCloudReportDoesNotBecomeAFullSkyMeasurementInNativeCache() {
        // Actual AWC LKKU report; the model sky is a synthetic regression counterexample.
        val report = parseMetarCurrentConditions("""[{"icaoId":"LKKU","obsTime":1791802800,
            "lat":49.042,"lon":17.442,"qcField":1,"cover":"FEW",
            "rawOb":"METAR LKKU 121100Z COR 29005KT 220V340 9999 FEW045 14/05 Q1021"}]""").single()
        val root = JSONObject(FORECAST)
        root.getJSONObject("current").put("weather_code", 3).put("cloud_cover", 85)
        val model = WeatherParser.parseForecast(root.toString(), 1L).current
        val fused = fuseCurrentConditions(model,
            CzechLocation("LKKU", REGION_WORLD, report.latitude, report.longitude, "CZ"),
            listOf(report), report.time.plusSeconds(300))
        val reloaded = WeatherParser.parseForecast(
            applyCurrentConditionsToForecastJson(root.toString(), fused), 1L).current
        assertEquals(model, reloaded)
        assertEquals(null, reloaded.skyObservationTime)
    }

    @Test
    fun obscuredSkySurvivesNativeCacheWithoutInventingCloudsOrHighConfidence() {
        val report = parseMetarCurrentConditions("""[{"icaoId":"WIOO","obsTime":1792056600,
            "temp":30,"dewp":26,"wdir":270,"wspd":8,"visib":0.93,"qcField":16,
            "lat":-0.151,"lon":109.404,"wxString":"FU","cover":"OVX",
            "rawOb":"METAR WIOO 150930Z 27008KT 1500 FU VV005 30/26 Q1007 NOSIG"}]""").single()
        val root = JSONObject(FORECAST).put("timezone", "Asia/Pontianak").put("utc_offset_seconds", 25200)
        root.getJSONObject("current").put("time", "2026-10-15T16:30")
            .put("weather_code", 0).put("cloud_cover", 0)
        val model = WeatherParser.parseForecast(root.toString(), 1L).current
        val fused = fuseCurrentConditions(model,
            CzechLocation("WIOO", REGION_WORLD, report.latitude, report.longitude, "ID"),
            listOf(report), report.time.plusSeconds(60))
        val reloaded = WeatherParser.parseForecast(applyCurrentConditionsToForecastJson(root.toString(), fused), 1L).current
        assertEquals(SKY_OBSCURED_WEATHER_CODE, reloaded.weatherCode)
        assertEquals(model.cloudCover, reloaded.cloudCover)
        assertEquals(model.precipitation, reloaded.precipitation, 0.0)
        assertEquals(report.time, reloaded.skyObservationTime)
        assertEquals("2026-10-15T16:30", reloaded.skyConditionTime(25200))
        val condition = conditionFor(reloaded.weatherCode)
        assertEquals(R.string.condition_sky_obscured, condition.labelResource())
        assertEquals(WeatherKind.UNKNOWN, condition.kind)
        val agreement = HourlyModelAgreement(5, 0.0, 0.0, 0.0, 5, 0, 0.0, 0.0, WeatherKind.CLEAR)
        val hour = WeatherParser.parseForecast(root.toString(), 1L).hourly.single()
            .copy(time = "2026-10-15T16:00", weatherCode = 0, modelAgreement = agreement)
        val result = hourlyForecastConfidence(hour, hour.copy(time = "2026-10-15T17:00"),
            java.time.LocalDateTime.parse("2026-10-15T16:31"), 0, condition,
            shownConditionTime = reloaded.skyConditionTime(25200))
        assertEquals(ConfidenceLevel.LIMITED, result.level)
        assertEquals(ConfidenceReason.UNKNOWN_CONDITION, result.reason)
        assertEquals(emptyList<ForecastDisagreement>(), result.disagreements)
    }

    @Test
    fun actualMixedRainSnowReportSurvivesFusionAndCacheWithoutInventingAmounts() {
        // AWC CYKL, 2026-10-15 09:33Z; the modeled dry state is a regression counterexample.
        val json = """[{"icaoId":"CYKL","obsTime":1792056780,"temp":0,"dewp":-1,
            "wdir":280,"wspd":10,"visib":9,"qcField":2,"wxString":"-SN -RA",
            "rawOb":"SPECI CYKL 150933Z AUTO 28010G16KT 9SM -SNRA FEW021 BKN027 BKN035 OVC096 00/M01 A2955 RMK SLP036",
            "lat":54.803,"lon":-66.804,"cover":"OVC"}]"""
        val report = parseMetarCurrentConditions(json).single()
        val root = JSONObject(FORECAST)
        root.getJSONObject("current").put("weather_code", 0).put("cloud_cover", 0)
        val model = WeatherParser.parseForecast(root.toString(), 1L).current
        val location = CzechLocation("CYKL", REGION_WORLD, report.latitude, report.longitude, "CA")
        val now = report.time.plusSeconds(60)
        val fused = fuseCurrentConditions(model, location, listOf(report), now)
        val current = WeatherParser.parseForecast(
            applyCurrentConditionsToForecastJson(root.toString(), fused), 1L).current
        assertEquals(68, current.weatherCode)
        assertEquals(report.time, current.skyObservationTime)
        assertEquals(0.0, current.precipitation, 0.0)
        assertEquals(true, hasPrecipitationEvidence(current.weatherCode, current.precipitation))
        assertEquals(R.string.condition_light_rain_and_snow, conditionFor(current.weatherCode).labelResource())
        for (modelCode in listOf(95, 96, 99)) {
            assertEquals(modelCode, fuseCurrentConditions(model.copy(weatherCode = modelCode),
                location, listOf(report), now).weatherCode)
        }
        assertEquals(0, fuseCurrentConditions(model, location, listOf(report),
            report.time.plusSeconds(31 * 60)).weatherCode)
        assertEquals(0, fuseCurrentConditions(model,
            location.copy(latitude = location.latitude + 1), listOf(report), now).weatherCode)
    }

    @Test
    fun rainProbabilityBoundsUseTheRawNumberOnAndroid() {
        for (value in listOf(-1.0, -0.1, 100.1, 101.0, 0.0, 12.8, 100.0)) {
            val root = JSONObject(FORECAST)
            root.getJSONObject("hourly").getJSONArray("precipitation_probability").put(0, value)
            root.getJSONObject("daily").getJSONArray("precipitation_probability_max").put(0, value)
            val parsed = WeatherParser.parseForecast(root.toString(), 1L)
            val expected = value.takeIf { it in 0.0..100.0 }?.toInt()
            assertEquals(expected, parsed.hourly.single().precipitationProbability)
            assertEquals(expected, parsed.daily.single().precipitationProbability)
            assertEquals(19.0, parsed.current.temperature, 0.0)
        }
    }

    @Test
    fun observedFreezingRainDoesNotInheritOrdinaryRainConfidenceOnAndroid() {
        val now = Instant.parse("2026-10-13T10:00:00Z")
        val root = JSONObject(FORECAST).put("utc_offset_seconds", 7200)
        root.getJSONObject("current").put("weather_code", 61).put("temperature_2m", 1)
        val snapshot = WeatherParser.parseForecast(root.toString(), 1L)
        val report = JSONObject().put("icaoId", "LKPR").put("obsTime", now.epochSecond)
            .put("qcField", 16).put("lat", 50.0).put("lon", 14.0)
            .put("wxString", "-FZRA").put("cover", "BKN").put("temp", 1)
        val observations = parseMetarCurrentConditions(JSONArray().put(report).toString())
        val current = fuseCurrentConditions(snapshot.current,
            CzechLocation("Point", REGION_PRAGUE, 50.0, 14.0), observations, now)
        val inputs = List(5) { index -> HourlyModelInput("model_$index", 1.0, 10.0, 180.0, 100.0, 61, 0.3) }
        val agreement = HourlyModelAgreement(5, 0.0, 0.0, 0.0, 5, 5, 0.3, 0.3, WeatherKind.RAIN,
            modelInputs = inputs)
        val hour = snapshot.hourly.single().copy(temperature = 1.0, weatherCode = 61,
            precipitation = 0.3, modelAgreement = agreement)
        val localNow = java.time.LocalDateTime.parse(hour.time)
        val following = hour.copy(time = localNow.plusHours(1).toString())
        assertEquals(66, current.weatherCode)
        assertEquals(ConfidenceLevel.HIGH, hourlyForecastConfidence(hour, following, localNow, 0).level)
        val result = hourlyForecastConfidence(hour, following, localNow, 0,
            conditionFor(current.weatherCode, current.isDay),
            shownConditionTime = current.skyConditionTime(snapshot.utcOffsetSeconds))
        assertEquals(ConfidenceLevel.LOW, result.level)
        assertEquals(listOf(ForecastDisagreement.DISPLAYED_CONDITION), result.disagreements)
    }

    @Test
    fun observedSkyTimeSurvivesNativeJsonAndDoesNotBecomeAnHourlyConflict() {
        val root = JSONObject(FORECAST).put("utc_offset_seconds", 7200)
        root.getJSONObject("current").put("weather_code", 0).put("cloud_cover", 0)
        val snapshot = WeatherParser.parseForecast(root.toString(), 1L)
        val agreement = HourlyModelAgreement(5, 0.0, 0.0, 0.0, 5, 0, 0.0, 0.0, WeatherKind.CLEAR)
        val hour = snapshot.hourly.single().copy(weatherCode = 0, modelAgreement = agreement)
        val following = hour.copy(time = "2026-10-13T13:00")
        val now = Instant.parse("2026-10-13T10:08:00Z")
        for (minute in listOf(0L, 5L)) {
            val report = CurrentStationObservation("TEST", 50.0, 14.0,
                now.minusSeconds((8 - minute) * 60), null, null, null, null, null, null,
                cloudCoverPercent = 100, weatherCode = 3)
            val fused = fuseCurrentConditions(snapshot.current,
                CzechLocation("Point", REGION_PRAGUE, 50.0, 14.0), listOf(report), now)
            val saved = applyCurrentConditionsToForecastJson(root.toString(), fused)
            val reloaded = WeatherParser.parseForecast(saved, 1L).current
            assertEquals(report.time, reloaded.skyObservationTime)
            assertEquals(snapshot.current.time, reloaded.time)
            val confidence = hourlyForecastConfidence(hour, following,
                java.time.LocalDateTime.parse("2026-10-13T12:08"), 0,
                conditionFor(reloaded.weatherCode, reloaded.isDay),
                shownConditionTime = reloaded.skyConditionTime(snapshot.utcOffsetSeconds))
            assertEquals(if (minute == 0L) ConfidenceLevel.LOW else ConfidenceLevel.HIGH, confidence.level)
            assertEquals(null, reloaded.skyConditionTime(null))
        }
    }

    @Test
    fun combinedMetarPrecipitationSurvivesFusionAndNativeJsonRoundTrip() {
        val now = Instant.parse("2026-10-13T10:05:00Z")
        val root = JSONObject(FORECAST)
        root.getJSONObject("current").put("weather_code", 0).put("cloud_cover", 0)
        val model = WeatherParser.parseForecast(root.toString(), 1L).current
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for ((encoded, expected) in mapOf(
            "-DZRA" to 58, "DZRA" to 59, "+DZRA" to 59,
            "-RADZ" to 58, "RADZ" to 59, "+RADZ" to 59,
            "-TSRAGR" to 96, "TSRAGR" to 96, "+TSRAGR" to 96,
        )) {
            val report = JSONObject().put("icaoId", "LKPR").put("obsTime", now.epochSecond - 300)
                .put("qcField", 16).put("lat", 50.0).put("lon", 14.0)
                .put("wxString", encoded).put("cover", "BKN").put("temp", 12)
            val observed = parseMetarCurrentConditions(JSONArray().put(report).toString()).single()
            val fused = fuseCurrentConditions(model, CzechLocation("Point", REGION_PRAGUE, 50.0, 14.0),
                listOf(observed), now)
            val saved = applyCurrentConditionsToForecastJson(root.toString(), fused)
            val current = WeatherParser.parseForecast(saved, 1L).current
            assertEquals(encoded, expected, current.weatherCode)
            assertEquals(encoded, 0.0, current.precipitation, 0.0)
            assertEquals(encoded, 63, current.cloudCover)
            val label = conditionFor(current.weatherCode).labelResource()
            val expectedLabel = when (expected) {
                58 -> R.string.condition_light_drizzle_and_rain
                59 -> R.string.condition_drizzle_and_rain
                else -> R.string.condition_storm
            }
            assertEquals(encoded, context.getString(expectedLabel), context.getString(label))
        }
    }

    @Test
    fun totalCloudBoundsUseTheRawNumberOnAndroid() {
        val values = listOf(-1.0, -0.1, 100.1, 255.0, 0.0, 12.8, 100.0, 19.5, 49.5, 79.5)
        val expected = listOf(null, null, null, null, 0, 13, 100, 20, 50, 80)
        values.forEachIndexed { index, value ->
            val root = JSONObject(FORECAST)
            root.getJSONObject("hourly").put("cloud_cover", JSONArray(listOf(value)))
            val snapshot = WeatherParser.parseForecast(root.toString(), 1L)
            assertEquals(expected[index], snapshot.hourly.single().cloudCover)
            root.getJSONObject("current").put("cloud_cover", value)
            if (index < 4) {
                assertThrows(JSONException::class.java) { WeatherParser.parseForecast(root.toString(), 1L) }
            } else {
                assertEquals(expected[index], WeatherParser.parseForecast(root.toString(), 1L).current.cloudCover)
            }
        }
    }

    @Test
    fun cloudLayerBoundsUseTheRawNumberOnAndroid() {
        val values = listOf(-1.0, -0.1, 100.1, 255.0, 0.0, 12.8, 100.0)
        val expected = listOf(null, null, null, null, 0, 12, 100)
        values.forEachIndexed { index, value ->
            val root = JSONObject(FORECAST)
            for (field in listOf("cloud_cover_low", "cloud_cover_mid", "cloud_cover_high")) {
                root.getJSONObject("current").put(field, value)
                root.getJSONObject("hourly").put(field, JSONArray(listOf(value)))
            }
            val snapshot = WeatherParser.parseForecast(root.toString(), 1L)
            assertEquals(listOf(expected[index], expected[index], expected[index]), listOf(
                snapshot.current.cloudCoverLow, snapshot.current.cloudCoverMid, snapshot.current.cloudCoverHigh,
            ))
            assertEquals(listOf(expected[index], expected[index], expected[index]), listOf(
                snapshot.hourly.single().cloudCoverLow, snapshot.hourly.single().cloudCoverMid,
                snapshot.hourly.single().cloudCoverHigh,
            ))
            assertEquals(19.0, snapshot.hourly.single().temperature, 0.0)
            assertEquals(1, snapshot.daily.size)
        }
    }

    private companion object {
        val FORECAST = """
            {
              "timezone":"Europe/Prague",
              "current":{"time":"2026-10-13T12:00","temperature_2m":19,"apparent_temperature":18,
                "relative_humidity_2m":48,"precipitation":0,"weather_code":3,"cloud_cover":100,
                "pressure_msl":1023,"wind_speed_10m":8,"wind_direction_10m":34,"wind_gusts_10m":16,"is_day":1},
              "hourly":{"time":["2026-10-13T12:00"],"temperature_2m":[19],"relative_humidity_2m":[48],
                "precipitation_probability":[5],"precipitation":[0],"weather_code":[3],
                "pressure_msl":[1023],"wind_speed_10m":[8],"wind_direction_10m":[34],"is_day":[1]},
              "daily":{"time":["2026-10-13"],"weather_code":[3],"temperature_2m_max":[22],"temperature_2m_min":[10],
                "sunrise":["2026-10-13T07:00"],"sunset":["2026-10-13T18:00"],"precipitation_sum":[0],
                "precipitation_probability_max":[5],"wind_speed_10m_max":[16]}
            }
        """.trimIndent()
    }
}
