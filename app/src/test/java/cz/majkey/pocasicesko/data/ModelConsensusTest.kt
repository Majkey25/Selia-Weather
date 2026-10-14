package cz.majkey.pocasicesko.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime

class ModelConsensusTest {
    @Test
    fun regionalQuarterHourWindowUsesOnlyTheExactCurrentTimeAcrossARequestBoundary() {
        val base = JSONObject(BASE).also {
            it.getJSONObject("current").put("weather_code", 0).put("cloud_cover", 0).put("cloud_cover_low", 0)
        }
        val fallback = ModelBlendResult(base.toString(), ForecastCalculationMode.BEST_MATCH,
            emptyList(), ForecastFallbackReason.PROVIDER_UNAVAILABLE)
        val current = JSONObject().put("time", "2026-08-29T19:30")
            .put("weather_code", 0).put("cloud_cover", 0)
        val window = JSONObject().put("time", JSONArray(listOf("2026-08-29T19:00", "2026-08-29T19:15", "2026-08-29T19:30")))
            .put("weather_code", JSONArray(listOf(0, 3, 0)))
            .put("cloud_cover", JSONArray(listOf(0, 100, 0)))
        val regional = JSONObject().put("current", current).put("minutely_15", window)
        val result = applyRegionalCurrentSky(fallback, PRAGUE, regional.toString())
        assertEquals("2026-08-29T19:15", JSONObject(result.json).getJSONObject("current").getString("time"))
        assertEquals(3, JSONObject(result.json).getJSONObject("current").getInt("weather_code"))
        assertEquals(100, JSONObject(result.json).getJSONObject("current").getInt("cloud_cover"))
        assertFalse(JSONObject(result.json).getJSONObject("current").has("cloud_cover_low"))
        assertEquals("chmi_aladin_seamless", result.currentSkyModelId)
        for (invalidTimes in listOf(listOf("2026-08-29T19:00", "2026-08-29T19:30"),
            listOf("2026-08-29T19:15", "2026-08-29T19:15"))) {
            window.put("time", JSONArray(invalidTimes))
            assertEquals(fallback, applyRegionalCurrentSky(fallback, PRAGUE, regional.toString()))
        }
        window.put("time", JSONArray(listOf("2026-08-29T19:15")))
        window.put("cloud_cover", JSONArray(listOf(100)))
        for (invalidCode in listOf(61, JSONObject.NULL, "3")) {
            window.put("weather_code", JSONArray(listOf(invalidCode)))
            assertEquals(fallback, applyRegionalCurrentSky(fallback, PRAGUE, regional.toString()))
        }
    }

    @Test
    fun regionalCurrentDoesNotConfuseRepeatedLocalTimesWithDifferentUtcOffsets() {
        val base = JSONObject(BASE).put("utc_offset_seconds", 7200)
        val fallback = ModelBlendResult(base.toString(), ForecastCalculationMode.BEST_MATCH,
            emptyList(), ForecastFallbackReason.PROVIDER_UNAVAILABLE)
        val regional = JSONObject().put("utc_offset_seconds", 3600).put("current",
            JSONObject(base.getJSONObject("current").toString()).put("weather_code", 0).put("cloud_cover", 0))
        assertEquals(fallback, applyRegionalCurrentSky(fallback, PRAGUE, regional.toString()))
        regional.put("utc_offset_seconds", 7200)
        assertEquals("chmi_aladin_seamless", applyRegionalCurrentSky(fallback, PRAGUE, regional.toString()).currentSkyModelId)
    }

    @Test
    fun regionalWindowRejectsUnalignedArrayLengths() {
        val fallback = ModelBlendResult(BASE, ForecastCalculationMode.BEST_MATCH,
            emptyList(), ForecastFallbackReason.PROVIDER_UNAVAILABLE)
        val regional = JSONObject().put("minutely_15", JSONObject()
            .put("time", JSONArray(listOf("2026-08-29T19:15", "2026-08-29T19:30")))
            .put("weather_code", JSONArray(listOf(0)))
            .put("cloud_cover", JSONArray(listOf(0, 0))))
        assertEquals(fallback, applyRegionalCurrentSky(fallback, PRAGUE, regional.toString()))
    }

    @Test
    fun independentRegionalCurrentWorksDuringProviderOutageAtEveryCurrentCadence() {
        for (minute in listOf("00", "15", "30", "45")) {
            val time = "2026-08-29T19:$minute"
            val base = JSONObject(BASE).also {
                it.getJSONObject("current").put("time", time).put("weather_code", 0).put("cloud_cover", 0)
                    .put("cloud_cover_low", 0)
            }
            val fallback = ModelBlendResult(base.toString(), ForecastCalculationMode.BEST_MATCH,
                emptyList(), ForecastFallbackReason.PROVIDER_UNAVAILABLE)
            val regional = JSONObject().put("current", JSONObject().put("time", time)
                .put("weather_code", 3).put("cloud_cover", 100).put("cloud_cover_mid", 90)).toString()
            val result = applyRegionalCurrentSky(fallback, PRAGUE, regional)
            val root = JSONObject(result.json)
            assertEquals(3, root.getJSONObject("current").getInt("weather_code"))
            assertEquals(100, root.getJSONObject("current").getInt("cloud_cover"))
            assertEquals(90, root.getJSONObject("current").getInt("cloud_cover_mid"))
            assertFalse(root.getJSONObject("current").has("cloud_cover_low"))
            assertEquals(fallback.mode, result.mode)
            assertEquals(fallback.fallbackReason, result.fallbackReason)
            assertEquals(fallback.contributorIds, result.contributorIds)
            assertEquals(base.getJSONObject("hourly").toString(), root.getJSONObject("hourly").toString())
            assertEquals(base.getJSONObject("daily").toString(), root.getJSONObject("daily").toString())
            val calculation = ForecastCalculation(ForecastRegion.CZECHIA, result.mode,
                listOf("chmi_aladin_seamless"), result.contributorIds, result.fallbackReason,
                currentSkyModelId = result.currentSkyModelId)
            assertEquals(calculation, JSONObject().putForecastCalculation(calculation).forecastCalculationOrNull())
        }
    }

    @Test
    fun regionalCurrentFailureAndNonCzechLocationsKeepTheFallbackUnchanged() {
        val fallback = ModelBlendResult(BASE, ForecastCalculationMode.BEST_MATCH,
            emptyList(), ForecastFallbackReason.PROVIDER_UNAVAILABLE)
        for (regional in listOf(null, "not JSON", "{}", "{\"current\":null}")) {
            assertEquals(fallback, applyRegionalCurrentSky(fallback, PRAGUE, regional))
        }
        val valid = JSONObject().put("current", JSONObject().put("time", "2026-08-29T19:15")
            .put("weather_code", 0).put("cloud_cover", 0)).toString()
        assertEquals(fallback, applyRegionalCurrentSky(fallback,
            CzechLocation("Berlin", "Berlin", 52.52, 13.405, "DE"), valid))
        for (precipitation in listOf(0.1, JSONObject.NULL, "0")) {
            val base = JSONObject(BASE).also { it.getJSONObject("current").put("precipitation", precipitation) }
            val wet = fallback.copy(json = base.toString())
            assertEquals(wet, applyRegionalCurrentSky(wet, PRAGUE, valid))
        }
    }

    @Test
    fun regionalCurrentSurvivesInsufficientOrUnalignedHourlyModels() {
        val location = CzechLocation("Praha", REGION_PRAGUE, 50.0755, 14.4378, "CZ")
        val regional = JSONObject().put("current", JSONObject().put("time", "2026-08-29T19:15")
            .put("weather_code", 3).put("cloud_cover", 100)).toString()
        val base = JSONObject(BASE).also {
            it.getJSONObject("current").put("weather_code", 0).put("cloud_cover", 0)
        }
        val unaligned = JSONObject(MODELS).also {
            it.getJSONObject("hourly").put("time", JSONArray())
        }.toString()
        for (models in listOf(ONE_MODEL, unaligned)) {
            val result = applyRegionalCurrentSky(blendModelForecast(base.toString(), models, location), location, regional)
            val current = JSONObject(result.json).getJSONObject("current")
            assertEquals(3, current.getInt("weather_code"))
            assertEquals(100, current.getInt("cloud_cover"))
            assertEquals("chmi_aladin_seamless", result.currentSkyModelId)
            assertEquals(ForecastCalculationMode.BEST_MATCH, result.mode)
            assertEquals(ForecastFallbackReason.INSUFFICIENT_CONTRIBUTORS, result.fallbackReason)
            assertEquals(base.getJSONObject("daily").toString(), JSONObject(result.json).getJSONObject("daily").toString())
        }
    }

    @Test
    fun regionalCurrentSkyRemainsSeparateFromHourlyConsensus() {
        val source = JSONObject(MODELS)
        val inputs = source.getJSONObject("hourly")
        listOf("a", "b", "c").forEach { id ->
            inputs.put("weather_code_$id", JSONArray(listOf(0, 0)))
            inputs.put("cloud_cover_$id", JSONArray(listOf(0, 0)))
            inputs.put("precipitation_$id", JSONArray(listOf(0.0, 0.0)))
        }
        listOf("temperature_2m", "weather_code", "cloud_cover", "precipitation", "wind_speed_10m", "wind_direction_10m").forEach { field ->
            inputs.put("${field}_chmi_aladin_seamless", JSONArray(listOf(
                when (field) { "weather_code" -> 3; "cloud_cover" -> 100; "temperature_2m" -> 20; "wind_speed_10m" -> 10; else -> 0 },
                when (field) { "weather_code" -> 3; "cloud_cover" -> 100; "temperature_2m" -> 20; "wind_speed_10m" -> 10; else -> 0 },
            )))
        }
        source.put("current", JSONObject().put("time", "2026-08-29T19:15").put("weather_code", 3).put("cloud_cover", 100))
        val location = CzechLocation("Březnice", "Zlín", 49.18643, 17.66277, "CZ")
        val base = JSONObject(BASE).also {
            it.getJSONObject("current").put("weather_code", 0).put("cloud_cover", 0).put("is_day", 1)
            it.getJSONObject("daily").put("sunrise", JSONArray(listOf("2026-08-29T06:00")))
                .put("sunset", JSONArray(listOf("2026-08-29T20:00")))
        }
        val root = JSONObject(applyRegionalCurrentSky(blendModelForecast(base.toString(), source.toString(), location), location, source.toString()).json)
        assertEquals(3, root.getJSONObject("current").getInt("weather_code"))
        assertEquals(100, root.getJSONObject("current").getInt("cloud_cover"))
        assertEquals(0, root.getJSONObject("hourly").getJSONArray("weather_code").getInt(0))
        assertEquals(0, root.getJSONObject("hourly").getJSONArray("cloud_cover").getInt(0))
        assertTrue(root.getJSONObject("hourly").getJSONArray(SKY_SOURCE_MODEL_KEY).isNull(0))
        assertEquals(null, WeatherParser.parseForecast(root.toString(), 0).hourly.first().skySourceModelId)
        val incomplete = JSONObject(source.toString()).also {
            it.getJSONObject("hourly").getJSONArray("wind_direction_10m_chmi_aladin_seamless").put(0, JSONObject.NULL)
        }
        val incompleteHour = WeatherParser.parseForecast(blendModelForecast(base.toString(), incomplete.toString(), location).json, 0).hourly.first()
        assertEquals(null, incompleteHour.modelAgreement)
        assertEquals(null, incompleteHour.skySourceModelId)
        val wetNext = JSONObject(base.toString()).also {
            it.getJSONObject("hourly").getJSONArray("precipitation").put(1, 0.5)
        }
        assertTrue(JSONObject(blendModelForecast(wetNext.toString(), source.toString(), location).json)
            .getJSONObject("hourly").getJSONArray(SKY_SOURCE_MODEL_KEY).isNull(0))
        val missingNext = JSONObject(base.toString()).also {
            it.getJSONObject("hourly").getJSONArray("precipitation").put(1, JSONObject.NULL)
        }
        assertTrue(JSONObject(blendModelForecast(missingNext.toString(), source.toString(), location).json)
            .getJSONObject("hourly").getJSONArray(SKY_SOURCE_MODEL_KEY).isNull(0))
        listOf(45, 51, 95).forEach { hazard ->
            val hazardous = JSONObject(base.toString())
            hazardous.getJSONObject("current").put("weather_code", hazard)
            hazardous.getJSONObject("hourly").getJSONArray("weather_code").put(0, hazard)
            val retained = JSONObject(applyRegionalCurrentSky(blendModelForecast(hazardous.toString(), source.toString(), location), location, source.toString()).json)
            assertEquals(hazard, retained.getJSONObject("current").getInt("weather_code"))
            assertEquals(hazard, retained.getJSONObject("hourly").getJSONArray("weather_code").getInt(0))
        }
        val nonCzech = JSONObject(blendModelForecast(base.toString(), source.toString(),
            CzechLocation("Berlin", "Berlin", 52.52, 13.405, "DE")).json)
        assertEquals(0, nonCzech.getJSONObject("current").getInt("weather_code"))
        assertEquals(0, nonCzech.getJSONObject("hourly").getJSONArray("weather_code").getInt(0))
        source.getJSONObject("current").put("weather_code", 61)
        assertEquals(0, JSONObject(applyRegionalCurrentSky(blendModelForecast(base.toString(), source.toString(), location),
            location, source.toString()).json).getJSONObject("current").getInt("weather_code"))
        source.getJSONObject("current").put("weather_code", 3)
        source.getJSONObject("current").put("time", "2026-08-29T18:15")
        assertEquals(0, JSONObject(applyRegionalCurrentSky(blendModelForecast(base.toString(), source.toString(), location), location, source.toString()).json)
            .getJSONObject("current").getInt("weather_code"))
        source.getJSONObject("current").put("time", "2026-08-29T19:15")
        for (invalid in listOf(JSONObject.NULL, "3")) {
            source.getJSONObject("current").put("weather_code", invalid)
            val result = applyRegionalCurrentSky(blendModelForecast(base.toString(), source.toString(), location), location, source.toString())
            assertEquals(ForecastCalculationMode.DIAGNOSTIC_MEDIAN, result.mode)
            assertEquals(null, result.currentSkyModelId)
        }
    }

