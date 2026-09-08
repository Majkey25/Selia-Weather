package cz.majkey.pocasicesko.data

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import org.json.JSONException
import org.json.JSONObject

data class HistoricalDay(
    val date: LocalDate,
    val temperatureMeanC: Double?,
    val temperatureMaximumC: Double?,
    val temperatureMinimumC: Double?,
    val precipitationMm: Double?,
    val relativeHumidityPercent: Double?,
    val windSpeedMetersPerSecond: Double?,
    val solarEnergyMegajoulesPerSquareMeter: Double?,
    val dewPointC: Double? = null,
    val wetBulbTemperatureC: Double? = null,
    val surfacePressureHpa: Double? = null,
    val windSpeedMaximumMetersPerSecond: Double? = null,
    val windSpeedMinimumMetersPerSecond: Double? = null,
    val windDirectionDegrees: Double? = null,
    val clearSkySolarEnergyMegajoulesPerSquareMeter: Double? = null,
    val cloudCoverPercent: Double? = null,
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
    val precipitationDayCount: Int,
    val temperatureDayCount: Int,
    val temperatureMinimumDayCount: Int,
    val temperatureMaximumDayCount: Int,
    val totalPrecipitationMm: Double?,
    val wetDayCount: Int?,
    val averageTemperatureC: Double?,
    val minimumTemperatureC: Double?,
    val maximumTemperatureC: Double?,
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
    val precipitation = days.mapNotNull(HistoricalDay::precipitationMm)
    val temperatures = days.mapNotNull(HistoricalDay::temperatureMeanC)
    val minimumTemperatures = days.mapNotNull(HistoricalDay::temperatureMinimumC)
    val maximumTemperatures = days.mapNotNull(HistoricalDay::temperatureMaximumC)
    return HistorySummary(
        dayCount = days.size,
        calendarDayCount = days.maxOf { it.date.toEpochDay() } - days.minOf { it.date.toEpochDay() } + 1,
        solarEnergyDayCount = days.count { it.solarEnergyMegajoulesPerSquareMeter != null },
        humidityDayCount = days.count { it.relativeHumidityPercent != null },
        windDayCount = days.count { it.windSpeedMetersPerSecond != null },
        precipitationDayCount = precipitation.size,
        temperatureDayCount = temperatures.size,
        temperatureMinimumDayCount = minimumTemperatures.size,
        temperatureMaximumDayCount = maximumTemperatures.size,
        totalPrecipitationMm = precipitation.takeIf(List<Double>::isNotEmpty)?.sum(),
        wetDayCount = precipitation.takeIf(List<Double>::isNotEmpty)?.count { it >= WET_DAY_THRESHOLD_MM },
        averageTemperatureC = temperatures.takeIf(List<Double>::isNotEmpty)?.average(),
        minimumTemperatureC = minimumTemperatures.minOrNull(),
        maximumTemperatureC = maximumTemperatures.maxOrNull(),
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
    val metadata = root.optJSONObject("parameters")
    POWER_HISTORY_UNITS.forEach { (parameter, unit) ->
        val declared = metadata?.optJSONObject(parameter)?.optString("units")
        if (declared != null && declared != unit) throw JSONException("Unexpected NASA POWER unit for $parameter.")
    }
    val fields = POWER_HISTORY_UNITS.keys.associateWith(parameters::optJSONObject)
    val header = root.optJSONObject("header")
    val timeStandard = header?.optString("time_standard").orEmpty()
    if (timeStandard.isNotEmpty() && timeStandard != "UTC") throw JSONException("NASA POWER archive must use UTC.")
    val fillValue = header?.optDouble("fill_value", DEFAULT_FILL_VALUE)
        ?.takeIf(Double::isFinite) ?: DEFAULT_FILL_VALUE
    val days = buildList {
        val dates = fields.values.filterNotNull().flatMap { it.keys().asSequence().toList() }.toSortedSet()
        for (key in dates) {
            val date = runCatching { LocalDate.parse(key, DateTimeFormatter.BASIC_ISO_DATE) }.getOrNull()
                ?: continue
            val values = fields.mapValues { (parameter, data) ->
                data?.valueOrNull(key, fillValue)?.takeIf { validPowerValue(parameter, it) }
            }
            if (values.values.all { it == null }) continue
            add(
                HistoricalDay(
                    date = date,
                    temperatureMeanC = values["T2M"],
                    temperatureMaximumC = values["T2M_MAX"],
                    temperatureMinimumC = values["T2M_MIN"],
                    precipitationMm = values["PRECTOTCORR"],
                    relativeHumidityPercent = values["RH2M"],
                    windSpeedMetersPerSecond = values["WS10M"],
                    solarEnergyMegajoulesPerSquareMeter = values["ALLSKY_SFC_SW_DWN"],
                    dewPointC = values["T2MDEW"],
                    wetBulbTemperatureC = values["T2MWET"],
                    surfacePressureHpa = values["PS"]?.times(10.0),
                    windSpeedMaximumMetersPerSecond = values["WS10M_MAX"],
                    windSpeedMinimumMetersPerSecond = values["WS10M_MIN"],
                    windDirectionDegrees = values["WD10M"],
                    clearSkySolarEnergyMegajoulesPerSquareMeter = values["CLRSKY_SFC_SW_DWN"],
                    cloudCoverPercent = values["CLOUD_AMT"],
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

private fun validPowerValue(parameter: String, value: Double): Boolean = when (parameter) {
    "RH2M", "CLOUD_AMT" -> value in 0.0..100.0
    "WD10M" -> value in 0.0..360.0
    "PS" -> value > 0 && value <= Double.MAX_VALUE / 10.0
    "PRECTOTCORR", "WS10M", "WS10M_MAX", "WS10M_MIN", "ALLSKY_SFC_SW_DWN", "CLRSKY_SFC_SW_DWN" -> value >= 0
    else -> true
}

internal val POWER_HISTORY_UNITS = mapOf(
    "T2M" to "C", "T2M_MAX" to "C", "T2M_MIN" to "C", "PRECTOTCORR" to "mm/day",
    "RH2M" to "%", "WS10M" to "m/s", "ALLSKY_SFC_SW_DWN" to "MJ/m^2/day",
    "T2MDEW" to "C", "T2MWET" to "C", "PS" to "kPa", "WS10M_MAX" to "m/s",
    "WS10M_MIN" to "m/s", "WD10M" to "Degrees", "CLRSKY_SFC_SW_DWN" to "MJ/m^2/day",
    "CLOUD_AMT" to "%",
)

private const val DEFAULT_FILL_VALUE = -999.0
private const val WET_DAY_THRESHOLD_MM = 0.1
