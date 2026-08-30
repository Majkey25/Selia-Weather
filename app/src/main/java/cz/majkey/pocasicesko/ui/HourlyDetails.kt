package cz.majkey.pocasicesko.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cz.majkey.pocasicesko.R
import cz.majkey.pocasicesko.data.HourlyWeather
import cz.majkey.pocasicesko.units.WeatherUnitFormatter
import java.util.Locale

internal fun toggleExpandedHour(current: String?, clicked: String): String? {
    require(clicked.isNotBlank())
    return if (current == clicked) null else clicked
}

internal enum class HourMetricKind {
    FEELS_LIKE,
    PRECIPITATION,
    HUMIDITY,
    WIND_GUSTS,
    PRESSURE,
    LOW_CLOUDS,
    MIDDLE_CLOUDS,
    HIGH_CLOUDS,
    UV,
    VISIBILITY,
}

internal fun availableHourMetricKinds(hour: HourlyWeather): List<HourMetricKind> = buildList {
    if (hour.apparentTemperature != null) add(HourMetricKind.FEELS_LIKE)
    add(HourMetricKind.PRECIPITATION)
    add(HourMetricKind.HUMIDITY)
    if (hour.windGusts != null) add(HourMetricKind.WIND_GUSTS)
    add(HourMetricKind.PRESSURE)
    if (hour.cloudCoverLow != null) add(HourMetricKind.LOW_CLOUDS)
    if (hour.cloudCoverMid != null) add(HourMetricKind.MIDDLE_CLOUDS)
    if (hour.cloudCoverHigh != null) add(HourMetricKind.HIGH_CLOUDS)
    if (hour.uvIndex != null) add(HourMetricKind.UV)
    if (hour.visibilityMeters != null) add(HourMetricKind.VISIBILITY)
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
            HourMetricKind.FEELS_LIKE -> HourMetric(
                stringResource(R.string.feels_like),
                units.temperature(requireNotNull(hour.apparentTemperature)),
            )
            HourMetricKind.PRECIPITATION -> HourMetric(
                stringResource(R.string.precipitation),
                units.precipitation(hour.precipitation),
            )
            HourMetricKind.HUMIDITY -> HourMetric(
                stringResource(R.string.humidity),
                "${hour.humidity} %",
            )
            HourMetricKind.WIND_GUSTS -> HourMetric(
                stringResource(R.string.wind_gusts),
                units.windSpeed(requireNotNull(hour.windGusts)),
            )
            HourMetricKind.PRESSURE -> HourMetric(
                stringResource(R.string.pressure),
                units.pressure(hour.pressure),
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
        }
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
private fun HourMetricValue(metric: HourMetric, modifier: Modifier) {
    Column(modifier.padding(vertical = 2.dp)) {
        Text(
            text = metric.label,
            color = Color.White.copy(alpha = 0.48f),
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = metric.value,
            color = Color.White.copy(alpha = 0.88f),
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private data class HourMetric(
    val label: String,
    val value: String,
)
