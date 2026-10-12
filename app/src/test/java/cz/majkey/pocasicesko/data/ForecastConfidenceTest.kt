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