    @Test
    fun oneRegionalClearMemberCannotReplaceOvercastHourlyConsensus() {
        val source = JSONObject(MODELS).getJSONObject("hourly")
        listOf("a", "b", "c").forEach { id ->
            source.put("weather_code_$id", JSONArray(listOf(3, 3)))
                .put("cloud_cover_$id", JSONArray(listOf(100, 100)))
                .put("precipitation_$id", JSONArray(listOf(0, 0)))
        }
        mapOf("temperature_2m" to 20, "weather_code" to 0, "cloud_cover" to 0,
            "precipitation" to 0, "wind_speed_10m" to 10, "wind_direction_10m" to 180).forEach { (field, value) ->
            source.put("${field}_chmi_aladin_seamless", JSONArray(listOf(value, value)))
        }
        val hourly = JSONObject(blendModelForecast(BASE, JSONObject().put("hourly", source).toString(), PRAGUE).json)
            .getJSONObject("hourly")
        assertEquals(100, hourly.getJSONArray("cloud_cover").getInt(0))
        assertEquals(3, hourly.getJSONArray("weather_code").getInt(0))
        assertTrue(hourly.getJSONArray(SKY_SOURCE_MODEL_KEY).isNull(0))
        assertEquals(4, hourly.getJSONArray(MODEL_AGREEMENT_KEY).getJSONObject(0).getInt("model_count"))
    }

    @Test
    fun hourlySkyConsensusSurvivesDryWetOrMissingProviderRainAtEitherHour() {
        val source = JSONObject(MODELS)
        val inputs = source.getJSONObject("hourly")
        listOf("a", "b", "c").forEach { id ->
            inputs.put("weather_code_$id", JSONArray(listOf(3, 3)))
            inputs.put("cloud_cover_$id", JSONArray(listOf(100, 100)))
            inputs.put("precipitation_$id", JSONArray(listOf(0.05, 0.05)))
        }
        mapOf("temperature_2m" to 20, "weather_code" to 0, "cloud_cover" to 0,
            "precipitation" to 0.0, "wind_speed_10m" to 10, "wind_direction_10m" to 180).forEach { (field, value) ->
            inputs.put("${field}_chmi_aladin_seamless", JSONArray(listOf(value, value)))
        }
        for (wetIndex in 0..1) for (amount in listOf(0.0, 0.1, 0.3, JSONObject.NULL)) {
            val base = JSONObject(BASE)
            base.getJSONObject("hourly").getJSONArray("precipitation").put(wetIndex, amount)
            val hourly = JSONObject(blendModelForecast(base.toString(), source.toString(), PRAGUE).json)
                .getJSONObject("hourly")
            assertTrue("Unexpected regional dominance at hour $wetIndex for $amount",
                hourly.getJSONArray(SKY_SOURCE_MODEL_KEY).isNull(0))
            assertEquals(3, hourly.getJSONArray("weather_code").getInt(0))
            assertEquals(100, hourly.getJSONArray("cloud_cover").getInt(0))
            assertEquals(0.05, hourly.getJSONArray("precipitation").getDouble(0), 0.0)
        }
    }

    @Test
    fun regionalDrySkyCannotEraseWetConsensusOrReplaceTheLongRangeSky() {
        val source = JSONObject(MODELS).getJSONObject("hourly")
        mapOf("temperature_2m" to 20, "weather_code" to 0, "cloud_cover" to 0,
            "precipitation" to 0.0, "wind_speed_10m" to 10, "wind_direction_10m" to 180).forEach { (field, value) ->
            source.put("${field}_chmi_aladin_seamless", JSONArray(listOf(value, value)))
        }
        listOf("a", "b", "c").forEach { id ->
            source.put("precipitation_$id", JSONArray(listOf(0.2, 0.2)))
            source.put("weather_code_$id", JSONArray(listOf(61, 61)))
        }
        val location = CzechLocation("Březnice", "Zlín", 49.18643, 17.66277, "CZ")
        val wet = JSONObject(blendModelForecast(BASE, JSONObject().put("hourly", source).toString(), location).json)
            .getJSONObject("hourly")
        assertEquals(61, wet.getJSONArray("weather_code").getInt(0))
        assertEquals(0.2, wet.getJSONArray("precipitation").getDouble(0), 0.0)
        assertTrue(wet.getJSONArray(SKY_SOURCE_MODEL_KEY).isNull(0))
        val distantBase = JSONObject(BASE)
        distantBase.getJSONObject("hourly").getJSONArray("time").put(0, "2026-09-02T19:00")
        source.getJSONArray("time").put(0, "2026-09-02T19:00")
        listOf("a", "b", "c").forEach { id ->
            source.put("precipitation_$id", JSONArray(listOf(0.0, 0.0)))
            source.put("weather_code_$id", JSONArray(listOf(3, 3)))
            source.put("cloud_cover_$id", JSONArray(listOf(100, 100)))
        }
        val distant = JSONObject(blendModelForecast(distantBase.toString(), JSONObject().put("hourly", source).toString(), location).json)
            .getJSONObject("hourly")
        assertEquals(3, distant.getJSONArray("weather_code").getInt(0))
        assertEquals(100, distant.getJSONArray("cloud_cover").getInt(0))
    }

    @Test
    fun perModelValuesSurviveAgreementCacheSerialization() {
        val hourly = JSONObject(blendModelForecast(BASE, MODELS).json).getJSONObject("hourly")
        val metadata = hourly.getJSONArray(MODEL_AGREEMENT_KEY).getJSONObject(0)
        val saved = requireNotNull(metadata.modelAgreementOrNull()).toJson().getJSONArray("model_inputs")
        assertEquals(3, saved.length())
        assertEquals("a", saved.getJSONObject(0).getString("model_id"))
        assertEquals(20.0, saved.getJSONObject(0).getDouble("temperature_celsius"), 0.0)
        assertEquals(0, saved.getJSONObject(0).getInt("weather_code"))
        assertEquals(0.0, saved.getJSONObject(0).getDouble("precipitation_mm"), 0.0)
    }

    @Test
    fun adjacentDrySkyCategoriesDoNotHideMinorityRainOrFog() {
        val base = JSONObject(BASE)
        base.getJSONObject("current").put("is_day", 1)
        base.getJSONObject("daily").put("sunrise", JSONArray(listOf("2026-08-29T06:00")))
            .put("sunset", JSONArray(listOf("2026-08-29T20:00")))
        for (minorityCode in listOf<Int?>(null, 45, 61, 95)) {
            val models = JSONObject(MODELS)
            val source = models.getJSONObject("hourly")
            repeat(11) { index ->
                val code = if (index == 10 && minorityCode != null) minorityCode else if (index < 5) 0 else 1
                mapOf("temperature_2m" to 20.0, "wind_speed_10m" to 10.0, "wind_direction_10m" to 180.0,
                    "cloud_cover" to if (index < 5) 19.0 else 21.0, "weather_code" to code.toDouble(),
                    "precipitation" to 0.0).forEach { (field, value) ->
                    source.put("${field}_${'a' + index}", JSONArray(listOf(value, value)))
                }
            }
            val blended = blendModelForecast(base.toString(), models.toString()).json
            val snapshot = WeatherParser.parseForecast(blended, 0L)
            val confidence = hourlyForecastConfidence(snapshot.hourly.first(), snapshot.hourly[1],
                LocalDateTime.parse("2026-08-29T18:00"), 0)
            assertEquals(if (minorityCode == null) ConfidenceLevel.HIGH else ConfidenceLevel.LOW, confidence.level)
            val metadata = JSONObject(blended).getJSONObject("hourly").getJSONArray(MODEL_AGREEMENT_KEY).getJSONObject(0)
            assertEquals(11, metadata.getInt("model_count"))
            assertEquals(2.0, metadata.getDouble("cloud_range_percent"), 0.0)
            assertEquals(minorityCode == null, metadata.getBoolean("all_sky_conditions"))
        }
    }

    @Test
    fun hourlyAgreementUsesActualCompleteContributorsAndSurvivesJson() {
        val hourly = JSONObject(blendModelForecast(BASE, MODELS).json).getJSONObject("hourly")
        val first = requireNotNull(hourly.getJSONArray(MODEL_AGREEMENT_KEY).getJSONObject(0).modelAgreementOrNull())
        assertEquals(3, first.modelCount)
        assertEquals(20.0, first.temperatureRangeCelsius, 0.0)
        assertEquals(20.0, first.cloudRangePercent, 0.0)
        assertEquals(2, first.agreeingConditionCount)
        assertEquals(WeatherKind.CLEAR, first.dominantCondition)
        assertEquals(0, first.wetModelCount)
        assertTrue(first.windRangeKmh in 3.4..3.6)
        assertEquals(first, first.toJson().modelAgreementOrNull())
    }

