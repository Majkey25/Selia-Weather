package cz.majkey.pocasicesko.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
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
    PRECIPITATION_PROBABILITY,
    RAIN,
    SHOWERS,
    SNOWFALL,
    SNOW_WATER,
    HUMIDITY,
    WIND,
    WIND_GUSTS,
    PRESSURE,
    SURFACE_PRESSURE,
    CLOUD_COVER,
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
    if (hour.apparentTemperature != null) add(HourMetricKind.FEELS_LIKE)
    if (hour.dewPoint != null) add(HourMetricKind.DEW_POINT)
    if (hour.wetBulbTemperature != null) add(HourMetricKind.WET_BULB)
    add(HourMetricKind.PRECIPITATION)
    if (precipitationHour != null) add(HourMetricKind.PRECIPITATION_PROBABILITY)
    if (precipitationHour?.rain != null) add(HourMetricKind.RAIN)
    if (precipitationHour?.showers != null) add(HourMetricKind.SHOWERS)
    if (precipitationHour?.snowfall != null) add(HourMetricKind.SNOWFALL)
    if (hour.snowDepthWaterEquivalent != null) add(HourMetricKind.SNOW_WATER)
    add(HourMetricKind.HUMIDITY)
    add(HourMetricKind.WIND)
    if (hour.windGusts != null) add(HourMetricKind.WIND_GUSTS)
    add(HourMetricKind.PRESSURE)
    if (hour.surfacePressure != null) add(HourMetricKind.SURFACE_PRESSURE)
    if (hour.cloudCover != null) add(HourMetricKind.CLOUD_COVER)
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
    if (precipitationHour?.et0 != null) add(HourMetricKind.ET0)
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
    RAIN, SNOW, MIXED, FREEZING, PRECIPITATION, MODEL_DISAGREEMENT, WIND, VISIBILITY, FEELS_LIKE, UV, CONDITIONS,
}

