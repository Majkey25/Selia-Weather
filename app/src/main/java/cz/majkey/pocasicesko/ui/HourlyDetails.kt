package cz.majkey.pocasicesko.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cz.majkey.pocasicesko.R
import cz.majkey.pocasicesko.data.HourlyWeather
import cz.majkey.pocasicesko.data.WeatherKind
import cz.majkey.pocasicesko.data.conditionFor
import cz.majkey.pocasicesko.data.hasPrecipitationEvidence
import cz.majkey.pocasicesko.units.WeatherUnitFormatter
import java.util.Locale
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

internal fun toggleExpandedHour(current: String?, clicked: String): String? {
    require(clicked.isNotBlank())
    return if (current == clicked) null else clicked
}

internal fun hourlyPrecipitationInterval(time: String, locale: Locale): String? {
    val end = runCatching { LocalDateTime.parse(time) }.getOrNull() ?: return null
    val start = runCatching { end.minusHours(1) }.getOrNull() ?: return null
    val crossesDate = start.toLocalDate() != end.toLocalDate()
    val format = DateTimeFormatter.ofPattern(if (crossesDate) "d MMM uuuu, HH:mm" else "HH:mm", locale)
    val separator = if (crossesDate) " – " else "–"
    return start.format(format) + separator + end.format(format)
}

// Source accumulations end at their timestamp. Keep the source object unchanged.
internal fun hourlyPrecipitationByStart(hours: List<HourlyWeather>): Map<String, HourlyWeather?> {
    val byTime = hours.mapNotNull { hour ->
        val time = runCatching { LocalDateTime.parse(hour.time) }.getOrNull() ?: return@mapNotNull null
        if (time.minute != 0 || time.second != 0 || time.nano != 0) return@mapNotNull null
        time to hour
    }.groupBy({ it.first }, { it.second })
    return buildMap {
        byTime.forEach { (start, rows) ->
            val end = runCatching { start.plusHours(1) }.getOrNull()
            val source = byTime[end]?.singleOrNull()
            rows.forEach { put(it.time, source) }
        }
    }
}

internal fun hourlyStartingPrecipitationInterval(time: String, locale: Locale): String? =
    runCatching { LocalDateTime.parse(time).plusHours(1).toString() }.getOrNull()
        ?.let { hourlyPrecipitationInterval(it, locale) }

internal enum class HourMetricKind {
    TEMPERATURE,
    FEELS_LIKE,
    DEW_POINT,
    WET_BULB,
    PRECIPITATION,
    RAIN,
    SHOWERS,
    SNOWFALL,
    SNOW_WATER,
    HUMIDITY,
    WIND,
    WIND_GUSTS,
    PRESSURE,
    SURFACE_PRESSURE,
    LOW_CLOUDS,
    MIDDLE_CLOUDS,
    HIGH_CLOUDS,
    UV,
    VISIBILITY,
    FREEZING_LEVEL,
    BOUNDARY_LAYER,
    INTEGRATED_WATER,
    LIFTED_INDEX,
    CONVECTIVE_INHIBITION,
    CAPE,
    VAPOUR_PRESSURE_DEFICIT,
    SURFACE_TEMPERATURE,
    ET0,
    SOIL_TEMPERATURE,
    SOIL_MOISTURE,
}