    @Test
    fun windAgreementUsesActualModelPairsRatherThanBoundingBoxCorners() {
        val base = JSONObject(BASE).also { root ->
            root.getJSONObject("current").put("is_day", 1)
            root.getJSONObject("daily").put("sunrise", JSONArray(listOf("2026-08-29T06:00")))
                .put("sunset", JSONArray(listOf("2026-08-29T20:00")))
        }
        val models = JSONObject(MODELS).also { root ->
            val source = root.getJSONObject("hourly")
            listOf(0, 90, 180, 270).forEachIndexed { index, direction ->
                mapOf("temperature_2m" to 20, "wind_speed_10m" to 10,
                    "wind_direction_10m" to direction, "cloud_cover" to 0,
                    "weather_code" to 0, "precipitation" to 0).forEach { (field, value) ->
                    source.put("${field}_${'a' + index}", JSONArray(listOf(value, value)))
                }
            }
        }
        val snapshot = WeatherParser.parseForecast(blendModelForecast(base.toString(), models.toString()).json, 0L)
        assertEquals(20.0, requireNotNull(snapshot.hourly.first().modelAgreement).windRangeKmh, 1e-9)
        assertEquals(ConfidenceLevel.MODERATE, hourlyForecastConfidence(snapshot.hourly.first(), snapshot.hourly[1],
            LocalDateTime.parse("2026-08-29T18:00"), 0).level)
    }

    @Test
    fun missingComparisonFieldsDoNotBorrowGlobalContributorCounts() {
        val models = JSONObject(MODELS)
        models.getJSONObject("hourly").remove("cloud_cover_c")
        val hourly = JSONObject(blendModelForecast(BASE, models.toString()).json).getJSONObject("hourly")
        assertTrue(hourly.getJSONArray(MODEL_AGREEMENT_KEY).isNull(0))
        assertEquals(22.0, hourly.getJSONArray("temperature_2m").getDouble(0), 0.0)
    }

    @Test
    fun invalidOnlyWindInputCannotEraseAgreementBetweenValidModels() {
        val models = JSONObject(MODELS)
        models.getJSONObject("hourly").put("wind_speed_10m_d", JSONArray(listOf(-1, -1)))
        val hourly = JSONObject(blendModelForecast(BASE, models.toString()).json).getJSONObject("hourly")
        assertEquals(10.0, hourly.getJSONArray("wind_speed_10m").getDouble(0), 0.0)
        val agreement = hourly.getJSONArray(MODEL_AGREEMENT_KEY)
        assertFalse("Invalid-only wind input must not erase valid model evidence", agreement.isNull(0))
        assertEquals(3, agreement.getJSONObject(0).getInt("model_count"))
    }

    @Test
    fun partialInputsThatInfluenceTheBlendCannotBeHiddenFromConfidence() {
        val models = JSONObject(MODELS)
        val source = models.getJSONObject("hourly")
        source.put("temperature_2m_d", org.json.JSONArray().put(100.0).put(100.0))
        val hourly = JSONObject(blendModelForecast(BASE, models.toString()).json).getJSONObject("hourly")
        assertEquals(31.0, hourly.getJSONArray("temperature_2m").getDouble(0), 0.0)
        assertTrue(hourly.getJSONArray(MODEL_AGREEMENT_KEY).isNull(0))
    }

    @Test
    fun staticCalibrationDoesNotClaimAgreementFromDifferentLiveRuns() {
        val result = issuedBlend(issuedValues())
        assertTrue(result.calibratedValueCount > 0)
        val hourly = JSONObject(result.json).getJSONObject("hourly")
        assertTrue(hourly.getJSONArray(MODEL_AGREEMENT_KEY).isNull(0))
    }
    @Test
    fun dryMedianCannotEraseRegionalRainWhileMinorityEvidenceRemainsVisible() {
        val base = precipitationBase().also {
            it.getJSONObject("hourly").getJSONArray("precipitation").put(0, 0.3)
            it.getJSONObject("hourly").getJSONArray("rain").put(0, 0.3)
            it.getJSONObject("hourly").getJSONArray("weather_code").put(0, 51)
        }
        val models = JSONObject(precipitationModels(List(9) { if (it == 0) 0.4 else 0.0 }, emptyMap())).also {
            repeat(9) { index ->
                it.getJSONObject("hourly").put("temperature_2m_${'a' + index}", JSONArray(listOf(20.0, 20.0)))
            }
        }.toString()
        val hourly = JSONObject(blendModelForecast(base.toString(), models).json).getJSONObject("hourly")
        assertEquals(0.3, hourly.getJSONArray("precipitation").getDouble(0), 0.0)
        assertEquals(0.3, hourly.getJSONArray("rain").getDouble(0), 0.0)
        assertEquals(51, hourly.getJSONArray("weather_code").getInt(0))
        assertEquals(3, hourly.getJSONArray("precipitation_probability").getInt(0))
        assertEquals(1, hourly.getJSONArray(PRECIPITATION_SPREAD_KEY).getJSONObject(0).getInt("wet_model_count"))
        assertEquals(9, hourly.getJSONArray(PRECIPITATION_SPREAD_KEY).getJSONObject(0).getInt("model_count"))
    }

    @Test
    fun guardedPrecipitationOnlyInputsKeepDiagnosticsAndProviderComponents() {
        val base = precipitationBase().also {
            it.getJSONObject("hourly").getJSONArray("precipitation").put(0, 0.3)
            it.getJSONObject("hourly").getJSONArray("rain").put(0, 0.2)
            it.getJSONObject("hourly").getJSONArray("showers").put(0, 0.1)
        }
        val source = JSONObject().put("time", JSONArray(listOf("2026-08-29T19:00")))
        listOf("a", "b", "c").forEach {
            source.put("temperature_2m_$it", JSONArray().put(JSONObject.NULL))
            source.put("precipitation_$it", JSONArray().put(if (it == "a") 0.4 else 0.0))
        }
        val result = JSONObject(blendModelForecast(base.toString(), JSONObject().put("hourly", source).toString()).json)
        val hourly = result.getJSONObject("hourly")
        assertEquals(1, hourly.getJSONArray(PRECIPITATION_SPREAD_KEY).getJSONObject(0).getInt("wet_model_count"))
        assertEquals(0.3, hourly.getJSONArray("precipitation").getDouble(0), 0.0)
        assertEquals(0.2, hourly.getJSONArray("rain").getDouble(0), 0.0)
        assertEquals(0.1, hourly.getJSONArray("showers").getDouble(0), 0.0)
        assertEquals(0.0, hourly.getJSONArray("snowfall").getDouble(0), 0.0)
        assertEquals(base.getJSONObject("daily").toString(), result.getJSONObject("daily").toString())
        assertEquals(base.getJSONObject("current").toString(), result.getJSONObject("current").toString())
    }

    @Test
    fun dryModelConsensusCannotEraseRegionalTraceDrizzleCode() {
        val base = precipitationBase().also {
            it.getJSONObject("hourly").getJSONArray("weather_code").put(0, 51)
        }
        val hourly = JSONObject(blendModelForecast(base.toString(),
            precipitationModels(listOf(0.0, 0.0, 0.0), emptyMap())).json).getJSONObject("hourly")
        assertEquals(51, hourly.getJSONArray("weather_code").getInt(0))
        assertEquals(0.0, hourly.getJSONArray("precipitation").getDouble(0), 0.0)
    }

    @Test
    fun selectsLocationSpecificModelInputs() {
        val prague = WeatherRepository.modelForecastUrl(
            CzechLocation("Praha", REGION_PRAGUE, 50.0755, 14.4378, "CZ"),
        )
        val newYork = WeatherRepository.modelForecastUrl(
            CzechLocation("New York", "New York", 40.7128, -74.006, "US"),
        )

        assertTrue(prague.contains("chmi_aladin_seamless"))
        assertTrue(prague.contains("icon_seamless"))
        assertTrue(prague.contains("ecmwf_ifs025"))
        assertTrue(prague.contains("gfs_seamless"))
        assertTrue(prague.contains("precipitation,rain,showers,snowfall"))
        assertFalse(prague.contains("&current="))
        assertEquals("chmi_aladin_seamless", prague.substringAfter("&models=").substringBefore(','))
        val regionalCurrent = WeatherRepository.regionalCurrentUrl(CzechLocation("Praha", REGION_PRAGUE, 50.0755, 14.4378, "CZ"))
        assertTrue(regionalCurrent.contains("&current=weather_code,cloud_cover,cloud_cover_low,cloud_cover_mid,cloud_cover_high"))
        assertTrue(regionalCurrent.contains("&models=chmi_aladin_seamless&"))
        assertFalse(newYork.contains("chmi_aladin_seamless"))
        assertFalse(newYork.contains("&current="))
        assertTrue(newYork.contains("gem_seamless"))
        assertTrue(newYork.contains("&models=${forecastApiModelsFor(
            CzechLocation("New York", "New York", 40.7128, -74.006, "US"),
        ).joinToString(",")}"))
        assertFalse(newYork.contains("kma_seamless"))
        listOf(prague, newYork).forEach { url ->
            val models = url.substringAfter("&models=").split(",")
            assertTrue("ecmwf_aifs025_single" in models)
            assertFalse("ecmwf_aifs025" in models)
        }
    }

    @Test
    fun windSpeedDoesNotDisappearWhenModelDirectionsDisagree() {
        for (directions in listOf(listOf(0, 90, 180, 270), listOf(350, 0, 10))) {
            val base = JSONObject(BASE)
            base.getJSONObject("current").put("time", "2026-08-29T19:00")
            val models = JSONObject().put("time", JSONArray(listOf("2026-08-29T19:00")))
            directions.forEachIndexed { index, direction ->
                models.put("temperature_2m_m$index", JSONArray(listOf(20)))
                    .put("wind_speed_10m_m$index", JSONArray(listOf(10)))
                    .put("wind_direction_10m_m$index", JSONArray(listOf(direction)))
            }
            val result = JSONObject(blendModelForecast(
                base.toString(), JSONObject().put("hourly", models).toString(),
            ).json)

            assertEquals(10.0, result.getJSONObject("hourly").getJSONArray("wind_speed_10m").getDouble(0), 1e-9)
            assertEquals(10.0, result.getJSONObject("current").getDouble("wind_speed_10m"), 1e-9)
            assertEquals(10.0, result.getJSONObject("daily").getJSONArray("wind_speed_10m_max").getDouble(0), 1e-9)
            if (directions.size == 3) {
                assertEquals(0, result.getJSONObject("hourly").getJSONArray("wind_direction_10m").getInt(0))
            }
        }
    }

    @Test
    fun blendsContinuousValuesAndDerivesConditionsLocally() {
        val result = blendModelForecast(BASE, MODELS)
        val root = JSONObject(result.json)
        val current = root.getJSONObject("current")
        val hourly = root.getJSONObject("hourly")
        val daily = root.getJSONObject("daily")

        assertEquals(99.0, current.getDouble("temperature_2m"), 0.0)
        assertEquals(22.0, hourly.getJSONArray("temperature_2m").getDouble(0), 0.0)
        assertEquals(3, current.getInt("weather_code"))
        assertEquals(100, current.getInt("cloud_cover"))
        assertEquals(0, hourly.getJSONArray("weather_code").getInt(0))
        assertEquals(10, hourly.getJSONArray("cloud_cover").getInt(0))
        assertEquals(180, current.getInt("wind_direction_10m"))
        assertEquals(0, hourly.getJSONArray("wind_direction_10m").getInt(0))
        assertEquals(0, hourly.getJSONArray("precipitation_probability").getInt(1))
        assertEquals(61, hourly.getJSONArray("weather_code").getInt(1))
        assertEquals(22.0, daily.getJSONArray("temperature_2m_max").getDouble(0), 0.0)
        assertEquals(20.0, daily.getJSONArray("temperature_2m_min").getDouble(0), 0.0)
        // Two hourly intervals cannot replace a complete provider daily total.
        assertEquals(0.0, daily.getJSONArray("precipitation_sum").getDouble(0), 0.0)
        assertEquals(180, daily.getJSONArray("wind_direction_10m_dominant").getInt(0))
        assertEquals(ForecastCalculationMode.DIAGNOSTIC_MEDIAN, result.mode)
        assertEquals(listOf("a", "b", "c"), result.contributorIds)
        assertEquals(null, result.fallbackReason)
    }

