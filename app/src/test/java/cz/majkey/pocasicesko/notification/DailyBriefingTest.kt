package cz.majkey.pocasicesko.notification

import cz.majkey.pocasicesko.data.DailyWeather
import java.time.ZoneId
import java.time.LocalDate
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DailyBriefingTest {
    @Test
    fun recommendsWarmRainProtectionFromApparentTemperature() {
        val advice = dailyBriefingAdvice(
            day(apparentMin = 3.0, apparentMax = 8.0, probability = 70, rain = 2.0),
        )

        assertEquals(OutfitLevel.WARM_COAT, advice.outfit)
        assertTrue(advice.umbrella)
        assertFalse(advice.sunProtection)
    }

    @Test
    fun recommendsLightClothingAndSunProtection() {
        val advice = dailyBriefingAdvice(
            day(apparentMin = 19.0, apparentMax = 30.0, probability = 10, uv = 7.0),
        )

        assertEquals(OutfitLevel.HOT, advice.outfit)
        assertFalse(advice.umbrella)
        assertTrue(advice.sunProtection)
    }

    @Test
    fun fallsBackToMeasuredTemperatureAndSchedulesNextMorning() {
        assertEquals(
            OutfitLevel.JACKET,
            dailyBriefingAdvice(day(min = 9.0, max = 16.0)).outfit,
        )
        val zone = ZoneId.of("Europe/Prague")
        assertEquals(
            ZonedDateTime.parse("2026-09-02T07:00:00+02:00[Europe/Prague]").toInstant(),
            nextDailyBriefingTime(ZonedDateTime.parse("2026-09-01T08:00:00+02:00[Europe/Prague]")),
        )
        assertEquals(
            ZonedDateTime.parse("2026-09-01T07:00:00+02:00[Europe/Prague]").toInstant(),
            nextDailyBriefingTime(ZonedDateTime.of(2026, 9, 1, 6, 0, 0, 0, zone)),
        )
        assertFalse(DEFAULT_DAILY_BRIEFING_ENABLED)
    }

    @Test
    fun retriesTodaysBriefingWithoutDeliveringYesterdaysAdvice() {
        val today = LocalDate.of(2026, 9, 5)
        assertTrue(isPendingBriefingForToday("2026-09-05", today))
        assertFalse(isPendingBriefingForToday("2026-09-04", today))
        assertFalse(isPendingBriefingForToday("2026-09-06", today))
        assertFalse(isPendingBriefingForToday(null, today))
        assertFalse(isPendingBriefingForToday("invalid", today))
    }

    private fun day(
        min: Double = 12.0,
        max: Double = 22.0,
        apparentMin: Double? = null,
        apparentMax: Double? = null,
        probability: Int = 0,
        rain: Double = 0.0,
        uv: Double? = null,
    ) = DailyWeather(
        date = "2026-09-01",
        weatherCode = 0,
        temperatureMax = max,
        temperatureMin = min,
        sunrise = "06:00",
        sunset = "20:00",
        precipitationSum = rain,
        precipitationProbability = probability,
        windSpeedMax = 10.0,
        apparentTemperatureMax = apparentMax,
        apparentTemperatureMin = apparentMin,
        uvIndexMax = uv,
    )
}
