package cz.majkey.pocasicesko.data

import java.time.Instant
import java.util.Locale

internal fun historyCsv(archive: HistoryArchive): String = buildList {
    add(
        "location_name,latitude,longitude,source,source_version,accessed_at_utc,date," +
            "temperature_mean_c,temperature_max_c,temperature_min_c,precipitation_mm," +
            "relative_humidity_percent,wind_speed_m_s,solar_energy_mj_m2",
    )
    archive.days.forEach { day ->
        add(
            listOf(
                archive.location.name.csvField(),
                archive.location.latitude.fixed(6),
                archive.location.longitude.fixed(6),
                "NASA POWER",
                archive.sourceVersion.csvField(),
                Instant.ofEpochMilli(archive.accessedAtEpochMillis).toString(),
                day.date.toString(),
                day.temperatureMeanC.fixed(2),
                day.temperatureMaximumC.fixed(2),
                day.temperatureMinimumC.fixed(2),
                day.precipitationMm.fixed(2),
                day.relativeHumidityPercent?.fixed(2).orEmpty(),
                day.windSpeedMetersPerSecond?.fixed(2).orEmpty(),
                day.solarEnergyMegajoulesPerSquareMeter?.fixed(2).orEmpty(),
            ).joinToString(","),
        )
    }
}.joinToString("\n")

internal fun historyChatPrompt(archive: HistoryArchive): String =
    "Analyze the attached daily weather archive for ${archive.location.name} " +
        "(${archive.location.latitude}, ${archive.location.longitude}) from " +
        "${archive.days.first().date} to ${archive.days.last().date}. " +
        "Calculate answers from the rows and state the covered dates. " +
        "Be ready to calculate precipitation for any requested date range. " +
        "NASA POWER values are model and satellite grid estimates, not local station observations. " +
        "Solar energy is not sunshine duration."

private fun Double.fixed(decimals: Int): String = String.format(Locale.US, "%.${decimals}f", this)

private fun String.csvField(): String = if (any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
    "\"${replace("\"", "\"\"")}\""
} else {
    this
}
