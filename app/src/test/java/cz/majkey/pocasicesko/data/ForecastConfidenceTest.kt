package cz.majkey.pocasicesko.data

import java.time.LocalDateTime
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ForecastConfidenceTest {
    private val now = LocalDateTime.parse("2026-10-07T12:00")
    private val evidence = HourlyModelAgreement(5, 1.0, 2.0, 10.0, 5, 0, 0.0, 0.0, WeatherKind.CLEAR)
    private fun hour(time: String = "2026-10-07T13:00") = HourlyWeather(time, 20.0, 50, 0, 0.0, 0, 1013.0, 10.0, 180, true,
        modelAgreement = evidence)

    @Test
    fun unobservableSkyIsNotAConditionConflictOrHighConfidence() {
        for (code in listOf(-1, SKY_OBSCURED_WEATHER_CODE)) {
            for (shownTime in listOf(hour().time, "2026-10-07T13:05", null)) {
                val result = hourlyForecastConfidence(hour(), hour(), now, 0,
                    conditionFor(code), shownConditionTime = shownTime)
                assertEquals(ConfidenceLevel.LIMITED, result.level)
                assertEquals(ConfidenceReason.UNKNOWN_CONDITION, result.reason)
                assertEquals(emptyList<ForecastDisagreement>(), result.disagreements)
                assertEquals(5, result.modelCount)
                assertEquals(evidence, result.evidence)
                val conflict = hourlyForecastConfidence(hour().copy(modelAgreement =
                    evidence.copy(cloudRangePercent = 90.0)), hour(), now, 0,
                    conditionFor(code), shownConditionTime = shownTime)
                assertEquals(ConfidenceLevel.LOW, conflict.level)
                assertEquals(listOf(ForecastDisagreement.CLOUDS), conflict.disagreements)
            }
        }
        val hours = (12..23).map { hour("2026-10-07T${it}:00") }
        val current = CurrentWeather("2026-10-07T12:00", 20.0, 20.0, 50, 0.0,
            SKY_OBSCURED_WEATHER_CODE, 0, 1013.0, 10.0, 180, 10.0, true)
        val day = dailyForecastConfidence("2026-10-07", hours,
            hours.associate { it.time to it }, now, 0, current)
        assertEquals(ConfidenceLevel.MODERATE, day.level)
        assertEquals(ConfidenceReason.PARTIAL_DAY, day.reason)
        assertEquals(11, day.coveredHours)
    }

    @Test
    fun historicalHoursKeepAvailableModelsWithoutClaimingVerifiedAccuracy() {
        val inputs = List(5) { index -> HourlyModelInput("model_$index", 20.0, 10.0, 180.0, 0.0, 0, 0.0) }
        val agreement = evidence.copy(modelInputs = inputs)
        val past = hour("2026-10-06T13:00").copy(modelAgreement = agreement, skySourceModelId = "chmi_aladin_seamless")
        val rain = past.copy(time = "2026-10-06T14:00")
        val result = hourlyForecastConfidence(past, rain, now, 0)
        assertEquals(ConfidenceLevel.LIMITED, result.level)
        assertEquals(ConfidenceReason.HISTORICAL, result.reason)
        assertEquals(agreement, result.evidence)
        assertEquals(agreement, result.precipitationEvidence)
        assertEquals(5, result.modelCount)
        assertEquals(past.time, result.validTime)
        assertEquals(past.skySourceModelId, result.skySourceModelId)
        val later = past.copy(time = "2026-10-06T15:00", modelAgreement = evidence.copy(cloudRangePercent = 90.0))
        val unavailableHour = past.copy(time = "2026-10-06T00:00", modelAgreement = null)
        val day = dailyForecastConfidence("2026-10-06", listOf(later, unavailableHour, past), mapOf(past.time to rain), now, 0)
        assertEquals(ConfidenceLevel.LIMITED, day.level)
        assertEquals(ConfidenceReason.HISTORICAL, day.reason)
        assertEquals(agreement, day.evidence)
        assertEquals(past.time, day.validTime)
        val unavailable = hourlyForecastConfidence(past.copy(modelAgreement = null), null, now, 0)
        assertNull(unavailable.evidence)
        assertEquals(0, unavailable.modelCount)
    }

    @Test
    fun confidenceExplainsCloudConflictAndChecksTheActuallyDisplayedCondition() {
        val cloudy = hour().copy(modelAgreement = evidence.copy(cloudRangePercent = 100.0))
        assertEquals(listOf(ForecastDisagreement.CLOUDS), hourlyForecastConfidence(cloudy, hour(), now, 0).disagreements)
        val displayed = hourlyForecastConfidence(hour(), hour(), now, 0, conditionFor(3))
        assertEquals(ConfidenceLevel.LOW, displayed.level)
        assertEquals(listOf(ForecastDisagreement.DISPLAYED_CONDITION), displayed.disagreements)
        val missing = hourlyForecastConfidence(hour().copy(modelAgreement = null, skySourceModelId = "chmi_aladin_seamless"), hour(), now, 0)
        assertEquals("chmi_aladin_seamless", missing.skySourceModelId)
        assertNull(hourlyForecastConfidence(hour().copy(skySourceModelId = "chmi_aladin_seamless"), hour(), now, 0,
            conditionFor(3), skySourceModelId = null).skySourceModelId)
    }

    @Test
    fun dayConfidenceIncludesTheCurrentConditionActuallyShown() {
        val hours = (12..23).map { hour("2026-10-07T${it}:00") }
        val rain = hours.associate { it.time to hour() }
        val observed = CurrentWeather("2026-10-07T12:00", 20.0, 20.0, 50, 0.0, 3, 100,
            1013.0, 10.0, 180, 10.0, true)
        assertEquals(ConfidenceLevel.HIGH, dailyForecastConfidence("2026-10-07", hours, rain, now, 0).level)
        val displayed = dailyForecastConfidence("2026-10-07", hours, rain, now, 0, observed)
        assertEquals(ConfidenceLevel.LOW, displayed.level)
        assertEquals(listOf(ForecastDisagreement.DISPLAYED_CONDITION), displayed.disagreements)
        assertEquals("chmi_aladin_seamless",
            dailyForecastConfidence("2026-10-07", hours, rain, now, 0, observed, "chmi_aladin_seamless").skySourceModelId)
    }

    @Test
    fun laterCurrentSkyDoesNotCreateAnHourlyConditionConflict() {
        val hours = (12..23).map { hour("2026-10-07T${it}:00") }
        val rain = hours.associate { it.time to hour() }
        val observed = CurrentWeather("2026-10-07T12:45", 20.0, 20.0, 50, 0.0, 3, 100,
            1013.0, 10.0, 180, 10.0, true)
        val result = dailyForecastConfidence("2026-10-07", hours, rain, now.plusMinutes(45), 0, observed)
        assertEquals(ConfidenceLevel.HIGH, result.level)
        assertEquals(emptyList<ForecastDisagreement>(), result.disagreements)
        assertEquals("2026-10-07T12:00", result.validTime)
        assertEquals(observed.time, result.shownConditionTime)
        val aligned = hourlyForecastConfidence(hours.first(), hour(), now.plusMinutes(45), 0,
            conditionFor(3), shownConditionTime = "2026-10-07T12:00:00")
        assertEquals(listOf(ForecastDisagreement.DISPLAYED_CONDITION), aligned.disagreements)
        assertEquals(ConfidenceLevel.LIMITED, hourlyForecastConfidence(hours.first(), hour(), now, 0,
            conditionFor(3), shownConditionTime = "invalid").level)
    }

    @Test
    fun laterCurrentSkyDoesNotHideRealModelConflicts() {
        val hours = (12..23).map { hour("2026-10-07T${it}:00") }
        val observed = CurrentWeather("2026-10-07T12:45", 20.0, 20.0, 50, 0.0, 3, 100,
            1013.0, 10.0, 180, 10.0, true)
        val cases = listOf(
            evidence.copy(cloudRangePercent = 76.0) to ForecastDisagreement.CLOUDS,
            evidence.copy(windRangeKmh = 26.0) to ForecastDisagreement.WIND,
            evidence.copy(temperatureRangeCelsius = 6.0) to ForecastDisagreement.TEMPERATURE,
            evidence.copy(wetModelCount = 2, maximumPrecipitationMm = 1.0) to ForecastDisagreement.RAIN_OCCURRENCE,
        )
        for ((conflict, reason) in cases) {
            val conflictingHours = hours.map { it.copy(modelAgreement = conflict) }
            val rain = conflictingHours.associate { it.time to it }
            val result = dailyForecastConfidence("2026-10-07", conflictingHours, rain, now.plusMinutes(45), 0, observed)
            assertEquals(ConfidenceLevel.LOW, result.level)
            assertEquals(listOf(reason), result.disagreements)
        }
    }

    @Test
    fun legacyMetadataLoadsButInvalidOrDuplicateModelRowsDoNot() {
        val legacy = evidence.toJson().put("schema_version", 1).also { it.remove("model_inputs") }
        assertEquals(evidence, legacy.modelAgreementOrNull())
        val rows = evidence.copy(modelInputs = List(5) { index ->
            HourlyModelInput("model_$index", 20.0, 10.0, 180.0, 0.0, 0, 0.0)
        }).toJson()
        assertEquals(5, requireNotNull(rows.modelAgreementOrNull()).modelInputs.size)
        val duplicate = JSONObject(rows.toString())
        duplicate.getJSONArray("model_inputs").getJSONObject(1).put("model_id", "model_0")
        assertNull(duplicate.modelAgreementOrNull())
        val invalid = JSONObject(rows.toString())
        invalid.getJSONArray("model_inputs").getJSONObject(0).put("cloud_cover_percent", "0")
        assertNull(invalid.modelAgreementOrNull())
        assertNull(rows.put("model_count", 4).modelAgreementOrNull())
    }

    @Test
    fun agreementIsCappedForDistanceAndOlderDownloads() {
        assertEquals(ConfidenceLevel.HIGH, hourlyForecastConfidence(hour(), hour(), now, 0).level)
        assertEquals(ConfidenceLevel.MODERATE, hourlyForecastConfidence(hour("2026-10-10T13:00"), hour(), now, 0).level)
        assertEquals(ConfidenceLevel.LOW, hourlyForecastConfidence(hour("2026-10-16T13:00"), hour(), now, 0).level)
        assertEquals(ConfidenceLevel.MODERATE, hourlyForecastConfidence(hour(), hour(), now, 4 * 60 * 60_000L).level)
        assertEquals(ConfidenceLevel.LOW, hourlyForecastConfidence(hour(), hour(), now, 13 * 60 * 60_000L).level)
    }

    @Test
    fun disagreementOrMissingRainInputsCannotBecomeHigh() {
        assertEquals(ConfidenceLevel.LOW, hourlyForecastConfidence(hour().copy(modelAgreement = evidence.copy(temperatureRangeCelsius = 8.0)), hour(), now, 0).level)
        val mixed = evidence.copy(wetModelCount = 2, maximumPrecipitationMm = 1.0)
        assertEquals(ConfidenceLevel.LOW, hourlyForecastConfidence(hour(), hour().copy(modelAgreement = mixed), now, 0).level)
        assertEquals(ConfidenceLevel.LIMITED, hourlyForecastConfidence(hour(), null, now, 0).level)
        assertEquals(ConfidenceLevel.LIMITED, hourlyForecastConfidence(hour().copy(modelAgreement = null), hour(), now, 0).level)
        assertEquals(ConfidenceLevel.LIMITED, hourlyForecastConfidence(hour(), hour(), now, -1).level)
    }

    @Test
    fun verifiedSkyAgreementKeepsAllOtherDisagreementLimits() {
        val sky = requireNotNull(evidence.copy(modelCount = 11, agreeingConditionCount = 6, cloudRangePercent = 2.0,
            dominantCondition = WeatherKind.MAINLY_CLEAR).toJson().put("all_sky_conditions", true).modelAgreementOrNull())
        val forecast = hour().copy(weatherCode = 1, modelAgreement = sky)
        assertEquals(ConfidenceLevel.HIGH, hourlyForecastConfidence(forecast, hour(), now, 0).level)
        listOf(sky.copy(temperatureRangeCelsius = 8.0), sky.copy(windRangeKmh = 26.0),
            sky.copy(cloudRangePercent = 75.0)).forEach { conflicting ->
            assertEquals(ConfidenceLevel.LOW, hourlyForecastConfidence(forecast.copy(modelAgreement = conflicting), hour(), now, 0).level)
        }
        assertEquals(ConfidenceLevel.LOW, hourlyForecastConfidence(forecast.copy(weatherCode = 3), hour(), now, 0).level)
        assertEquals(ConfidenceLevel.LOW, hourlyForecastConfidence(forecast.copy(weatherCode = 45), hour(), now, 0).level)
        assertEquals(ConfidenceLevel.LOW, hourlyForecastConfidence(forecast.copy(weatherCode = 61), hour(), now, 0).level)
        assertEquals(ConfidenceLevel.LOW, hourlyForecastConfidence(forecast.copy(weatherCode = 95), hour(), now, 0).level)
        val mixedRain = evidence.copy(wetModelCount = 2, maximumPrecipitationMm = 1.0)
        assertEquals(ConfidenceLevel.LOW, hourlyForecastConfidence(forecast, hour().copy(modelAgreement = mixedRain), now, 0).level)
        assertEquals(ConfidenceLevel.LOW, hourlyForecastConfidence(forecast, hour().copy(precipitation = 0.3), now, 0).level)
        val varyingRain = evidence.copy(wetModelCount = 5, minimumPrecipitationMm = 0.01, maximumPrecipitationMm = 50.0,
            dominantCondition = WeatherKind.RAIN)
        assertEquals(ConfidenceLevel.LOW, hourlyForecastConfidence(forecast,
            hour().copy(precipitation = 1.0, modelAgreement = varyingRain), now, 0).level)
        assertEquals(ConfidenceLevel.MODERATE, hourlyForecastConfidence(forecast, hour(), now, 4 * 60 * 60_000L).level)
        assertEquals(ConfidenceLevel.LOW, hourlyForecastConfidence(forecast, hour(), now, 13 * 60 * 60_000L).level)
        assertEquals(ConfidenceLevel.MODERATE, hourlyForecastConfidence(forecast.copy(time = "2026-10-10T13:00"), hour(), now, 0).level)
        assertEquals(ConfidenceLevel.LOW, hourlyForecastConfidence(forecast.copy(time = "2026-10-16T13:00"), hour(), now, 0).level)
    }

    @Test
    fun unknownOrMixedSkyEvidenceKeepsTheCategoricalDisagreement() {
        val partialAgreement = evidence.copy(modelCount = 11, agreeingConditionCount = 6, cloudRangePercent = 2.0,
            dominantCondition = WeatherKind.MAINLY_CLEAR)
        val forecast = hour().copy(weatherCode = 1)
        val legacy = requireNotNull(partialAgreement.toJson().modelAgreementOrNull())
        assertNull(legacy.allSkyConditions)
        assertEquals(ConfidenceLevel.LOW, hourlyForecastConfidence(forecast.copy(modelAgreement = legacy), hour(), now, 0).level)
        val mixed = requireNotNull(partialAgreement.toJson().put("all_sky_conditions", false).modelAgreementOrNull())
        assertEquals(ConfidenceLevel.LOW, hourlyForecastConfidence(forecast.copy(modelAgreement = mixed), hour(), now, 0).level)
    }

    @Test
    fun preservedWetForecastDoesNotInheritUnanimousDryConfidence() {
        val rating = hourlyForecastConfidence(hour().copy(weatherCode = 51), hour().copy(precipitation = 0.3), now, 0)
        assertEquals(ConfidenceLevel.LOW, rating.level)
        assertEquals(ConfidenceReason.DISAGREEMENT, rating.reason)
    }

    @Test
    fun unanimousRainOccurrenceDoesNotHideDifferentAmounts() {
        val rain = evidence.copy(wetModelCount = 5, minimumPrecipitationMm = 0.01, maximumPrecipitationMm = 50.0,
            dominantCondition = WeatherKind.RAIN)
        val wet = hour().copy(weatherCode = 61, precipitation = 1.0, modelAgreement = rain)
        assertEquals(ConfidenceLevel.LOW, hourlyForecastConfidence(wet, wet, now, 0).level)
        val outside = hour().copy(precipitation = 0.03)
        assertEquals(ConfidenceLevel.LOW, hourlyForecastConfidence(hour(), outside, now, 0).level)
    }

    @Test
    fun ordinaryAndFreezingRainAreNotUnanimousConditionAgreement() {
        val inputs = listOf(61, 61, 67, 67, 67).mapIndexed { index, code ->
            HourlyModelInput("model_$index", 0.0, 10.0, 180.0, 100.0, code, 0.3)
        }
        val rain = evidence.copy(dominantCondition = WeatherKind.RAIN, wetModelCount = 5,
            minimumPrecipitationMm = 0.3, maximumPrecipitationMm = 0.3, modelInputs = inputs)
        val wet = hour().copy(weatherCode = 67, precipitation = 0.3, modelAgreement = rain)
        assertEquals(ConfidenceLevel.MODERATE, hourlyForecastConfidence(wet, wet, now, 0).level)
        val unanimous = wet.copy(modelAgreement = rain.copy(modelInputs = inputs.map { it.copy(weatherCode = 67) }))
        assertEquals(ConfidenceLevel.HIGH, hourlyForecastConfidence(unanimous, unanimous, now, 0).level)
    }

    @Test
    fun observedFreezingPrecipitationDoesNotInheritOrdinaryRainConfidence() {
        val location = CzechLocation("Test", "", 49.23, 17.63, "CZ")
        for ((forecastCode, observedCode) in listOf(61 to 66, 66 to 61, 51 to 56, 56 to 51, 71 to 68, 68 to 71)) {
            val inputs = List(5) { index ->
                HourlyModelInput("model_$index", 1.0, 10.0, 180.0, 100.0, forecastCode, 0.3)
            }
            val rain = evidence.copy(temperatureRangeCelsius = 0.0, windRangeKmh = 0.0, cloudRangePercent = 0.0,
                dominantCondition = conditionFor(forecastCode).kind, wetModelCount = 5, minimumPrecipitationMm = 0.3,
                maximumPrecipitationMm = 0.3, modelInputs = inputs)
            val hours = (12..23).map { hour("2026-10-07T${it}:00").copy(
                temperature = 1.0, weatherCode = forecastCode, precipitation = 0.3, modelAgreement = rain) }
            val precipitation = hours.associate { it.time to it }
            val instant = java.time.Instant.parse("2026-10-07T12:00:00Z")
            val model = CurrentWeather("2026-10-07T12:00", 1.0, 0.0, 90, 0.3, forecastCode, 100,
                1013.0, 10.0, 180, 10.0, true)
            val observation = CurrentStationObservation("TEST", location.latitude, location.longitude, instant,
                null, null, null, null, null, null, weatherCode = observedCode)
            val observed = fuseCurrentConditions(model, location, listOf(observation), instant)
            assertEquals(observedCode, observed.weatherCode)
            assertEquals(ConfidenceLevel.HIGH, dailyForecastConfidence("2026-10-07", hours, precipitation, now, 0, model).level)
            val result = dailyForecastConfidence("2026-10-07", hours, precipitation, now, 0, observed, utcOffsetSeconds = 0)
            assertEquals(ConfidenceLevel.LOW, result.level)
            assertEquals(listOf(ForecastDisagreement.DISPLAYED_CONDITION), result.disagreements)
        }
    }

    @Test
    fun sparseDayCannotInheritAHighRatingFromOneHour() {
        val hours = listOf(hour())
        val result = dailyForecastConfidence("2026-10-07", hours, mapOf(hours.single().time to hour()), now, 0)
        assertEquals(ConfidenceLevel.LIMITED, result.level)
        assertEquals(1, result.coveredHours)
        assertEquals(12, result.expectedHours)
        assertEquals(ConfidenceReason.HISTORICAL,
            dailyForecastConfidence("2026-10-06", hours, emptyMap(), now, 0).reason)
    }

    @Test
    fun invalidMetadataIsIgnoredRatherThanReinterpreted() {
        assertNull(evidence.toJson().put("model_count", 3.5).modelAgreementOrNull())
        assertNull(evidence.toJson().put("schema_version", 1.5).modelAgreementOrNull())
        assertNull(evidence.toJson().put("model_count", 99).modelAgreementOrNull())
        assertNull(evidence.toJson().put("dominant_condition", "unknown").modelAgreementOrNull())
        listOf("true", 1, JSONObject.NULL).forEach { invalid ->
            assertNull(evidence.toJson().put("all_sky_conditions", invalid).modelAgreementOrNull())
        }
        assertNull(evidence.toJson().put("dominant_condition", "RAIN").put("all_sky_conditions", true).modelAgreementOrNull())
    }
}
