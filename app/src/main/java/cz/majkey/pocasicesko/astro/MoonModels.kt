package cz.majkey.pocasicesko.astro

import java.time.ZonedDateTime

enum class MoonPhaseKey {
    NEW_MOON,
    WAXING_CRESCENT,
    FIRST_QUARTER,
    WAXING_GIBBOUS,
    FULL_MOON,
    WANING_GIBBOUS,
    LAST_QUARTER,
    WANING_CRESCENT,
}

data class MoonDetails(
    val phase: MoonPhaseKey,
    val illuminatedFraction: Double,
    val waxing: Boolean,
    val rise: ZonedDateTime?,
    val set: ZonedDateTime?,
    val alwaysUp: Boolean,
    val alwaysDown: Boolean,
    val altitudeDegrees: Double,
    val azimuthDegrees: Double,
    val distanceKm: Double,
    val brightLimbAngleDegrees: Double,
    val nextNewMoon: ZonedDateTime,
    val nextFullMoon: ZonedDateTime,
)