internal fun availableHourMetricKinds(hour: HourlyWeather, precipitationHour: HourlyWeather? = hour): List<HourMetricKind> = buildList {
    add(HourMetricKind.TEMPERATURE)
    add(HourMetricKind.FEELS_LIKE)
    if (hour.dewPoint != null) add(HourMetricKind.DEW_POINT)
    if (hour.wetBulbTemperature != null) add(HourMetricKind.WET_BULB)
    add(HourMetricKind.PRECIPITATION)
    if (precipitationHour?.rain != null) add(HourMetricKind.RAIN)
    if (precipitationHour?.showers != null) add(HourMetricKind.SHOWERS)
    if (precipitationHour?.snowfall != null) add(HourMetricKind.SNOWFALL)
    if (hour.snowDepthWaterEquivalent != null) add(HourMetricKind.SNOW_WATER)
    add(HourMetricKind.HUMIDITY)
    add(HourMetricKind.WIND)
    if (hour.windGusts != null) add(HourMetricKind.WIND_GUSTS)
    add(HourMetricKind.PRESSURE)
    if (hour.surfacePressure != null) add(HourMetricKind.SURFACE_PRESSURE)
    if (hour.cloudCoverLow != null) add(HourMetricKind.LOW_CLOUDS)
    if (hour.cloudCoverMid != null) add(HourMetricKind.MIDDLE_CLOUDS)
    if (hour.cloudCoverHigh != null) add(HourMetricKind.HIGH_CLOUDS)
    if (hour.uvIndex != null) add(HourMetricKind.UV)
    if (hour.visibilityMeters != null) add(HourMetricKind.VISIBILITY)
    if (hour.freezingLevelHeightMeters != null) add(HourMetricKind.FREEZING_LEVEL)
    if (hour.boundaryLayerHeightMeters != null) add(HourMetricKind.BOUNDARY_LAYER)
    if (hour.integratedWaterVapour != null) add(HourMetricKind.INTEGRATED_WATER)
    if (hour.liftedIndex != null) add(HourMetricKind.LIFTED_INDEX)
    if (hour.convectiveInhibition != null) add(HourMetricKind.CONVECTIVE_INHIBITION)
    if (hour.cape != null) add(HourMetricKind.CAPE)
    if (hour.vapourPressureDeficit != null) add(HourMetricKind.VAPOUR_PRESSURE_DEFICIT)
    if (hour.surfaceTemperature != null) add(HourMetricKind.SURFACE_TEMPERATURE)
    if (hour.et0 != null) add(HourMetricKind.ET0)
    if (hour.soilTemperature0Cm != null) add(HourMetricKind.SOIL_TEMPERATURE)
    if (hour.soilMoisture0To1Cm != null) add(HourMetricKind.SOIL_MOISTURE)
}

internal fun hourlyApparentTemperature(hour: HourlyWeather): Double =
    hour.apparentTemperature ?: hour.temperature

internal enum class HourlyRainLevel {
    NONE,
    FORECAST,
    UNLIKELY,
    POSSIBLE,
    LIKELY,
    HEAVY,
}

internal fun hourlyRainLevel(hour: HourlyWeather, weatherCode: Int = hour.weatherCode): HourlyRainLevel = when {
    hour.precipitation.isFinite() && hour.precipitation >= 5.0 -> HourlyRainLevel.HEAVY
    hasPrecipitationEvidence(weatherCode, hour.precipitation, hour.rain, hour.showers, hour.snowfall) ->
        HourlyRainLevel.FORECAST
    hour.precipitationProbability >= 70 -> HourlyRainLevel.LIKELY
    hour.precipitationProbability >= 40 -> HourlyRainLevel.POSSIBLE
    hour.precipitationProbability > 15 -> HourlyRainLevel.UNLIKELY
    else -> HourlyRainLevel.NONE
}

internal enum class HourlyHighlight {
    RAIN, SNOW, MIXED, FREEZING, PRECIPITATION, WIND, VISIBILITY, FEELS_LIKE, UV, CONDITIONS,
}

internal fun hourlyHighlight(hour: HourlyWeather, precipitationHour: HourlyWeather = hour): HourlyHighlight = when {
    hourlyRainLevel(precipitationHour, hour.weatherCode) != HourlyRainLevel.NONE -> when {
        hour.weatherCode in listOf(56, 57, 66, 67) -> HourlyHighlight.FREEZING
        (precipitationHour.snowfall ?: 0.0) > 0.0 && (precipitationHour.rain ?: 0.0) + (precipitationHour.showers ?: 0.0) > 0.0 ->
            HourlyHighlight.MIXED
        (precipitationHour.snowfall ?: 0.0) > 0.0 || conditionFor(hour.weatherCode, hour.isDay).kind == WeatherKind.SNOW ->
            HourlyHighlight.SNOW
        (precipitationHour.rain ?: 0.0) + (precipitationHour.showers ?: 0.0) > 0.0 ||
            conditionFor(hour.weatherCode, hour.isDay).kind in listOf(WeatherKind.RAIN, WeatherKind.STORM) ->
            HourlyHighlight.RAIN
        else -> HourlyHighlight.PRECIPITATION
    }
    (hour.windGusts ?: hour.windSpeed) >= 40.0 || hour.windSpeed >= 30.0 -> HourlyHighlight.WIND
    hour.visibilityMeters?.let { it < 1_000.0 } == true -> HourlyHighlight.VISIBILITY
    hour.apparentTemperature?.let { it <= 0.0 || it >= 30.0 || kotlin.math.abs(it - hour.temperature) >= 5.0 } == true ->
        HourlyHighlight.FEELS_LIKE
    hour.isDay && (hour.uvIndex ?: 0.0) >= 3.0 -> HourlyHighlight.UV
    else -> HourlyHighlight.CONDITIONS
}

