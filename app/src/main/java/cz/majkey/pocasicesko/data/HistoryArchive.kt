package cz.majkey.pocasicesko.data

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.max
import org.json.JSONException
import org.json.JSONObject

data class HistoricalDay(
    val date: LocalDate,
    val temperatureMeanC: Double,
    val temperatureMaximumC: Double,
    val temperatureMinimumC: Double,
    val precipitationMm: Double,
    val relativeHumidityPercent: Double?,
    val windSpeedMetersPerSecond: Double?,
    val solarEnergyMegajoulesPerSquareMeter: Double?,
)

data class HistoryArchive(
    val location: CzechLocation,
    val days: List<HistoricalDay>,
    val sourceVersion: String,
    val accessedAtEpochMillis: Long,
) {
    init {
        require(days.isNotEmpty()) { "History archive must contain at least one day." }
    }
}

data class HistorySummary(
    val dayCount: Int,
    val calendarDayCount: Long,
    val solarEnergyDayCount: Int,
    val humidityDayCount: Int,
    val windDayCount: Int,
    val totalPrecipitationMm: Double,
    val wetDayCount: Int,
    val averageTemperatureC: Double,
    val minimumTemperatureC: Double,
    val maximumTemperatureC: Double,
    val totalSolarEnergyMegajoulesPerSquareMeter: Double?,
    val averageRelativeHumidityPercent: Double?,
    val averageWindSpeedMetersPerSecond: Double?,
)

internal fun HistoryArchive.inDateRange(start: LocalDate, endInclusive: LocalDate): HistoryArchive? {
    require(start <= endInclusive) { "History range starts after its end." }
    val selected = days.filter { it.date in start..endInclusive }
    return selected.takeIf(List<HistoricalDay>::isNotEmpty)?.let { copy(days = it) }
}

fun HistoryArchive.summary(): HistorySummary {
    require(days.isNotEmpty()) { "History archive must contain at least one day." }
    return HistorySummary(
        dayCount = days.size,
        calendarDayCount = days.maxOf { it.date.toEpochDay() } - days.minOf { it.date.toEpochDay() } + 1,
        solarEnergyDayCount = days.count { it.solarEnergyMegajoulesPerSquareMeter != null },
        humidityDayCount = days.count { it.relativeHumidityPercent != null },
        windDayCount = days.count { it.windSpeedMetersPerSecond != null },
        totalPrecipitationMm = days.sumOf(HistoricalDay::precipitationMm),
        wetDayCount = days.count { it.precipitationMm >= WET_DAY_THRESHOLD_MM },
        averageTemperatureC = days.map(HistoricalDay::temperatureMeanC).average(),
        minimumTemperatureC = days.minOf(HistoricalDay::temperatureMinimumC),
        maximumTemperatureC = days.maxOf(HistoricalDay::temperatureMaximumC),
        totalSolarEnergyMegajoulesPerSquareMeter = days
            .mapNotNull(HistoricalDay::solarEnergyMegajoulesPerSquareMeter)
            .takeIf(List<Double>::isNotEmpty)
            ?.sum(),
        averageRelativeHumidityPercent = days
            .mapNotNull(HistoricalDay::relativeHumidityPercent)
            .takeIf(List<Double>::isNotEmpty)
            ?.average(),
        averageWindSpeedMetersPerSecond = days
            .mapNotNull(HistoricalDay::windSpeedMetersPerSecond)
            .takeIf(List<Double>::isNotEmpty)
            ?.average(),
    )
}

internal fun parsePowerHistory(
    json: String,
    location: CzechLocation,
    accessedAtEpochMillis: Long,
): HistoryArchive {
    val root = JSONObject(json)
    val parameters = root.getJSONObject("properties").getJSONObject("parameter")
    val temperatureMean = parameters.getJSONObject("T2M")
    val temperatureMaximum = parameters.getJSONObject("T2M_MAX")
    val temperatureMinimum = parameters.getJSONObject("T2M_MIN")
    val precipitation = parameters.getJSONObject("PRECTOTCORR")
    val humidity = parameters.optJSONObject("RH2M")
    val wind = parameters.optJSONObject("WS10M")
    val solarEnergy = parameters.optJSONObject("ALLSKY_SFC_SW_DWN")
    val header = root.optJSONObject("header")
    val fillValue = header?.optDouble("fill_value", DEFAULT_FILL_VALUE) ?: DEFAULT_FILL_VALUE
    val days = buildList {
        val dates = temperatureMean.keys()
        while (dates.hasNext()) {
            val key = dates.next()
            val date = runCatching { LocalDate.parse(key, DateTimeFormatter.BASIC_ISO_DATE) }.getOrNull()
                ?: continue
            val mean = temperatureMean.valueOrNull(key, fillValue) ?: continue
            val maximum = temperatureMaximum.valueOrNull(key, fillValue) ?: continue
            val minimum = temperatureMinimum.valueOrNull(key, fillValue) ?: continue
            val rain = precipitation.valueOrNull(key, fillValue) ?: continue
            add(
                HistoricalDay(
                    date = date,
                    temperatureMeanC = mean,
                    temperatureMaximumC = maximum,
                    temperatureMinimumC = minimum,
                    precipitationMm = max(0.0, rain),
                    relativeHumidityPercent = humidity?.valueOrNull(key, fillValue),
                    windSpeedMetersPerSecond = wind?.valueOrNull(key, fillValue),
                    solarEnergyMegajoulesPerSquareMeter = solarEnergy?.valueOrNull(key, fillValue),
                ),
            )
        }
    }.sortedBy(HistoricalDay::date)
    if (days.isEmpty()) throw JSONException("NASA POWER response contains no usable daily history.")
    return HistoryArchive(
        location = location,
        days = days,
        sourceVersion = header?.optJSONObject("api")?.optString("version")
            ?.takeIf(String::isNotBlank) ?: "unknown",
        accessedAtEpochMillis = accessedAtEpochMillis,
    )
}

private fun JSONObject.valueOrNull(key: String, fillValue: Double): Double? {
    if (!has(key)) return null
    val value = optDouble(key, Double.NaN)
    return value.takeIf { it.isFinite() && it != fillValue }
}

private const val DEFAULT_FILL_VALUE = -999.0
private const val WET_DAY_THRESHOLD_MM = 0.1
