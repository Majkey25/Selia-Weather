package cz.majkey.pocasicesko.notification

import cz.majkey.pocasicesko.data.DailyWeather
import cz.majkey.pocasicesko.data.hasPrecipitationEvidence
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

internal enum class OutfitLevel {
    WINTER,
    WARM_COAT,
    JACKET,
    LIGHT_LAYERS,
    HOT,
}

internal data class DailyBriefingAdvice(
    val outfit: OutfitLevel,
    val umbrella: Boolean,
    val sunProtection: Boolean,
)

internal fun dailyBriefingAdvice(day: DailyWeather): DailyBriefingAdvice {
    val minimum = day.apparentTemperatureMin ?: day.temperatureMin
    val maximum = day.apparentTemperatureMax ?: day.temperatureMax
    val outfit = when {
        minimum <= 0 -> OutfitLevel.WINTER
        maximum < 12 -> OutfitLevel.WARM_COAT
        minimum < 15 -> OutfitLevel.JACKET
        maximum < 27 -> OutfitLevel.LIGHT_LAYERS
        else -> OutfitLevel.HOT
    }
    return DailyBriefingAdvice(
        outfit = outfit,
        umbrella = day.precipitationProbability?.let { it >= 40 } == true ||
            hasPrecipitationEvidence(day.weatherCode, day.precipitationSum, day.rainSum, day.snowfallSum),
        sunProtection = (day.uvIndexMax ?: 0.0) >= 6.0,
    )
}

internal fun nextDailyBriefingTime(now: ZonedDateTime, time: LocalTime = BRIEFING_TIME): Instant {
    val today = now.toLocalDate().atTime(time).atZone(now.zone)
    return (if (today.isAfter(now)) today else now.toLocalDate().plusDays(1).atTime(time).atZone(now.zone)).toInstant()
}

internal fun isBriefingDeliveryDue(scheduledAt: Long, now: Long, time: LocalTime, zone: ZoneId): Boolean {
    if (scheduledAt <= 0 || now - scheduledAt !in 0..BRIEFING_DELIVERY_WINDOW_MILLIS) return false
    val day = Instant.ofEpochMilli(scheduledAt).atZone(zone).toLocalDate()
    return day.atTime(time).atZone(zone).toInstant().toEpochMilli() == scheduledAt
}

internal const val BRIEFING_DELIVERY_WINDOW_MILLIS = 30 * 60 * 1_000L

internal const val DEFAULT_DAILY_BRIEFING_ENABLED = false
internal val BRIEFING_TIME: LocalTime = LocalTime.of(7, 0)