    @Test
    fun sparseOrTiedCodesDoNotInventHazardConsensus() {
        listOf(listOf(95), listOf(71, 0), listOf(45, 0), listOf(95, 95, 0, 0)).forEach { codes ->
            val models = JSONObject(MODELS).also { root ->
                val hourly = root.getJSONObject("hourly")
                listOf("a", "b", "c", "d").forEachIndexed { index, suffix ->
                    hourly.put("weather_code_$suffix", JSONArray(listOf(codes.getOrNull(index), 0)))
                }
            }

            val result = JSONObject(blendModelForecast(BASE, models.toString()).json)

            assertEquals(0, result.getJSONObject("hourly").getJSONArray("weather_code").getInt(0))
        }
    }

    @Test
    fun drySkyQuorumKeepsModelClassificationInsteadOfReclassifyingCloudPercentages() {
        listOf(1, 2, 3).forEach { code ->
            listOf(false, true).forEach { cloudOnly ->
                val models = JSONObject(MODELS).also { root ->
                    val hourly = root.getJSONObject("hourly")
                    listOf("a", "b", "c").forEach { suffix ->
                        hourly.getJSONArray("weather_code_$suffix").put(0, code)
                        if (cloudOnly) hourly.remove("precipitation_$suffix")
                    }
                }
                val hourly = JSONObject(blendModelForecast(BASE, models.toString()).json)
                    .getJSONObject("hourly")
                assertEquals("code=$code cloudOnly=$cloudOnly", code, hourly.getJSONArray("weather_code").getInt(0))
                assertEquals(10, hourly.getJSONArray("cloud_cover").getInt(0))
            }
        }
    }

    @Test
    fun explicitPhenomenonQuorumDoesNotRequireCloudTotals() {
        listOf(45, 51, 56, 61, 66, 71, 80, 95).forEach { code ->
            val result = phenomenonFixture(listOf(code, code, code), setOf("cloud_cover"))
            val hourly = result.getJSONObject("hourly")
            assertEquals("code=$code", code, hourly.getJSONArray("weather_code").getInt(0))
            assertEquals(0.0, hourly.getJSONArray("precipitation").getDouble(0), 0.0)
            assertEquals(3, hourly.getJSONArray("precipitation_probability").getInt(0))
            assertEquals(3, result.getJSONObject("current").getInt("weather_code"))
        }
    }

    @Test
    fun explicitPhenomenonQuorumDoesNotRequirePrecipitationTotals() {
        listOf(setOf("precipitation"), setOf("precipitation", "cloud_cover")).forEach { missing ->
            listOf(45, 51, 56, 61, 66, 71, 80, 95).forEach { code ->
                val hourly = phenomenonFixture(listOf(code, code, code), missing).getJSONObject("hourly")
                assertEquals("code=$code missing=$missing", code, hourly.getJSONArray("weather_code").getInt(0))
                assertEquals(0.0, hourly.getJSONArray("precipitation").getDouble(0), 0.0)
                assertEquals(3, hourly.getJSONArray("precipitation_probability").getInt(0))
                assertTrue(hourly.getJSONArray(PRECIPITATION_SPREAD_KEY).isNull(0))
            }
        }
    }

    @Test
    fun documentedHeavyThunderstormCodesContributeToStormConsensus() {
        val hourly = phenomenonFixture(listOf(97, 97, 97), setOf("cloud_cover", "precipitation"))
            .getJSONObject("hourly")
        assertEquals(97, hourly.getJSONArray("weather_code").getInt(0))
    }

    @Test
    fun exactPhenomenonMajorityKeepsItsReportedIntensity() {
        listOf(53, 55, 57, 63, 65, 67, 73, 75, 81, 82, 86, 96, 99).forEach { code ->
            val hourly = phenomenonFixture(listOf(code, code, code, 0), setOf("precipitation", "cloud_cover"))
                .getJSONObject("hourly")
            assertEquals("code=$code", code, hourly.getJSONArray("weather_code").getInt(0))
        }
    }

    @Test
    fun splitRainIntensityDoesNotInventAnUnreportedLightClass() {
        for ((codes, expected) in listOf(listOf(63, 63, 65, 65) to 63,
            listOf(53, 53, 55, 55) to 53, listOf(81, 81, 82, 82) to 81,
            listOf(65, 65, 82, 82) to 65)) {
            val hourly = phenomenonFixture(codes, setOf("precipitation", "cloud_cover")).getJSONObject("hourly")
            assertEquals("codes=$codes", expected, hourly.getJSONArray("weather_code").getInt(0))
        }
    }

    @Test
    fun malformedOrUnsupportedCodesCannotSupplyTheThirdQuorumMember() {
        listOf(JSONObject.NULL, "NaN", -1, 100, 51.25, 52).forEach { invalid ->
            val hourly = phenomenonFixture(listOf(51, 51, invalid), emptySet()).getJSONObject("hourly")
            assertEquals("invalid=$invalid", 0, hourly.getJSONArray("weather_code").getInt(0))
        }
    }

    @Test
    fun minorityTieAndSparsePhenomenaDoNotReplaceProviderConditions() {
        listOf(listOf(51), listOf(51, 51), listOf(51, 0, 0), listOf(51, 51, 0, 0)).forEach { codes ->
            val missing = setOf("cloud_cover", "precipitation")
            assertEquals(0, phenomenonFixture(codes, missing).getJSONObject("hourly")
                .getJSONArray("weather_code").getInt(0))
            listOf(45, 56, 66, 71, 95).forEach { providerCode ->
                assertEquals(providerCode, phenomenonFixture(codes, missing, providerCode)
                    .getJSONObject("hourly").getJSONArray("weather_code").getInt(0))
            }
        }
    }

    private fun phenomenonFixture(
        codes: List<Any>, missingFields: Set<String>, providerCode: Int = 0,
    ): JSONObject {
        val base = JSONObject(BASE).also { root ->
            root.getJSONObject("hourly").getJSONArray("weather_code").put(0, providerCode)
            root.getJSONObject("hourly").getJSONArray("precipitation_probability").put(0, 3)
        }
        val models = JSONObject(MODELS).also { root ->
            val hourly = root.getJSONObject("hourly")
            listOf("a", "b", "c", "d").forEachIndexed { index, suffix ->
                hourly.put("weather_code_$suffix", JSONArray(listOf(codes.getOrNull(index), 0)))
                missingFields.forEach { field -> hourly.remove("${field}_$suffix") }
            }
        }
        return JSONObject(blendModelForecast(base.toString(), models.toString()).json)
    }

    @Test
    fun incompleteCodeCoverageKeepsProviderHazardsInsteadOfInventingRain() {
        listOf(71, 66, 56, 95, 45).forEach { providerCode ->
            val base = JSONObject(BASE).also { root ->
                root.getJSONObject("hourly").getJSONArray("weather_code").put(1, providerCode)
            }
            val models = JSONObject(MODELS).also { root ->
                root.getJSONObject("hourly").remove("weather_code_b")
                root.getJSONObject("hourly").remove("weather_code_c")
            }

            val result = JSONObject(blendModelForecast(base.toString(), models.toString()).json)

            assertEquals(providerCode, result.getJSONObject("hourly").getJSONArray("weather_code").getInt(1))
        }
    }

    @Test
    fun majorityFreezingWeatherRetainsItsPhase() {
        listOf(66, 56).forEach { code ->
            val models = JSONObject(MODELS).also { root ->
                val hourly = root.getJSONObject("hourly")
                listOf("a", "b", "c").forEach { suffix ->
                    hourly.getJSONArray("weather_code_$suffix").put(1, code)
                }
            }

            val result = JSONObject(blendModelForecast(BASE, models.toString()).json)

            assertEquals(code, result.getJSONObject("hourly").getJSONArray("weather_code").getInt(1))
        }
    }

    @Test
    fun precedingHourRainDoesNotOverrideInstantaneousDryConditions() {
        listOf(0.1, 0.4).forEach { wetAmount ->
            val models = JSONObject(MODELS).also { root ->
                val hourly = root.getJSONObject("hourly")
                listOf("a", "b", "c", "d").forEachIndexed { index, suffix ->
                    hourly.put("precipitation_$suffix", JSONArray(listOf(0.0, if (index < 2) 0.0 else wetAmount)))
                    hourly.put("cloud_cover_$suffix", JSONArray(listOf(10, 100)))
                    hourly.put("weather_code_$suffix", JSONArray(listOf(0, 3)))
                }
            }

            val result = JSONObject(blendModelForecast(BASE, models.toString()).json)
            val hourly = result.getJSONObject("hourly")

            assertEquals(wetAmount / 2, hourly.getJSONArray("precipitation").getDouble(1), 0.0)
            assertEquals(3, hourly.getJSONArray("weather_code").getInt(1))
            assertEquals(0, hourly.getJSONArray("precipitation_probability").getInt(1))
        }
    }

    @Test
    fun mixedLiquidConditionMajorityDoesNotBecomeDrySky() {
        for (amount in listOf(0.0, 0.2)) {
            val models = JSONObject(MODELS).also { root ->
                val hourly = root.getJSONObject("hourly")
                listOf(51, 51, 61, 80).forEachIndexed { index, code ->
                    val suffix = listOf("a", "b", "c", "d")[index]
                    hourly.put("weather_code_$suffix", JSONArray(listOf(code, code)))
                        .put("precipitation_$suffix", JSONArray(listOf(amount, amount)))
                }
            }
            val result = JSONObject(blendModelForecast(BASE, models.toString()).json)
                .getJSONObject("hourly")
            assertEquals("amount=$amount", 51, result.getJSONArray("weather_code").getInt(0))
            assertEquals("amount=$amount", amount, result.getJSONArray("precipitation").getDouble(0), 0.0)
        }
    }

    @Test
    fun modelTimesWithDifferentOrInvalidUtcOffsetsKeepTheProviderForecast() {
        val base = JSONObject(BASE).put("utc_offset_seconds", 7200).toString()
        for (offset in listOf(3600, JSONObject.NULL, "7200", 7200.5, 64801)) {
            val models = JSONObject(MODELS).put("utc_offset_seconds", offset).toString()
            val result = blendModelForecast(base, models)
            assertEquals(base, result.json)
            assertEquals(ForecastCalculationMode.BEST_MATCH, result.mode)
            assertEquals(ForecastFallbackReason.PROVIDER_UNAVAILABLE, result.fallbackReason)
        }
        val valid = JSONObject(MODELS).put("utc_offset_seconds", 7200.0).toString()
        assertEquals(ForecastCalculationMode.DIAGNOSTIC_MEDIAN, blendModelForecast(base, valid).mode)
    }

    @Test
    fun preservesCurrentIntervalAggregatesWhileBlendingHourlyTotals() {
        val base = JSONObject(BASE).also { root ->
            root.getJSONObject("current")
                .put("interval", 900).put("precipitation", 0.1).put("rain", 0.05)
                .put("snowfall", 0.02).put("wind_gusts_10m", 8.0)
            root.getJSONObject("hourly")
                .put("rain", JSONArray(listOf(0.0, 0.0)))
                .put("snowfall", JSONArray(listOf(0.0, 0.0)))
        }
        val models = JSONObject(MODELS).also { root ->
            listOf("a", "b", "c").forEach { suffix ->
                val hourly = root.getJSONObject("hourly")
                listOf("precipitation", "rain", "snowfall").forEach { field ->
                    hourly.put("${field}_$suffix", JSONArray(listOf(1.2, 1.2)))
                }
            }
        }

        val result = JSONObject(blendModelForecast(base.toString(), models.toString()).json)
        val current = result.getJSONObject("current")

        assertEquals(900, current.getInt("interval"))
        assertEquals(0.1, current.getDouble("precipitation"), 0.0)
        assertEquals(0.05, current.getDouble("rain"), 0.0)
        assertEquals(0.02, current.getDouble("snowfall"), 0.0)
        assertEquals(8.0, current.getDouble("wind_gusts_10m"), 0.0)
        assertEquals(1.2, result.getJSONObject("hourly").getJSONArray("precipitation").getDouble(0), 0.0)
        assertEquals(16.0, result.getJSONObject("hourly").getJSONArray("wind_gusts_10m").getDouble(0), 0.0)
    }

