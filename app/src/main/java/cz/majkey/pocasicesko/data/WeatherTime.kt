package cz.majkey.pocasicesko.data

import java.time.Instant
import java.time.OffsetDateTime

// Android 10's Instant parser rejects explicit ISO offsets that the desktop JDK accepts.
internal fun parseWeatherInstant(value: String): Instant = OffsetDateTime.parse(value).toInstant()