internal fun hourlyHighlight(hour: HourlyWeather, precipitationHour: HourlyWeather? = hour): HourlyHighlight = when {
    precipitationHour != null && hourlyRainLevel(precipitationHour, hour.weatherCode) != HourlyRainLevel.NONE -> when {
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
    (precipitationHour?.precipitationSpread?.wetModelCount ?: 0) > 0 -> HourlyHighlight.MODEL_DISAGREEMENT
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
    val resources = LocalContext.current.resources
    var showPrecipitationHelp by rememberSaveable(hour.time) { mutableStateOf(false) }
    var showAdvanced by rememberSaveable(hour.time) { mutableStateOf(false) }
    val helpExpansionState = stringResource(
        if (showPrecipitationHelp) R.string.hour_expanded else R.string.hour_collapsed,
    )
    val advancedState = stringResource(if (showAdvanced) R.string.hour_expanded else R.string.hour_collapsed)
    val kinds = BASIC_HOUR_METRICS + availableHourMetricKinds(hour, precipitationHour).filterNot { it in BASIC_HOUR_METRICS }
    val metrics = kinds.map { kind ->
        when (kind) {
            HourMetricKind.TEMPERATURE -> HourMetric(
                stringResource(R.string.temperature),
                units.temperature(hour.temperature),
            )
            HourMetricKind.FEELS_LIKE -> HourMetric(
                stringResource(R.string.feels_like),
                hour.apparentTemperature?.let(units::temperature) ?: stringResource(R.string.unavailable),
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
            HourMetricKind.PRECIPITATION_PROBABILITY -> HourMetric(
                stringResource(R.string.precipitation_probability),
                precipitationHour?.let { "${it.precipitationProbability} %" } ?: stringResource(R.string.unavailable),
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
                hour.windGusts?.let(units::windSpeed) ?: stringResource(R.string.unavailable),
            )
            HourMetricKind.PRESSURE -> HourMetric(
                stringResource(R.string.pressure),
                units.pressure(hour.pressure),
            )
            HourMetricKind.SURFACE_PRESSURE -> HourMetric(
                stringResource(R.string.surface_pressure),
                units.pressure(requireNotNull(hour.surfacePressure)),
            )
            HourMetricKind.CLOUD_COVER -> HourMetric(
                stringResource(R.string.cloud_cover),
                "${requireNotNull(hour.cloudCover)} %",
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
                hour.uvIndex?.let { String.format(locale, "%.1f", it) } ?: stringResource(R.string.unavailable),
            )
            HourMetricKind.VISIBILITY -> HourMetric(
                stringResource(R.string.visibility),
                hour.visibilityMeters?.let(units::visibility) ?: stringResource(R.string.unavailable),
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
                units.precipitation(requireNotNull(precipitationHour?.et0)),
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
                text = hourlyWeatherSummary(hour, units, precipitationHour, locale, resources::getString),
                color = Color(0xFFB9ECF5),
                fontSize = 12.sp,
                lineHeight = 18.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            )
        }
        TextButton(
            onClick = { showPrecipitationHelp = !showPrecipitationHelp },
            modifier = Modifier.semantics { stateDescription = helpExpansionState },
        ) {
            Icon(Icons.Rounded.Info, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.precipitation_help), fontSize = 12.sp, lineHeight = 18.sp)
        }
        if (showPrecipitationHelp) {
            precipitationHour?.precipitationSpread?.let { spread ->
                val minimum = units.precipitation(spread.minimumMm)
                val maximum = units.precipitation(spread.maximumMm)
                Text(
                    stringResource(R.string.hourly_precipitation_models, spread.wetModelCount,
                        spread.modelCount, if (minimum == maximum) minimum else "$minimum – $maximum"),
                    color = Color.White.copy(alpha = 0.82f),
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                )
            }
            Text(
                stringResource(R.string.precipitation_probability_note),
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
        }
        HourMetricGrid(metrics.take(BASIC_HOUR_METRICS.size))
        if (metrics.size > BASIC_HOUR_METRICS.size) {
            TextButton(
                onClick = { showAdvanced = !showAdvanced },
                modifier = Modifier.semantics { stateDescription = advancedState },
            ) {
                Icon(if (showAdvanced) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.advanced_details))
            }
            if (showAdvanced) HourMetricGrid(metrics.drop(BASIC_HOUR_METRICS.size))
        }
    }
}

internal fun hourlyWeatherSummary(
    hour: HourlyWeather,
    units: WeatherUnitFormatter,
    precipitationHour: HourlyWeather?,
    locale: Locale,
    resourceText: (Int) -> String,
): String {
    fun text(resource: Int, vararg arguments: Any): String =
        String.format(locale, resourceText(resource), *arguments)

    val highlight = hourlyHighlight(hour, precipitationHour)
    val summary = when (highlight) {
        HourlyHighlight.MODEL_DISAGREEMENT -> text(R.string.hour_summary_uncertain)
        HourlyHighlight.RAIN, HourlyHighlight.SNOW, HourlyHighlight.MIXED,
        HourlyHighlight.FREEZING, HourlyHighlight.PRECIPITATION -> {
            val precipitation = requireNotNull(precipitationHour)
            val resource = when {
                highlight == HourlyHighlight.FREEZING -> R.string.hour_summary_freezing
                hour.weatherCode in 95..99 -> R.string.hour_summary_storm
                highlight == HourlyHighlight.SNOW -> R.string.hour_summary_snow
                highlight == HourlyHighlight.MIXED -> R.string.hour_summary_mixed
                highlight == HourlyHighlight.RAIN && hourlyRainLevel(precipitation, hour.weatherCode) == HourlyRainLevel.HEAVY ->
                    R.string.hour_summary_heavy
                highlight == HourlyHighlight.RAIN -> R.string.hour_summary_rain
                else -> R.string.hour_summary_precipitation
            }
            val snow = precipitation.snowfall?.takeIf { it.isFinite() && it > 0.0 }
            val amount = if (highlight == HourlyHighlight.SNOW && snow != null) {
                units.snowfall(snow)
            } else precipitation.precipitation.takeIf { it.isFinite() && it > 0.0 }?.let {
                val formatted = units.precipitation(it)
                if (highlight in listOf(HourlyHighlight.SNOW, HourlyHighlight.MIXED)) {
                    text(R.string.hour_summary_water_equivalent, formatted)
                } else formatted
            }
            val chance = precipitation.precipitationProbability.takeIf { it in 1..100 }
            val detail = when {
                amount != null && chance != null -> text(R.string.hour_summary_amount_chance, amount, chance)
                amount != null -> text(R.string.hour_summary_amount, amount)
                chance != null -> text(R.string.hour_summary_chance, chance)
                else -> ""
            }
            listOf(text(resource), detail).filter(String::isNotEmpty).joinToString(" ")
        }
        HourlyHighlight.WIND -> {
            val wind = text(R.string.hourly_wind_summary, units.windSpeed(hour.windSpeed))
            hour.windGusts?.let {
                wind + " " + text(R.string.hourly_gusts_summary, units.windSpeed(it))
            } ?: wind
        }
        HourlyHighlight.VISIBILITY -> text(
            R.string.hourly_visibility_summary,
            units.visibility(requireNotNull(hour.visibilityMeters)),
        )
        HourlyHighlight.FEELS_LIKE -> text(
            R.string.hour_summary_feels_like,
            units.temperature(requireNotNull(hour.apparentTemperature)),
            units.temperature(hour.temperature),
        )
        HourlyHighlight.UV -> text(R.string.hourly_uv_summary, requireNotNull(hour.uvIndex))
        HourlyHighlight.CONDITIONS -> text(
            if (hour.apparentTemperature != null) R.string.hour_summary_conditions else R.string.hour_summary_temperature,
            text(conditionFor(hour.weatherCode, hour.isDay).labelResource()),
            units.temperature(hourlyApparentTemperature(hour)),
        )
    }
    return if (precipitationHour == null) summary + " " + text(R.string.hourly_precipitation_unavailable) else summary
}

@Composable
private fun HourMetricGrid(metrics: List<HourMetric>) {
    metrics.chunked(2).forEach { metricRow ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            metricRow.forEach { metric -> HourMetricValue(metric, Modifier.weight(1f)) }
            if (metricRow.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}

private val BASIC_HOUR_METRICS = listOf(
    HourMetricKind.TEMPERATURE, HourMetricKind.FEELS_LIKE,
    HourMetricKind.PRECIPITATION, HourMetricKind.PRECIPITATION_PROBABILITY,
    HourMetricKind.UV, HourMetricKind.HUMIDITY,
    HourMetricKind.WIND, HourMetricKind.WIND_GUSTS,
    HourMetricKind.PRESSURE, HourMetricKind.VISIBILITY,
)

@Composable
private fun HourMetricValue(metric: HourMetric, modifier: Modifier) {
    Column(modifier.padding(vertical = 2.dp)) {
        Text(
            text = metric.label,
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 10.sp,
            lineHeight = 14.sp,
        )
        Text(
            text = metric.value,
            color = Color.White.copy(alpha = 0.88f),
            fontSize = 13.sp,
            lineHeight = 18.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

private data class HourMetric(
    val label: String,
    val value: String,
)