    @Test
    fun preservesProviderDailyTotalWhenHourlyAmountsAreIncomplete() {
        listOf("precipitation", "rain", "snowfall").forEach { field ->
            val models = JSONObject(MODELS).also { root ->
                listOf("a", "b", "c").forEach { suffix ->
                    root.getJSONObject("hourly").remove("${field}_$suffix")
                    // No ensemble total is selected, so the whole provider group remains valid.
                    root.getJSONObject("hourly").remove("precipitation_$suffix")
                }
            }.toString()
            listOf(
                "[null,null]" to 4.2,
                "[0.2,null]" to 4.2,
                "[0.2,0.3]" to 4.2,
            ).forEach { (amounts, expected) ->
                val base = JSONObject(BASE).also { root ->
                    root.getJSONObject("hourly").put(field, JSONArray(amounts))
                    root.getJSONObject("daily").put("${field}_sum", JSONArray(listOf(4.2)))
                }

                val result = JSONObject(blendModelForecast(base.toString(), models).json)

                assertEquals(
                    expected,
                    result.getJSONObject("daily").getJSONArray("${field}_sum").getDouble(0),
                    0.0001,
                )
            }
        }
    }

    @Test
    fun dailyPrecipitationUsesAllCalendarDayIntervalsIncludingNextMidnight() {
        listOf("2026-08-29", "2026-03-29", "2026-10-25", "2026-12-31").forEach { date ->
            val amounts = List<Double?>(25) { index ->
                when (index) { 0 -> 99.0; 1 -> 2.0; 24 -> 5.0; else -> 0.0 }
            }
            val daily = dailyPrecipitationFixture(date, amounts).getJSONObject("daily")
            listOf("precipitation_sum", "rain_sum").forEach { field ->
                assertEquals("$date $field", 7.0, daily.getJSONArray(field).getDouble(0), 0.0)
                assertEquals(100.0, daily.getJSONArray(field).getDouble(1), 0.0)
            }
            assertEquals(0.0, daily.getJSONArray("snowfall_sum").getDouble(0), 0.0)
            assertEquals(90, daily.getJSONArray("precipitation_probability_max").getInt(0))
            val dry = dailyPrecipitationFixture(date, List(25) { 0.0 }).getJSONObject("daily")
            listOf("precipitation_sum", "rain_sum", "snowfall_sum").forEach { field ->
                assertEquals(0.0, dry.getJSONArray(field).getDouble(0), 0.0)
            }
        }
    }

    @Test
    fun incompleteCalendarDayDoesNotTurnMissingPrecipitationIntoZeroOrPartialTotal() {
        listOf(List<Double?>(24) { 0.0 }, List<Double?>(25) { if (it == 24) null else 0.0 }).forEach { amounts ->
            val daily = dailyPrecipitationFixture("2026-08-29", amounts).getJSONObject("daily")
            assertEquals(100.0, daily.getJSONArray("precipitation_sum").getDouble(0), 0.0)
            assertTrue(daily.getJSONArray("rain_sum").isNull(0))
            assertTrue(daily.getJSONArray("snowfall_sum").isNull(0))
            assertEquals(if (amounts.size == 25) 90 else 80,
                daily.getJSONArray("precipitation_probability_max").getInt(0))
        }
    }

    @Test
    fun blendedDailyRainDoesNotRetainUnrelatedProviderWetDuration() {
        val dry = dailyPrecipitationFixture("2026-08-29", List(25) { 0.0 }).getJSONObject("daily")
        assertEquals(0.0, dry.getJSONArray("precipitation_sum").getDouble(0), 0.0)
        assertTrue(dry.getJSONArray("precipitation_hours").isNull(0))
        assertEquals(12.0, dry.getJSONArray("precipitation_hours").getDouble(1), 0.0)
        val incomplete = dailyPrecipitationFixture("2026-08-29", List(24) { 0.0 }).getJSONObject("daily")
        assertEquals(12.0, incomplete.getJSONArray("precipitation_hours").getDouble(0), 0.0)
        val providerOnly = dailyPrecipitationFixture("2026-08-29", List(25) { 0.0 }, blendRain = false).getJSONObject("daily")
        assertEquals(12.0, providerOnly.getJSONArray("precipitation_hours").getDouble(0), 0.0)
    }

    @Test
    fun invalidFallbackWindCannotReplaceValidDailyProviderValues() {
        listOf(-10.0 to 0.0, 10.0 to 400.0).forEach { (speed, direction) ->
            val base = JSONObject(BASE)
            base.getJSONObject("hourly").put("wind_speed_10m", JSONArray(listOf(speed, speed)))
                .put("wind_direction_10m", JSONArray(listOf(direction, direction)))
                .put("wind_gusts_10m", JSONArray(listOf(-15.0, -15.0)))
            val models = JSONObject(MODELS)
            listOf("a", "b", "c").forEach { suffix ->
                models.getJSONObject("hourly").remove("wind_speed_10m_$suffix")
                models.getJSONObject("hourly").remove("wind_direction_10m_$suffix")
                models.getJSONObject("hourly").remove("wind_gusts_10m_$suffix")
            }
            val daily = JSONObject(blendModelForecast(base.toString(), models.toString()).json).getJSONObject("daily")
            assertEquals(90, daily.getJSONArray("wind_direction_10m_dominant").getInt(0))
            assertEquals(if (speed < 0) 5.0 else speed, daily.getJSONArray("wind_speed_10m_max").getDouble(0), 0.0)
            assertEquals(8.0, daily.getJSONArray("wind_gusts_10m_max").getDouble(0), 0.0)
        }
    }

    private fun dailyPrecipitationFixture(date: String, amounts: List<Double?>, blendRain: Boolean = true): JSONObject {
        val start = LocalDate.parse(date).atStartOfDay()
        val times = JSONArray(List(amounts.size) { start.plusHours(it.toLong()).toString() })
        val base = precipitationBase().put("utc_offset_seconds", 3600)
        val models = JSONObject(MODELS)
        listOf(base.getJSONObject("hourly"), models.getJSONObject("hourly")).forEach { hourly ->
            hourly.keys().asSequence().toList().filter { it != "time" }.forEach { field ->
                val value = hourly.getJSONArray(field).opt(0)
                hourly.put(field, JSONArray(List(amounts.size) { value }))
            }
            hourly.put("time", times)
        }
        base.getJSONObject("current").put("time", "${date}T12:15")
        base.getJSONObject("hourly").put("precipitation_probability",
            JSONArray(List(amounts.size) { if (it == 0) 100 else if (it == 24) 90 else 10 }))
        val daily = base.getJSONObject("daily")
        daily.put("time", JSONArray(listOf(date, start.plusDays(1).toLocalDate().toString())))
        listOf("precipitation_sum", "rain_sum", "snowfall_sum").forEach { field ->
            daily.put(field, JSONArray(listOf(100.0, 100.0)))
        }
        daily.put("precipitation_probability_max", JSONArray(listOf(80, 80)))
        daily.put("precipitation_hours", JSONArray(listOf(12.0, 12.0)))
        base.getJSONObject("hourly").put("snowfall", JSONArray(amounts.map { if (it == null) null else 0.0 }))
        listOf("a", "b", "c").forEach { suffix ->
            listOf("precipitation", "rain").forEach { field ->
                base.getJSONObject("hourly").put(field, JSONArray(amounts))
                models.getJSONObject("hourly").put("${field}_$suffix", JSONArray(amounts))
            }
            models.getJSONObject("hourly").put("snowfall_$suffix",
                JSONArray(amounts.map { if (it == null) null else 0.0 }))
            if (!blendRain) models.getJSONObject("hourly").remove("precipitation_$suffix")
        }
        return JSONObject(blendModelForecast(base.toString(), models.toString()).json)
    }

    @Test
    fun precipitationComponentsFollowTheMedianTotalContributorNotIndependentMedians() {
        val result = JSONObject(blendModelForecast(precipitationBase().toString(), precipitationModels(
            listOf(0.05, 0.1, 0.2),
            mapOf("rain" to listOf(0.05, 0.0, 0.0), "showers" to listOf(0.0, 0.1, 0.0),
                "snowfall" to listOf(0.0, 0.0, 0.14)),
        )).json)
        val hourly = result.getJSONObject("hourly")
        // Independent medians would incorrectly return zero for every component.
        assertEquals(0.1, hourly.getJSONArray("precipitation").getDouble(0), 0.0)
        assertEquals(0.0, hourly.getJSONArray("rain").getDouble(0), 0.0)
        assertEquals(0.1, hourly.getJSONArray("showers").getDouble(0), 0.0)
        assertEquals(0.0, hourly.getJSONArray("snowfall").getDouble(0), 0.0)
        assertEquals(3, hourly.getJSONArray("precipitation_probability").getInt(0))
        assertEquals(0.0, result.getJSONObject("daily").getJSONArray("precipitation_sum").getDouble(0), 0.0)
        assertEquals(0.0, result.getJSONObject("current").getDouble("precipitation"), 0.0)
    }

    @Test
    fun evenMedianUsesTheSameTwoSourcesForEveryKnownComponentWithoutSnowConversion() {
        val result = JSONObject(blendModelForecast(precipitationBase().toString(), precipitationModels(
            listOf(0.0, 0.1, 0.3, 2.0),
            mapOf("rain" to listOf(0.0, 0.1, 0.0, 2.0), "showers" to listOf(0.0, 0.0, 0.1, 0.0),
                "snowfall" to listOf(0.0, 0.0, 0.14, 0.0)),
        )).json)
        val hourly = result.getJSONObject("hourly")
        assertEquals(0.2, hourly.getJSONArray("precipitation").getDouble(0), 1e-12)
        assertEquals(0.05, hourly.getJSONArray("rain").getDouble(0), 1e-12)
        assertEquals(0.05, hourly.getJSONArray("showers").getDouble(0), 1e-12)
        assertEquals(0.07, hourly.getJSONArray("snowfall").getDouble(0), 1e-12)
        assertTrue(result.getJSONObject("daily").getJSONArray("snowfall_sum").isNull(0))
    }

    @Test
    fun missingOrInvalidSelectedComponentStaysUnknownHourlyAndDaily() {
        listOf<Double?>(null, -0.1).forEach { missing ->
            val result = JSONObject(blendModelForecast(precipitationBase().toString(), precipitationModels(
                listOf(0.0, 0.1, 0.3, 2.0),
                mapOf("rain" to listOf(0.0, 0.1, missing, 2.0),
                    "snowfall" to listOf(0.0, 0.0, missing, 0.0)),
            )).json)
            val hourly = result.getJSONObject("hourly")
            assertEquals(0.2, hourly.getJSONArray("precipitation").getDouble(0), 1e-12)
            assertTrue(hourly.getJSONArray("rain").isNull(0))
            assertTrue(hourly.getJSONArray("showers").isNull(0))
            assertTrue(hourly.getJSONArray("snowfall").isNull(0))
            assertTrue(result.getJSONObject("daily").getJSONArray("rain_sum").isNull(0))
            assertTrue(result.getJSONObject("daily").getJSONArray("snowfall_sum").isNull(0))
            assertEquals(3, hourly.getJSONArray("precipitation_probability").getInt(0))
        }
    }

