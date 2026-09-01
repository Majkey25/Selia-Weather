package cz.majkey.pocasicesko.notification

import cz.majkey.pocasicesko.data.DailyWeather
import java.time.Instant
import java.time.LocalTime
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
        umbrella = day.precipitationProbability >= 40 || day.precipitationSum >= 0.5,
        sunProtection = (day.uvIndexMax ?: 0.0) >= 6.0,
    )
}

internal fun nextDailyBriefingTime(now: ZonedDateTime): Instant {
    val today = now.toLocalDate().atTime(BRIEFING_TIME).atZone(now.zone)
    return (if (today.isAfter(now)) today else today.plusDays(1)).toInstant()
}

internal const val DEFAULT_DAILY_BRIEFING_ENABLED = false
internal val BRIEFING_TIME: LocalTime = LocalTime.of(7, 0)
