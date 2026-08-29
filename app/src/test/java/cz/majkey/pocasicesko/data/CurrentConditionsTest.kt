package cz.majkey.pocasicesko.data

import java.time.Instant
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class CurrentConditionsTest {
    @Test
    fun freshFullSunOverridesFalseCloudAndRain() {
        val now = Instant.parse("2026-08-29T09:10:00Z")
        val fused = fuseCurrentConditions(
            model = current(weatherCode = 61, precipitation = 0.4, cloudCover = 100),
            location = CzechLocation("Březnice", REGION_ZLIN, 49.1877, 17.6735),
            observations = listOf(
                observation(49.20, 17.70, now.minusSeconds(600), sunshineSeconds = 600.0),
                observation(49.30, 17.60, now.minusSeconds(900), sunshineSeconds = 540.0),
            ),
            now = now,
        )

        assertEquals(0, fused.weatherCode)
        assertEquals(5, fused.cloudCover)
        assertEquals(5, fused.cloudCoverLow)
        assertEquals(5, fused.cloudCoverMid)
        assertEquals(5, fused.cloudCoverHigh)
        assertEquals(0.0, fused.precipitation, 0.0)
        assertEquals(20.0, fused.temperature, 0.0)
    }

    @Test
    fun freshMeasuredRainOverridesFalseClearSky() {
        val now = Instant.parse("2026-08-29T09:10:00Z")
        val fused = fuseCurrentConditions(
            model = current(weatherCode = 0, precipitation = 0.0, cloudCover = 0),
            location = CzechLocation("Praha", REGION_PRAGUE, 50.0755, 14.4378),
            observations = listOf(
                observation(50.08, 14.44, now.minusSeconds(300), precipitation = 0.3),
                observation(50.10, 14.40, now.minusSeconds(600), precipitation = 0.2),
            ),
            now = now,
        )

        assertEquals(61, fused.weatherCode)
        assertEquals(100, fused.cloudCover)
        assertEquals(0.3, fused.precipitation, 0.1)
    }

    @Test
    fun staleOrDistantObservationsDoNotChangeModel() {
        val now = Instant.parse("2026-08-29T09:10:00Z")
        val model = current(weatherCode = 2, precipitation = 0.0, cloudCover = 50)

        val fused = fuseCurrentConditions(
            model = model,
            location = CzechLocation("Praha", REGION_PRAGUE, 50.0755, 14.4378),
            observations = listOf(
                observation(50.08, 14.44, now.minusSeconds(7_200), sunshineSeconds = 600.0),
                observation(48.97, 17.67, now.minusSeconds(300), sunshineSeconds = 600.0),
            ),
            now = now,
        )

        assertEquals(model, fused)
    }

    @Test
    fun delayedPublishedObservationWithinNinetyMinutesStillApplies() {
        val now = Instant.parse("2026-08-29T10:00:00Z")
        val fused = fuseCurrentConditions(
            model = current(weatherCode = 3, precipitation = 0.0, cloudCover = 100),
            location = CzechLocation("Březnice", REGION_ZLIN, 49.1877, 17.6735),
            observations = listOf(
                observation(49.20, 17.70, now.minusSeconds(3_600), sunshineSeconds = 600.0),
            ),
            now = now,
        )

        assertEquals(0, fused.weatherCode)
    }

    @Test
    fun correctedJsonUpdatesCurrentAndMatchingHourlyValue() {
        val corrected = JSONObject(
            applyCurrentConditionsToForecastJson(
                """{"current":{"time":"2026-08-29T11:15"},"hourly":{"time":["2026-08-29T10:00","2026-08-29T11:00"],"temperature_2m":[18,19],"relative_humidity_2m":[70,65],"precipitation":[0.2,0.3],"rain":[0.2,0.3],"weather_code":[61,61],"wind_speed_10m":[5,6],"wind_direction_10m":[180,190]}}""",
                current(weatherCode = 0, precipitation = 0.0, cloudCover = 5),
            ),
        )

        assertEquals(0, corrected.getJSONObject("current").getInt("weather_code"))
        val hourly = corrected.getJSONObject("hourly")
        assertEquals(61, hourly.getJSONArray("weather_code").getInt(0))
        assertEquals(0, hourly.getJSONArray("weather_code").getInt(1))
        assertEquals(22.0, hourly.getJSONArray("temperature_2m").getDouble(1), 0.0)
        assertEquals(0.0, hourly.getJSONArray("precipitation").getDouble(1), 0.0)
    }

    private fun current(
        weatherCode: Int,
        precipitation: Double,
        cloudCover: Int,
    ) = CurrentWeather(
        time = "2026-08-29T11:10",
        temperature = 22.0,
        feelsLike = 22.0,
        humidity = 50,
        precipitation = precipitation,
        weatherCode = weatherCode,
        cloudCover = cloudCover,
        pressure = 1_015.0,
        windSpeed = 5.0,
        windDirection = 270,
        windGusts = 8.0,
        isDay = true,
    )

    private fun observation(
        latitude: Double,
        longitude: Double,
        time: Instant,
        precipitation: Double = 0.0,
        sunshineSeconds: Double? = null,
    ) = CurrentStationObservation(
        stationId = "$latitude,$longitude",
        latitude = latitude,
        longitude = longitude,
        time = time,
        temperature = 20.0,
        humidity = 60,
        precipitation = precipitation,
        windSpeed = null,
        windDirection = null,
        sunshineSeconds = sunshineSeconds,
    )
}