    @Test
    fun insufficientTotalContributorsKeepTheWholeProviderPrecipitationGroup() {
        val base = precipitationBase().also { root ->
            root.getJSONObject("hourly").getJSONArray("precipitation").put(0, 0.2)
            root.getJSONObject("hourly").getJSONArray("rain").put(0, 0.2)
        }
        val models = JSONObject(precipitationModels(
            listOf(1.0, 2.0, 3.0),
            mapOf("rain" to listOf(1.0, 2.0, 3.0)),
        )).also { it.getJSONObject("hourly").getJSONArray("precipitation_c").put(0, JSONObject.NULL) }
        val hourly = JSONObject(blendModelForecast(base.toString(), models.toString()).json).getJSONObject("hourly")
        assertEquals(0.2, hourly.getJSONArray("precipitation").getDouble(0), 0.0)
        assertEquals(0.2, hourly.getJSONArray("rain").getDouble(0), 0.0)
        assertEquals(0.0, hourly.getJSONArray("showers").getDouble(0), 0.0)
    }

    private fun precipitationBase(): JSONObject = JSONObject(BASE).also { root ->
        listOf("rain", "showers", "snowfall").forEach { field ->
            root.getJSONObject("hourly").put(field, JSONArray(listOf(0.0, 0.0)))
        }
        root.getJSONObject("hourly").getJSONArray("precipitation_probability").put(0, 3)
        root.getJSONObject("daily").put("rain_sum", JSONArray(listOf(4.2)))
            .put("snowfall_sum", JSONArray(listOf(4.2)))
    }

    @Test
    fun precipitationSpreadRetainsMinorityTraceWithoutChangingDryMedianOrProbability() {
        val result = JSONObject(blendModelForecast(precipitationBase().toString(), precipitationModels(
            listOf(0.0, 0.0, 0.03),
            mapOf("rain" to listOf(0.0, 0.0, 0.03), "showers" to listOf(0.0, 0.0, 0.0),
                "snowfall" to listOf(0.0, 0.0, 0.0)),
        )).json)
        val hourly = result.getJSONObject("hourly")
        val spreads = hourly.getJSONArray(PRECIPITATION_SPREAD_KEY)
        assertEquals(hourly.getJSONArray("time").length(), spreads.length())
        val spread = spreads.getJSONObject(0)
        assertEquals(3, spread.getInt("model_count"))
        assertEquals(1, spread.getInt("wet_model_count"))
        assertEquals(0.0, spread.getDouble("minimum_mm"), 0.0)
        assertEquals(0.03, spread.getDouble("maximum_mm"), 0.0)
        assertEquals(0.0, hourly.getJSONArray("precipitation").getDouble(0), 0.0)
        assertEquals(3, hourly.getJSONArray("precipitation_probability").getInt(0))
        listOf("rain", "showers", "snowfall").forEach { field ->
            assertEquals(0.0, hourly.getJSONArray(field).getDouble(0), 0.0)
        }
        assertEquals(0.0, result.getJSONObject("daily").getJSONArray("precipitation_sum").getDouble(0), 0.0)
        assertEquals(0.0, result.getJSONObject("current").getDouble("precipitation"), 0.0)
    }

    @Test
    fun precipitationSpreadExcludesInvalidMembersAndKeepsMissingHourAligned() {
        listOf(JSONObject.NULL, "NaN", -0.1).forEach { invalid ->
            val models = JSONObject(precipitationModels(listOf(0.0, 0.03, 0.1, 0.2), emptyMap()))
            val source = models.getJSONObject("hourly")
            source.getJSONArray("precipitation_d").put(0, invalid)
            val spread = JSONObject(blendModelForecast(precipitationBase().toString(), models.toString()).json)
                .getJSONObject("hourly").getJSONArray(PRECIPITATION_SPREAD_KEY).getJSONObject(0)
            assertEquals(3, spread.getInt("model_count"))
            assertEquals(2, spread.getInt("wet_model_count"))
            assertEquals(0.1, spread.getDouble("maximum_mm"), 0.0)
            source.getJSONArray("precipitation_c").put(0, JSONObject.NULL)
            val aligned = JSONObject(blendModelForecast(precipitationBase().toString(), models.toString()).json)
                .getJSONObject("hourly").getJSONArray(PRECIPITATION_SPREAD_KEY)
            assertEquals(2, aligned.length())
            assertTrue(aligned.isNull(0))
            assertFalse(aligned.isNull(1))
        }
        assertFalse(JSONObject(blendModelForecast(BASE, ONE_MODEL).json)
            .getJSONObject("hourly").has(PRECIPITATION_SPREAD_KEY))
    }

    private fun precipitationModels(totals: List<Double>, components: Map<String, List<Double?>>): String =
        JSONObject(MODELS).also { root ->
            val hourly = root.getJSONObject("hourly")
            totals.forEachIndexed { index, total ->
                val suffix = ('a' + index).toString()
                hourly.put("precipitation_$suffix", JSONArray(listOf(total, 0.0)))
                components.forEach { (field, values) ->
                    hourly.put("${field}_$suffix", JSONArray(listOf(values[index], 0.0)))
                }
            }
        }.toString()

    @Test
    fun precedingHourRainDoesNotOverwriteCurrentClearSky() {
        val base = JSONObject(BASE).also { root ->
            root.getJSONObject("current")
                .put("interval", 900).put("weather_code", 0).put("precipitation", 0.0)
                .put("cloud_cover", 0).put("cloud_cover_low", 0)
                .put("cloud_cover_mid", 0).put("cloud_cover_high", 0)
                .put("temperature_2m", 18.2).put("relative_humidity_2m", 65)
        }
        val models = JSONObject(MODELS).also { root ->
            val hourly = root.getJSONObject("hourly")
            listOf("a", "b", "c").forEach { suffix ->
                hourly.getJSONArray("precipitation_$suffix").put(0, 1.2)
                hourly.getJSONArray("weather_code_$suffix").put(0, 61)
                listOf("cloud_cover", "cloud_cover_low", "cloud_cover_mid", "cloud_cover_high")
                    .forEach { field -> hourly.put("${field}_$suffix", JSONArray(listOf(100, 100))) }
            }
        }

        val result = JSONObject(blendModelForecast(base.toString(), models.toString()).json)
        val current = result.getJSONObject("current")

        assertEquals("2026-08-29T19:15", current.getString("time"))
        assertEquals(900, current.getInt("interval"))
        assertEquals(0, current.getInt("weather_code"))
        assertEquals(0.0, current.getDouble("precipitation"), 0.0)
        assertEquals(18.2, current.getDouble("temperature_2m"), 0.0)
        assertEquals(65, current.getInt("relative_humidity_2m"))
        assertEquals(5.0, current.getDouble("wind_speed_10m"), 0.0)
        assertEquals(180, current.getInt("wind_direction_10m"))
        listOf("cloud_cover", "cloud_cover_low", "cloud_cover_mid", "cloud_cover_high")
            .forEach { field -> assertEquals(0, current.getInt(field)) }
        assertEquals(61, result.getJSONObject("hourly").getJSONArray("weather_code").getInt(0))
        assertEquals(1.2, result.getJSONObject("hourly").getJSONArray("precipitation").getDouble(0), 0.0)
    }

    @Test
    fun exactHourlyTimestampStillUsesInstantaneousModelBlend() {
        val base = JSONObject(BASE).also { root ->
            root.getJSONObject("current").put("time", "2026-08-29T19:00")
        }

        val current = JSONObject(blendModelForecast(base.toString(), MODELS).json)
            .getJSONObject("current")

        assertEquals(22.0, current.getDouble("temperature_2m"), 0.0)
        assertEquals(0, current.getInt("wind_direction_10m"))
        assertEquals(1014.0, current.getDouble("pressure_msl"), 0.0)
        assertEquals(3, current.getInt("weather_code"))
        assertEquals(100, current.getInt("cloud_cover"))
    }

    @Test
    fun preservesProviderProbabilityRegardlessOfDeterministicModelAgreement() {
        val base = JSONObject(BASE).also { root ->
            root.getJSONObject("hourly").getJSONArray("precipitation_probability")
                .put(0, 35).put(1, 45)
            root.getJSONObject("daily").getJSONArray("precipitation_probability_max").put(0, 65)
        }.toString()
        listOf(0.0, 0.2, JSONObject.NULL).forEach { precipitation ->
            val models = JSONObject(MODELS).also { root ->
                listOf("a", "b", "c").forEach { suffix ->
                    root.getJSONObject("hourly").getJSONArray("precipitation_$suffix")
                        .put(0, precipitation).put(1, precipitation)
                }
            }

            val result = JSONObject(blendModelForecast(base, models.toString()).json)
            val probabilities = result.getJSONObject("hourly").getJSONArray("precipitation_probability")

            assertEquals(35, probabilities.getInt(0))
            assertEquals(45, probabilities.getInt(1))
            assertEquals(65, result.getJSONObject("daily").getJSONArray("precipitation_probability_max").getInt(0))
        }
    }

    @Test
    fun regionalBlendWithoutVerifiedCalibrationKeepsDiagnosticProvenance() {
        val result = blendModelForecast(BASE, MODELS, PRAGUE, calibration = null)

        assertEquals(ForecastCalculationMode.DIAGNOSTIC_MEDIAN, result.mode)
        assertEquals(listOf("a", "b", "c"), result.contributorIds)
        assertTrue(result.appliedWeights.isEmpty())
        assertEquals(null, result.truthClass)
        assertEquals(null, result.artifactVersion)
        assertEquals(null, result.artifactGeneratedAtEpochSeconds)
    }

    @Test
    fun liveSeriesWithoutIssuedRunTimesCannotUseLearnedWeights() {
        val artifact = parseCalibrationArtifact(
            CALIBRATION,
            Instant.parse("2026-08-29T19:00:00Z").epochSecond,
        )

        val result = blendModelForecast(BASE, MODELS, PRAGUE, artifact)
        val root = JSONObject(result.json)

        assertEquals(22.0, root.getJSONObject("hourly").getJSONArray("temperature_2m").getDouble(0), 0.0001)
        assertEquals(99.0, root.getJSONObject("current").getDouble("temperature_2m"), 0.0)
        assertEquals(ForecastCalculationMode.DIAGNOSTIC_MEDIAN, result.mode)
        assertTrue(result.appliedWeights.isEmpty())
    }

    @Test
    fun majorityDrizzleIsNotErasedByTheMeasurableRainThreshold() {
        listOf(
            listOf(51, 53, 55) to 53,
            listOf(51, 53, 0) to 51,
            listOf(55, 55, 0) to 55,
            listOf(53, 53, 0) to 53,
            listOf(51, 0, 0) to 0,
        )
            .forEach { (codes, expectedCode) ->
                val base = JSONObject(BASE).also { root ->
                    root.getJSONObject("hourly").getJSONArray("precipitation_probability").put(0, 3)
                }
                val models = JSONObject(MODELS).also { root ->
                    val hourly = root.getJSONObject("hourly")
                    listOf("a", "b", "c").forEachIndexed { index, suffix ->
                        hourly.getJSONArray("weather_code_$suffix").put(0, codes[index])
                        hourly.getJSONArray("precipitation_$suffix").put(0, 0.03)
                    }
                }

                val hourly = JSONObject(blendModelForecast(base.toString(), models.toString()).json)
                    .getJSONObject("hourly")

                assertEquals(expectedCode, hourly.getJSONArray("weather_code").getInt(0))
                assertEquals(0.03, hourly.getJSONArray("precipitation").getDouble(0), 0.0)
                assertEquals(3, hourly.getJSONArray("precipitation_probability").getInt(0))
            }
    }

