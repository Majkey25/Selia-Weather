package cz.majkey.pocasicesko.astro

import java.time.ZonedDateTime
import org.shredzone.commons.suncalc.MoonIllumination
import org.shredzone.commons.suncalc.MoonPhase
import org.shredzone.commons.suncalc.MoonPosition
import org.shredzone.commons.suncalc.MoonTimes

object MoonCalculator {
    fun calculate(
        at: ZonedDateTime,
        latitude: Double,
        longitude: Double,
        elevationMeters: Double = 0.0,
    ): MoonDetails {
        require(latitude.isFinite() && latitude in -90.0..90.0) { "Invalid latitude" }
        require(longitude.isFinite() && longitude in -180.0..180.0) { "Invalid longitude" }
        require(elevationMeters.isFinite() && elevationMeters >= 0.0) { "Invalid elevation" }

        val illumination = MoonIllumination.compute()
            .on(at)
            .at(latitude, longitude)
            .elevation(elevationMeters)
            .execute()
        val position = MoonPosition.compute()
            .on(at)
            .at(latitude, longitude)
            .elevation(elevationMeters)
            .execute()
        val times = MoonTimes.compute()
            .on(at.toLocalDate())
            .timezone(at.zone)
            .at(latitude, longitude)
            .elevation(elevationMeters)
            .oneDay()
            .execute()
        val nextNewMoon = MoonPhase.compute()
            .on(at)
            .phase(MoonPhase.Phase.NEW_MOON)
            .execute()
            .time
            .withZoneSameInstant(at.zone)
        val nextFullMoon = MoonPhase.compute()
            .on(at)
            .phase(MoonPhase.Phase.FULL_MOON)
            .execute()
            .time
            .withZoneSameInstant(at.zone)

        return MoonDetails(
            phase = illumination.closestPhase.toKey(),
            illuminatedFraction = illumination.fraction,
            waxing = illumination.phase < 0.0,
            rise = times.rise,
            set = times.set,
            alwaysUp = times.isAlwaysUp,
            alwaysDown = times.isAlwaysDown,
            altitudeDegrees = position.altitude,
            azimuthDegrees = position.azimuth,
            distanceKm = position.distance,
            brightLimbAngleDegrees = normalizeAngle(
                illumination.angle - position.parallacticAngle,
            ),
            nextNewMoon = nextNewMoon,
            nextFullMoon = nextFullMoon,
        )
    }
}

private fun MoonPhase.Phase.toKey(): MoonPhaseKey = MoonPhaseKey.valueOf(name)

private fun normalizeAngle(value: Double): Double = ((value + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
