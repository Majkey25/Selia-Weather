package cz.majkey.pocasicesko.data

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

internal const val SKY_OBSERVATION_TIME_KEY = "_selia_sky_observation_time"

// Android 10's Instant parser rejects explicit ISO offsets that the desktop JDK accepts.
internal fun parseWeatherInstant(value: String): Instant = OffsetDateTime.parse(value).toInstant()

internal fun CurrentWeather.skyConditionTime(utcOffsetSeconds: Int?): String? {
    val observedAt = skyObservationTime ?: return time
    val offset = utcOffsetSeconds?.takeIf { it in -64_800..64_800 } ?: return null
    return observedAt.atOffset(ZoneOffset.ofTotalSeconds(offset)).toLocalDateTime().toString()
}