    @Test
    fun blendedCloudCoverUpdatesBenignSkyCodeWhenCodesAndPrecipitationContributorsAreMissing() {
        listOf(10 to 0, 40 to 1, 70 to 2, 95 to 3).forEach { (cloudCover, expectedCode) ->
            val base = JSONObject(BASE).also { root ->
                root.getJSONObject("hourly").apply {
                    getJSONArray("weather_code").put(0, 0)
                    getJSONArray("precipitation_probability").put(0, 3)
                    put("cloud_cover_low", JSONArray(listOf(0, 0)))
                    put("cloud_cover_mid", JSONArray(listOf(85, 0)))
                    put("cloud_cover_high", JSONArray(listOf(100, 0)))
                }
            }
            val models = JSONObject(MODELS).also { root ->
                root.getJSONObject("hourly").apply {
                    getJSONArray("precipitation_c").put(0, JSONObject.NULL)
                    listOf("a", "b", "c").forEach { suffix ->
                        getJSONArray("cloud_cover_$suffix").put(0, cloudCover)
                        remove("weather_code_$suffix")
                    }
                }
            }

            val hourly = JSONObject(blendModelForecast(base.toString(), models.toString()).json)
                .getJSONObject("hourly")

            assertEquals(expectedCode, hourly.getJSONArray("weather_code").getInt(0))
            assertEquals(cloudCover, hourly.getJSONArray("cloud_cover").getInt(0))
            assertEquals(3, hourly.getJSONArray("precipitation_probability").getInt(0))
            assertEquals(0.0, hourly.getJSONArray("precipitation").getDouble(0), 0.0)
            // Selected models do not supply layers; unrelated Best Match layers must not leak.
            assertTrue(hourly.getJSONArray("cloud_cover_low").isNull(0))
            assertTrue(hourly.getJSONArray("cloud_cover_mid").isNull(0))
            assertTrue(hourly.getJSONArray("cloud_cover_high").isNull(0))
        }
    }

    @Test
    fun cloudLayersFollowTheOddMedianTotalInsteadOfIndependentMemberMasks() {
        val base = cloudBase()
        val result = JSONObject(blendModelForecast(base.toString(), cloudModels(
            listOf(0.0, 0.0, 0.0, 100.0, 100.0),
            mapOf(
                "cloud_cover_low" to listOf(0.0, 0.0, 0.0, 0.0, 0.0),
                "cloud_cover_mid" to listOf(null, null, 0.0, 85.0, 85.0),
                "cloud_cover_high" to listOf(null, null, 0.0, 100.0, 100.0),
            ),
        )).json)
        val hourly = result.getJSONObject("hourly")
        assertEquals(0, hourly.getJSONArray("weather_code").getInt(0))
        listOf("cloud_cover", "cloud_cover_low", "cloud_cover_mid", "cloud_cover_high").forEach { field ->
            assertEquals(0.0, hourly.getJSONArray(field).getDouble(0), 0.0)
            assertEquals(base.getJSONObject("current").getInt(field), result.getJSONObject("current").getInt(field))
        }
        assertEquals(3, result.getJSONObject("current").getInt("weather_code"))
        assertEquals(3, hourly.getJSONArray("precipitation_probability").getInt(0))
        assertEquals(0.0, hourly.getJSONArray("precipitation").getDouble(0), 0.0)
        assertEquals(5, hourly.getJSONArray(PRECIPITATION_SPREAD_KEY).getJSONObject(0).getInt("model_count"))
        assertEquals(0.0, result.getJSONObject("daily").getJSONArray("precipitation_sum").getDouble(0), 0.0)
    }

    @Test
    fun missingMedianCloudLayersStayUnknownRatherThanBorrowingBestMatch() {
        val hourly = JSONObject(blendModelForecast(cloudBase().toString(), cloudModels(
            listOf(0.0, 10.0, 20.0), emptyMap(),
        )).json).getJSONObject("hourly")
        assertEquals(10.0, hourly.getJSONArray("cloud_cover").getDouble(0), 0.0)
        listOf("cloud_cover_low", "cloud_cover_mid", "cloud_cover_high").forEach { field ->
            assertTrue(hourly.getJSONArray(field).isNull(0))
        }
    }

    @Test
    fun evenCloudMedianUsesBothMiddleSourcesAndInvalidSelectedLayersStayNull() {
        val models = JSONObject(cloudModels(
            listOf(0.0, 20.0, 60.0, 100.0),
            mapOf("cloud_cover_high" to listOf(0.0, 10.0, 50.0, 90.0)),
        ))
        val hourly = JSONObject(blendModelForecast(cloudBase().toString(), models.toString()).json)
            .getJSONObject("hourly")
        assertEquals(40.0, hourly.getJSONArray("cloud_cover").getDouble(0), 0.0)
        assertEquals(30.0, hourly.getJSONArray("cloud_cover_high").getDouble(0), 0.0)
        listOf(JSONObject.NULL, "NaN", -1, 101).forEach { invalid ->
            models.getJSONObject("hourly").getJSONArray("cloud_cover_high_b").put(0, invalid)
            val changed = JSONObject(blendModelForecast(cloudBase().toString(), models.toString()).json)
                .getJSONObject("hourly")
            assertEquals(40.0, changed.getJSONArray("cloud_cover").getDouble(0), 0.0)
            assertTrue(changed.getJSONArray("cloud_cover_high").isNull(0))
        }
    }

    @Test
    fun invalidCloudTotalsAreExcludedAndInsufficientTotalsKeepWholeProviderGroup() {
        listOf(JSONObject.NULL, "NaN", -1, 101).forEach { invalid ->
            val base = cloudBase()
            val models = JSONObject(cloudModels(
                listOf(0.0, 20.0, 60.0, 100.0),
                mapOf("cloud_cover_high" to listOf(0.0, 10.0, 50.0, 90.0)),
            ))
            val source = models.getJSONObject("hourly")
            source.getJSONArray("cloud_cover_b").put(0, invalid)
            val hourly = JSONObject(blendModelForecast(base.toString(), models.toString()).json).getJSONObject("hourly")
            assertEquals(60.0, hourly.getJSONArray("cloud_cover").getDouble(0), 0.0)
            assertEquals(50.0, hourly.getJSONArray("cloud_cover_high").getDouble(0), 0.0)
            source.getJSONArray("cloud_cover_c").put(0, JSONObject.NULL)
            val fallback = JSONObject(blendModelForecast(base.toString(), models.toString()).json).getJSONObject("hourly")
            listOf("cloud_cover", "cloud_cover_low", "cloud_cover_mid", "cloud_cover_high").forEach { field ->
                assertEquals(base.getJSONObject("hourly").getJSONArray(field).getDouble(0), fallback.getJSONArray(field).getDouble(0), 0.0)
            }
        }
    }

    @Test
    fun cloudGroupingPreservesProviderNonUnionDefinitions() {
        val hourly = JSONObject(blendModelForecast(cloudBase().toString(), cloudModels(
            listOf(0.0, 30.0, 60.0),
            mapOf("cloud_cover_low" to listOf(0.0, 0.0, 0.0),
                "cloud_cover_mid" to listOf(0.0, 0.0, 50.0),
                "cloud_cover_high" to listOf(0.0, 100.0, 100.0)),
        )).json).getJSONObject("hourly")
        assertEquals(30.0, hourly.getJSONArray("cloud_cover").getDouble(0), 0.0)
        assertEquals(100.0, hourly.getJSONArray("cloud_cover_high").getDouble(0), 0.0)
        assertEquals(3, hourly.getJSONArray("weather_code").getInt(0))
    }

    private fun cloudBase(): JSONObject = JSONObject(BASE).also { root ->
        root.getJSONObject("current").put("time", "2026-08-29T19:00")
        mapOf("cloud_cover_low" to 15, "cloud_cover_mid" to 85, "cloud_cover_high" to 100)
            .forEach { (field, value) ->
                root.getJSONObject("hourly").put(field, JSONArray(listOf(value, value)))
                root.getJSONObject("current").put(field, value)
            }
        root.getJSONObject("hourly").getJSONArray("precipitation_probability").put(0, 3)
    }

    private fun cloudModels(totals: List<Double>, layers: Map<String, List<Double?>>): String =
        JSONObject(MODELS).also { root ->
            val hourly = root.getJSONObject("hourly")
            totals.forEachIndexed { index, total ->
                val suffix = ('a' + index).toString()
                hourly.put("temperature_2m_$suffix", JSONArray(listOf(20, 20)))
                hourly.put("cloud_cover_$suffix", JSONArray(listOf(total, total)))
                hourly.put("precipitation_$suffix", JSONArray(listOf(0.0, 0.0)))
                hourly.put("weather_code_$suffix", JSONArray(listOf(if (total <= 20) 0 else 3, 3)))
                layers.forEach { (field, values) ->
                    hourly.put("${field}_$suffix", JSONArray(listOf(values[index], values[index])))
                }
            }
        }.toString()

    @Test
    fun cloudOnlyBlendPreservesProviderDrizzleRainAndHazards() {
        listOf(51, 61, 71, 66, 95, 45).forEach { providerCode ->
            val base = JSONObject(BASE).also { root ->
                root.getJSONObject("hourly").getJSONArray("weather_code").put(0, providerCode)
            }
            val models = JSONObject(MODELS).also { root ->
                root.getJSONObject("hourly").getJSONArray("precipitation_c").put(0, JSONObject.NULL)
            }

            val hourly = JSONObject(blendModelForecast(base.toString(), models.toString()).json)
                .getJSONObject("hourly")

            assertEquals(providerCode, hourly.getJSONArray("weather_code").getInt(0))
            assertEquals(10, hourly.getJSONArray("cloud_cover").getInt(0))
        }
    }

    @Test
    fun cloudOnlyBlendDoesNotTurnPrecedingHourRainIntoAnInstantRainCondition() {
        val base = JSONObject(BASE).also { root ->
            root.getJSONObject("hourly").apply {
                getJSONArray("weather_code").put(0, 3)
                getJSONArray("precipitation").put(0, 0.2)
                getJSONArray("precipitation_probability").put(0, 3)
            }
        }
        val models = JSONObject(MODELS).also { root ->
            root.getJSONObject("hourly").getJSONArray("precipitation_c").put(0, JSONObject.NULL)
        }

        val hourly = JSONObject(blendModelForecast(base.toString(), models.toString()).json)
            .getJSONObject("hourly")

        assertEquals(0, hourly.getJSONArray("weather_code").getInt(0))
        assertEquals(0.2, hourly.getJSONArray("precipitation").getDouble(0), 0.0)
        assertEquals(3, hourly.getJSONArray("precipitation_probability").getInt(0))
    }

    @Test
    fun appliesIssuedValuesAtTheRequestedLocationUsingActualRunLead() {
        val result = issuedBlend(issuedValues())
        val hourly = JSONObject(result.json).getJSONObject("hourly")

        assertEquals(21.2, hourly.getJSONArray("temperature_2m").getDouble(0), 1e-9)
        assertEquals(20.0, hourly.getJSONArray("temperature_2m").getDouble(1), 1e-9)
        assertEquals(ForecastCalculationMode.CALIBRATED, result.mode)
        assertEquals(mapOf("a" to 0.4, "b" to 0.6), result.appliedWeights)
        assertEquals(CalibrationTruthClass.STATION, result.truthClass)
        assertEquals("2026-08-29T19:00", result.calibrationAppliedAt)
        assertEquals("temperature_2m", result.calibrationVariable)
        assertEquals(1, result.calibratedValueCount)
    }

    @Test
    fun reportsFutureOnlyCalibrationWithoutClaimingItChangedCurrentConditions() {
        val result = issuedBlend(issuedValues().map { it.copy(validTime = it.validTime.plusSeconds(3600)) })
        assertEquals(ForecastCalculationMode.CALIBRATED, result.mode)
        assertEquals("2026-08-29T20:00", result.calibrationAppliedAt)
        assertEquals(1, result.calibratedValueCount)
        val root = JSONObject(result.json)
        assertEquals(99.0, root.getJSONObject("current").getDouble("temperature_2m"), 0.0)
        assertEquals(22.0, root.getJSONObject("hourly").getJSONArray("temperature_2m").getDouble(0), 0.0)
        assertEquals(21.2, root.getJSONObject("hourly").getJSONArray("temperature_2m").getDouble(1), 1e-9)
    }