@Composable
internal fun ExpandedHourDetails(
    hour: HourlyWeather,
    precipitationHour: HourlyWeather?,
    units: WeatherUnitFormatter,
    locale: Locale,
    modifier: Modifier = Modifier,
) {
    val metrics = availableHourMetricKinds(hour, precipitationHour).map { kind ->
        when (kind) {
            HourMetricKind.TEMPERATURE -> HourMetric(
                stringResource(R.string.temperature),
                units.temperature(hour.temperature),
            )
            HourMetricKind.FEELS_LIKE -> HourMetric(
                stringResource(R.string.feels_like),
                units.temperature(hourlyApparentTemperature(hour)),
            )
            HourMetricKind.DEW_POINT -> HourMetric(
                stringResource(R.string.dew_point),
                units.temperature(requireNotNull(hour.dewPoint)),
            )
            HourMetricKind.WET_BULB -> HourMetric(
                stringResource(R.string.wet_bulb_temperature),
                units.temperature(requireNotNull(hour.wetBulbTemperature)),
            )
            HourMetricKind.PRECIPITATION -> HourMetric(
                stringResource(R.string.precipitation),
                precipitationHour?.let { units.precipitation(it.precipitation) } ?: stringResource(R.string.unavailable),
            )
            HourMetricKind.RAIN -> HourMetric(
                stringResource(R.string.rain),
                units.precipitation(requireNotNull(precipitationHour?.rain)),
            )
            HourMetricKind.SHOWERS -> HourMetric(
                stringResource(R.string.showers),
                units.precipitation(requireNotNull(precipitationHour?.showers)),
            )
            HourMetricKind.SNOWFALL -> HourMetric(
                stringResource(R.string.snowfall),
                units.snowfall(requireNotNull(precipitationHour?.snowfall)),
            )
            HourMetricKind.SNOW_WATER -> HourMetric(
                stringResource(R.string.snow_water_equivalent),
                units.precipitation(requireNotNull(hour.snowDepthWaterEquivalent)),
            )
            HourMetricKind.HUMIDITY -> HourMetric(
                stringResource(R.string.humidity),
                "${hour.humidity} %",
            )
            HourMetricKind.WIND -> HourMetric(
                stringResource(R.string.wind),
                "${units.windSpeed(hour.windSpeed)} · " +
                    stringResource(windDirectionResource(hour.windDirection)) +
                    " · ${hour.windDirection}°",
            )
            HourMetricKind.WIND_GUSTS -> HourMetric(
                stringResource(R.string.wind_gusts),
                units.windSpeed(requireNotNull(hour.windGusts)),
            )
            HourMetricKind.PRESSURE -> HourMetric(
                stringResource(R.string.pressure),
                units.pressure(hour.pressure),
            )
            HourMetricKind.SURFACE_PRESSURE -> HourMetric(
                stringResource(R.string.surface_pressure),
                units.pressure(requireNotNull(hour.surfacePressure)),
            )
            HourMetricKind.LOW_CLOUDS -> HourMetric(
                stringResource(R.string.low_clouds),
                "${requireNotNull(hour.cloudCoverLow)} %",
            )
            HourMetricKind.MIDDLE_CLOUDS -> HourMetric(
                stringResource(R.string.middle_clouds),
                "${requireNotNull(hour.cloudCoverMid)} %",
            )
            HourMetricKind.HIGH_CLOUDS -> HourMetric(
                stringResource(R.string.high_clouds),
                "${requireNotNull(hour.cloudCoverHigh)} %",
            )
            HourMetricKind.UV -> HourMetric(
                stringResource(R.string.uv_index),
                String.format(locale, "%.1f", requireNotNull(hour.uvIndex)),
            )
            HourMetricKind.VISIBILITY -> HourMetric(
                stringResource(R.string.visibility),
                units.visibility(requireNotNull(hour.visibilityMeters)),
            )
            HourMetricKind.FREEZING_LEVEL -> HourMetric(
                stringResource(R.string.freezing_level),
                units.distance(requireNotNull(hour.freezingLevelHeightMeters) / 1_000.0),
            )
            HourMetricKind.BOUNDARY_LAYER -> HourMetric(
                stringResource(R.string.boundary_layer_height),
                units.distance(requireNotNull(hour.boundaryLayerHeightMeters) / 1_000.0),
            )
            HourMetricKind.INTEGRATED_WATER -> HourMetric(
                stringResource(R.string.integrated_water_vapour),
                String.format(locale, "%.1f kg/m²", requireNotNull(hour.integratedWaterVapour)),
            )
            HourMetricKind.LIFTED_INDEX -> HourMetric(
                stringResource(R.string.lifted_index),
                String.format(locale, "%.1f", requireNotNull(hour.liftedIndex)),
            )
            HourMetricKind.CONVECTIVE_INHIBITION -> HourMetric(
                stringResource(R.string.convective_inhibition),
                String.format(locale, "%.0f J/kg", requireNotNull(hour.convectiveInhibition)),
            )
            HourMetricKind.CAPE -> HourMetric(
                stringResource(R.string.cape),
                String.format(locale, "%.0f J/kg", requireNotNull(hour.cape)),
            )
            HourMetricKind.VAPOUR_PRESSURE_DEFICIT -> HourMetric(
                stringResource(R.string.vapour_pressure_deficit),
                String.format(locale, "%.1f kPa", requireNotNull(hour.vapourPressureDeficit)),
            )
            HourMetricKind.SURFACE_TEMPERATURE -> HourMetric(
                stringResource(R.string.surface_temperature),
                units.temperature(requireNotNull(hour.surfaceTemperature)),
            )
            HourMetricKind.ET0 -> HourMetric(
                stringResource(R.string.et0_evapotranspiration),
                units.precipitation(requireNotNull(hour.et0)),
            )
            HourMetricKind.SOIL_TEMPERATURE -> HourMetric(
                stringResource(R.string.soil_temperature),
                units.temperature(requireNotNull(hour.soilTemperature0Cm)),
            )
            HourMetricKind.SOIL_MOISTURE -> HourMetric(
                stringResource(R.string.soil_moisture),
                String.format(locale, "%.3f m³/m³", requireNotNull(hour.soilMoisture0To1Cm)),
            )
        }
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(R.string.precipitation_interval,
                hourlyStartingPrecipitationInterval(hour.time, locale) ?: stringResource(R.string.unavailable)),
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = Color(0xFFB9ECF5),
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = Color(0x1A6DD3EA),
            shape = RoundedCornerShape(16.dp),
        ) {
            Text(
                text = hourlyWeatherSummary(hour, units, precipitationHour),
                color = Color(0xFFB9ECF5),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            )
        }
        precipitationHour?.precipitationSpread?.let { spread ->
            val minimum = units.precipitation(spread.minimumMm)
            val maximum = units.precipitation(spread.maximumMm)
            Text(
                stringResource(
                    R.string.hourly_precipitation_models,
                    spread.wetModelCount,
                    spread.modelCount,
                    if (minimum == maximum) minimum else "$minimum – $maximum",
                ),
                color = Color.White.copy(alpha = 0.82f),
                fontSize = 12.sp,
            )
        }
        Text(
            stringResource(R.string.precipitation_probability_note),
            color = Color.White.copy(alpha = 0.62f),
            fontSize = 11.sp,
        )
        metrics.chunked(2).forEach { metricRow ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                metricRow.forEach { metric ->
                    HourMetricValue(metric, Modifier.weight(1f))
                }
                if (metricRow.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun hourlyWeatherSummary(hour: HourlyWeather, units: WeatherUnitFormatter, precipitationHour: HourlyWeather?): String {
    if (precipitationHour == null) return stringResource(R.string.hourly_precipitation_unavailable)
    val amount = units.precipitation(precipitationHour.precipitation)
    val highlight = hourlyHighlight(hour, precipitationHour)
    when (highlight) {
        HourlyHighlight.SNOW, HourlyHighlight.MIXED, HourlyHighlight.FREEZING, HourlyHighlight.PRECIPITATION -> {
            val resource = when (highlight) {
                HourlyHighlight.SNOW -> R.string.hourly_snow_summary
                HourlyHighlight.MIXED -> R.string.hourly_mixed_summary
                HourlyHighlight.FREEZING -> R.string.hourly_freezing_summary
                else -> R.string.hourly_precipitation_summary
            }
            val summary = stringResource(resource, amount, precipitationHour.precipitationProbability)
            return if ((precipitationHour.snowfall ?: 0.0) > 0.0) {
                summary + " " + stringResource(R.string.hourly_snowfall_summary, units.snowfall(requireNotNull(precipitationHour.snowfall)))
            } else summary
        }
        HourlyHighlight.WIND -> {
            val wind = stringResource(R.string.hourly_wind_summary, units.windSpeed(hour.windSpeed))
            return hour.windGusts?.let {
                wind + " " + stringResource(R.string.hourly_gusts_summary, units.windSpeed(it))
            } ?: wind
        }
        HourlyHighlight.VISIBILITY -> return stringResource(
            R.string.hourly_visibility_summary,
            units.visibility(requireNotNull(hour.visibilityMeters)),
        )
        HourlyHighlight.FEELS_LIKE -> return stringResource(
            R.string.hourly_feels_like_summary,
            units.temperature(requireNotNull(hour.apparentTemperature)),
            units.temperature(hour.temperature),
        )
        HourlyHighlight.UV -> return stringResource(R.string.hourly_uv_summary, requireNotNull(hour.uvIndex))
        HourlyHighlight.RAIN, HourlyHighlight.CONDITIONS -> Unit
    }
    return when (hourlyRainLevel(precipitationHour, hour.weatherCode)) {
        HourlyRainLevel.FORECAST -> stringResource(
            R.string.hourly_source_precipitation_summary,
            stringResource(
                if (hasPrecipitationEvidence(hour.weatherCode)) conditionFor(hour.weatherCode, hour.isDay).labelResource()
                else R.string.precipitation,
            ),
            amount,
            precipitationHour.precipitationProbability,
        )
        HourlyRainLevel.NONE -> stringResource(
            R.string.hourly_dry_summary,
            stringResource(conditionFor(hour.weatherCode, hour.isDay).labelResource()),
            precipitationHour.precipitationProbability,
            units.temperature(hourlyApparentTemperature(hour)),
            units.windSpeed(hour.windSpeed),
            stringResource(windDirectionResource(hour.windDirection)),
        )
        HourlyRainLevel.UNLIKELY -> stringResource(
            R.string.hourly_rain_unlikely,
            amount,
            precipitationHour.precipitationProbability,
        )
        HourlyRainLevel.POSSIBLE -> stringResource(
            R.string.hourly_rain_possible,
            amount,
            precipitationHour.precipitationProbability,
        )
        HourlyRainLevel.LIKELY -> stringResource(
            R.string.hourly_rain_likely,
            amount,
            precipitationHour.precipitationProbability,
        )
        HourlyRainLevel.HEAVY -> stringResource(
            R.string.hourly_rain_heavy,
            amount,
            precipitationHour.precipitationProbability,
        )
    }
}

@Composable
private fun HourMetricValue(metric: HourMetric, modifier: Modifier) {
    Column(modifier.padding(vertical = 2.dp)) {
        Text(
            text = metric.label,
            color = Color.White.copy(alpha = 0.48f),
            fontSize = 10.sp,
        )
        Text(
            text = metric.value,
            color = Color.White.copy(alpha = 0.88f),
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

private data class HourMetric(
    val label: String,
    val value: String,
)
