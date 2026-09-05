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
import cz.majkey.pocasicesko.units.WeatherUnitFormatter
import java.util.Locale

internal fun toggleExpandedHour(current: String?, clicked: String): String? {
    require(clicked.isNotBlank())
    return if (current == clicked) null else clicked
}

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

internal fun availableHourMetricKinds(hour: HourlyWeather): List<HourMetricKind> = buildList {
    add(HourMetricKind.TEMPERATURE)
    add(HourMetricKind.FEELS_LIKE)
    if (hour.dewPoint != null) add(HourMetricKind.DEW_POINT)
    if (hour.wetBulbTemperature != null) add(HourMetricKind.WET_BULB)
    add(HourMetricKind.PRECIPITATION)
    if (hour.rain != null) add(HourMetricKind.RAIN)
    if (hour.showers != null) add(HourMetricKind.SHOWERS)
    if (hour.snowfall != null) add(HourMetricKind.SNOWFALL)
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
    UNLIKELY,
    POSSIBLE,
    LIKELY,
    HEAVY,
}

internal fun hourlyRainLevel(hour: HourlyWeather): HourlyRainLevel = when {
    hour.precipitation >= 5.0 -> HourlyRainLevel.HEAVY
    hour.precipitationProbability >= 70 || hour.precipitation >= 2.0 -> HourlyRainLevel.LIKELY
    hour.precipitationProbability >= 40 || hour.precipitation >= 0.2 -> HourlyRainLevel.POSSIBLE
    hour.precipitationProbability > 15 || hour.precipitation > 0.0 -> HourlyRainLevel.UNLIKELY
    else -> HourlyRainLevel.NONE
}

internal enum class HourlyHighlight {
    RAIN, SNOW, MIXED, FREEZING, PRECIPITATION, WIND, VISIBILITY, FEELS_LIKE, UV, CONDITIONS,
}

internal fun hourlyHighlight(hour: HourlyWeather): HourlyHighlight = when {
    hourlyRainLevel(hour) != HourlyRainLevel.NONE || (hour.snowfall ?: 0.0) > 0.0 -> when {
        hour.weatherCode in listOf(56, 57, 66, 67) -> HourlyHighlight.FREEZING
        (hour.snowfall ?: 0.0) > 0.0 && (hour.rain ?: 0.0) + (hour.showers ?: 0.0) > 0.0 ->
            HourlyHighlight.MIXED
        (hour.snowfall ?: 0.0) > 0.0 || conditionFor(hour.weatherCode, hour.isDay).kind == WeatherKind.SNOW ->
            HourlyHighlight.SNOW
        (hour.rain ?: 0.0) + (hour.showers ?: 0.0) > 0.0 ||
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
    units: WeatherUnitFormatter,
    locale: Locale,
    modifier: Modifier = Modifier,
) {
    val metrics = availableHourMetricKinds(hour).map { kind ->
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
                units.precipitation(hour.precipitation),
            )
            HourMetricKind.RAIN -> HourMetric(
                stringResource(R.string.rain),
                units.precipitation(requireNotNull(hour.rain)),
            )
            HourMetricKind.SHOWERS -> HourMetric(
                stringResource(R.string.showers),
                units.precipitation(requireNotNull(hour.showers)),
            )
            HourMetricKind.SNOWFALL -> HourMetric(
                stringResource(R.string.snowfall),
                units.snowfall(requireNotNull(hour.snowfall)),
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
        Surface(
            color = Color(0x1A6DD3EA),
            shape = RoundedCornerShape(16.dp),
        ) {
            Text(
                text = hourlyWeatherSummary(hour, units),
                color = Color(0xFFB9ECF5),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            )
        }
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
private fun hourlyWeatherSummary(hour: HourlyWeather, units: WeatherUnitFormatter): String {
    val amount = units.precipitation(hour.precipitation)
    val highlight = hourlyHighlight(hour)
    when (highlight) {
        HourlyHighlight.SNOW, HourlyHighlight.MIXED, HourlyHighlight.FREEZING, HourlyHighlight.PRECIPITATION -> {
            val resource = when (highlight) {
                HourlyHighlight.SNOW -> R.string.hourly_snow_summary
                HourlyHighlight.MIXED -> R.string.hourly_mixed_summary
                HourlyHighlight.FREEZING -> R.string.hourly_freezing_summary
                else -> R.string.hourly_precipitation_summary
            }
            val summary = stringResource(resource, amount, hour.precipitationProbability)
            return if ((hour.snowfall ?: 0.0) > 0.0) {
                summary + " " + stringResource(R.string.hourly_snowfall_summary, units.snowfall(requireNotNull(hour.snowfall)))
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
    return when (hourlyRainLevel(hour)) {
        HourlyRainLevel.NONE -> stringResource(
            R.string.hourly_dry_summary,
            stringResource(conditionFor(hour.weatherCode, hour.isDay).labelResource()),
            hour.precipitationProbability,
            units.temperature(hourlyApparentTemperature(hour)),
            units.windSpeed(hour.windSpeed),
            stringResource(windDirectionResource(hour.windDirection)),
        )
        HourlyRainLevel.UNLIKELY -> stringResource(
            R.string.hourly_rain_unlikely,
            amount,
            hour.precipitationProbability,
        )
        HourlyRainLevel.POSSIBLE -> stringResource(
            R.string.hourly_rain_possible,
            amount,
            hour.precipitationProbability,
        )
        HourlyRainLevel.LIKELY -> stringResource(
            R.string.hourly_rain_likely,
            amount,
            hour.precipitationProbability,
        )
        HourlyRainLevel.HEAVY -> stringResource(
            R.string.hourly_rain_heavy,
            amount,
            hour.precipitationProbability,
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