    @Test
    fun rejectsStaleFutureWrongLocationUnitAndOutOfLeadIssuedValues() {
        val values = issuedValues()
        val invalid = listOf(
            values.map { it.copy(runTime = Instant.parse("2026-08-28T00:00:00Z")) },
            values.map { it.copy(runTime = Instant.parse("2026-08-29T18:00:00Z")) },
            values.map { it.copy(latitude = 51.0) },
            values.map { it.copy(unit = "K") },
            values.take(1),
        )
        invalid.forEach { rows ->
            val result = issuedBlend(rows)
            assertEquals(ForecastCalculationMode.DIAGNOSTIC_MEDIAN, result.mode)
            assertEquals(22.0, JSONObject(result.json).getJSONObject("hourly").getJSONArray("temperature_2m").getDouble(0), 0.0)
        }
        val artifact = JSONObject(CALIBRATION).also {
            it.getJSONArray("segments").getJSONObject(0).getJSONObject("selector")
                .put("minimum_lead_hours", 0).put("maximum_lead_hours", 1)
        }
        assertEquals(ForecastCalculationMode.DIAGNOSTIC_MEDIAN, issuedBlend(values, artifact.toString()).mode)
    }

    @Test
    fun doesNotApplyExpiredWeights() {
        val expired = JSONObject(CALIBRATION).put("expires_at", "2026-08-29T17:00:00Z")
        val parsedBeforeExpiry = parseCalibrationArtifact(expired.toString(), Instant.parse("2026-08-29T16:00:00Z").epochSecond)
        val result = blendModelForecast(BASE, MODELS, PRAGUE, parsedBeforeExpiry, issuedValues(), NOW)
        assertEquals(ForecastCalculationMode.DIAGNOSTIC_MEDIAN, result.mode)
    }

    @Test
    fun doesNotGuessUtcForDaylightSavingOverlapOrGap() {
        listOf("2026-03-29" to "01", "2026-10-25" to "00").forEach { (date, utcHour) ->
            val times = JSONArray(listOf("${date}T02:00", "${date}T03:00"))
            val base = JSONObject(BASE).also {
                it.getJSONObject("current").put("time", "${date}T02:15")
                it.getJSONObject("hourly").put("time", times)
            }
            val models = JSONObject(MODELS).also { it.getJSONObject("hourly").put("time", times) }
            val validTime = Instant.parse("${date}T${utcHour}:00:00Z")
            val now = validTime.plusSeconds(900)
            val artifact = JSONObject(CALIBRATION).also {
                it.put("generated_at", validTime.minusSeconds(3600).toString())
                it.put("expires_at", now.plusSeconds(86400).toString())
                it.getJSONArray("segments").getJSONObject(0).getJSONObject("selector")
                    .put("months", JSONArray(listOf(date.substring(5, 7).toInt())))
            }
            val values = issuedValues().map { it.copy(runTime = validTime.minusSeconds(3600), validTime = validTime) }
            val result = blendModelForecast(base.toString(), models.toString(), PRAGUE,
                parseCalibrationArtifact(artifact.toString(), now.epochSecond), values, now)
            assertEquals(22.0, JSONObject(result.json).getJSONObject("hourly").getJSONArray("temperature_2m").getDouble(0), 0.0)
            // In spring, the next real 03:00 hour legitimately matches 01:00 UTC.
            assertEquals(if (date == "2026-03-29") "${date}T03:00" else null, result.calibrationAppliedAt)
        }
    }

    @Test
    fun usesResponseUtcOffsetInsteadOfPotentiallyOutdatedDeviceTimezoneRules() {
        val base = JSONObject(BASE).put("timezone", "UTC").put("utc_offset_seconds", 7200)
        val result = blendModelForecast(base.toString(), MODELS, PRAGUE,
            parseCalibrationArtifact(CALIBRATION, NOW.epochSecond), issuedValues(), NOW)
        assertEquals(21.2, JSONObject(result.json).getJSONObject("hourly").getJSONArray("temperature_2m").getDouble(0), 1e-9)
        assertEquals(1, result.calibratedValueCount)
    }

    @Test
    fun neverUsesAnIntervalTotalAsHourlyCalibratedRain() {
        val artifact = CALIBRATION.replace("\"variable\":\"temperature_2m\"", "\"variable\":\"precipitation\"")
        val result = issuedBlend(issuedValues().map { it.copy(variable = "precipitation", unit = "mm") }, artifact)
        assertEquals(0, result.calibratedValueCount)
        assertEquals(0.0, JSONObject(result.json).getJSONObject("hourly").getJSONArray("precipitation").getDouble(0), 0.0)
    }

    @Test
    fun doesNotRecalibrateHoursBeforeCurrentHour() {
        val base = JSONObject(BASE).also { it.getJSONObject("current").put("time", "2026-08-29T20:15") }
        val result = blendModelForecast(base.toString(), MODELS, PRAGUE,
            parseCalibrationArtifact(CALIBRATION, NOW.epochSecond), issuedValues(), NOW)
        assertEquals(0, result.calibratedValueCount)
        assertEquals(22.0, JSONObject(result.json).getJSONObject("hourly").getJSONArray("temperature_2m").getDouble(0), 0.0)
    }

    private fun issuedBlend(values: List<StaticModelValue>, artifact: String = CALIBRATION): ModelBlendResult =
        blendModelForecast(BASE, MODELS, PRAGUE, parseCalibrationArtifact(artifact, NOW.epochSecond), values, NOW)

    private fun issuedValues(): List<StaticModelValue> = listOf("a" to 20.0, "b" to 22.0).map { (model, value) ->
        StaticModelValue(model, model, Instant.parse("2026-08-29T12:00:00Z"), Instant.parse("2026-08-29T17:00:00Z"),
            PRAGUE.latitude, PRAGUE.longitude, 250.0, "temperature_2m", value, "°C")
    }

    @Test
    fun keepsBestMatchWhenFewerThanThreeModelsArePresent() {
        val result = blendModelForecast(BASE, ONE_MODEL)
        val root = JSONObject(result.json)

        assertEquals(99.0, root.getJSONObject("current").getDouble("temperature_2m"), 0.0)
        assertEquals(
            99.0,
            root.getJSONObject("hourly").getJSONArray("temperature_2m").getDouble(0),
            0.0,
        )
        assertEquals(ForecastCalculationMode.BEST_MATCH, result.mode)
        assertEquals(listOf("a"), result.contributorIds)
        assertEquals(ForecastFallbackReason.INSUFFICIENT_CONTRIBUTORS, result.fallbackReason)
    }

    @Test
    fun derivesClearSkyWhenModelCodesAreMissing() {
        val models = JSONObject(MODELS).also { root ->
            val hourly = root.getJSONObject("hourly")
            listOf("a", "b", "c").forEach { suffix -> hourly.remove("weather_code_$suffix") }
        }

        val result = JSONObject(blendModelForecast(BASE, models.toString()).json)

        assertEquals(0, result.getJSONObject("hourly").getJSONArray("weather_code").getInt(0))
        assertEquals(3, result.getJSONObject("current").getInt("weather_code"))
    }

    @Test
    fun rejectsNegativeProviderPrecipitationInsteadOfBlendingIt() {
        val models = JSONObject(MODELS).also { root ->
            root.getJSONObject("hourly").getJSONArray("precipitation_a").put(1, -0.2)
        }

        val result = JSONObject(blendModelForecast(BASE, models.toString()).json)

        assertEquals(0.0, result.getJSONObject("hourly").getJSONArray("precipitation").getDouble(1), 0.0)
    }

    companion object {
        private val PRAGUE = CzechLocation("Praha", REGION_PRAGUE, 50.0755, 14.4378, "CZ")
        private val NOW = Instant.parse("2026-08-29T17:15:00Z")

        private val CALIBRATION = """
            {
              "schema_version":2,
              "dataset_manifest_hash":"${"a".repeat(64)}",
              "model_contract_hash":"${"b".repeat(64)}",
              "generated_at":"2026-08-29T12:00:00Z",
              "expires_at":"2026-09-29T18:00:00Z",
              "models":[
                {"model_id":"a","maximum_run_age_hours":12,"resolution_km":10.0},
                {"model_id":"b","maximum_run_age_hours":12,"resolution_km":10.0}
              ],
              "segments":[{
                "selector":{
                  "region":"CZECHIA",
                  "variable":"temperature_2m",
                  "minimum_lead_hours":0,
                  "maximum_lead_hours":24,
                  "months":[8]
                },
                "truth_class":"station",
                "mode":"blend",
                "weights":{"a":0.4,"b":0.6},
                "minimum_source_count":2,
                "fallback_model":"a",
                "holdout":{"accepted":true,"sample_count":30}
              }]
            }
        """.trimIndent()

        private val BASE = """
            {
              "timezone":"Europe/Prague",
              "current":{"time":"2026-08-29T19:15","temperature_2m":99,"weather_code":3,"cloud_cover":100,"relative_humidity_2m":50,"precipitation":0,"pressure_msl":1010,"wind_speed_10m":5,"wind_direction_10m":180,"wind_gusts_10m":8,"apparent_temperature":99},
              "hourly":{"time":["2026-08-29T19:00","2026-08-29T20:00"],"temperature_2m":[99,99],"relative_humidity_2m":[50,50],"precipitation_probability":[0,0],"precipitation":[0,0],"weather_code":[3,3],"cloud_cover":[100,100],"pressure_msl":[1010,1010],"wind_speed_10m":[5,5],"wind_direction_10m":[180,180],"wind_gusts_10m":[8,8],"apparent_temperature":[99,99],"is_day":[1,1]},
              "daily":{"time":["2026-08-29"],"weather_code":[3],"temperature_2m_max":[99],"temperature_2m_min":[99],"precipitation_sum":[0],"precipitation_probability_max":[0],"wind_speed_10m_max":[5],"apparent_temperature_max":[99],"apparent_temperature_min":[99],"wind_gusts_10m_max":[8],"wind_direction_10m_dominant":[90]}
            }
        """.trimIndent()

        private val MODELS = """
            {
              "hourly":{
                "time":["2026-08-29T19:00","2026-08-29T20:00"],
                "temperature_2m_a":[20,18],"temperature_2m_b":[22,20],"temperature_2m_c":[40,22],"temperature_2m_d":[null,null],
                "relative_humidity_2m_a":[40,60],"relative_humidity_2m_b":[50,70],"relative_humidity_2m_c":[60,80],
                "precipitation_a":[0,0],"precipitation_b":[0,0.2],"precipitation_c":[0,0.4],
                "weather_code_a":[0,0],"weather_code_b":[0,61],"weather_code_c":[1,61],
                "cloud_cover_a":[0,100],"cloud_cover_b":[10,100],"cloud_cover_c":[20,100],
                "pressure_msl_a":[1012,1011],"pressure_msl_b":[1014,1013],"pressure_msl_c":[1016,1015],
                "wind_speed_10m_a":[10,12],"wind_speed_10m_b":[10,12],"wind_speed_10m_c":[10,12],
                "wind_direction_10m_a":[350,180],"wind_direction_10m_b":[0,180],"wind_direction_10m_c":[10,180],
                "wind_gusts_10m_a":[15,18],"wind_gusts_10m_b":[16,19],"wind_gusts_10m_c":[17,20],
                "apparent_temperature_a":[20,18],"apparent_temperature_b":[22,20],"apparent_temperature_c":[40,22]
              }
            }
        """.trimIndent()

        private val ONE_MODEL = """
            {"hourly":{"time":["2026-08-29T19:00","2026-08-29T20:00"],"temperature_2m_a":[20,18]}}
        """.trimIndent()
    }
}
