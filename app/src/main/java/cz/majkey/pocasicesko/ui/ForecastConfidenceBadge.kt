package cz.majkey.pocasicesko.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cz.majkey.pocasicesko.R
import cz.majkey.pocasicesko.data.ConfidenceLevel
import cz.majkey.pocasicesko.data.ConfidenceReason
import cz.majkey.pocasicesko.data.ForecastConfidence
import cz.majkey.pocasicesko.data.ForecastDisagreement
import cz.majkey.pocasicesko.data.conditionFor
import cz.majkey.pocasicesko.units.WeatherUnitFormatter
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
internal fun ForecastConfidenceBadge(confidence: ForecastConfidence, units: WeatherUnitFormatter) {
    var show by rememberSaveable(confidence.validTime) { mutableStateOf(false) }
    val level = stringResource(confidence.level.labelResource())
    val description = stringResource(R.string.confidence_label, level)
    val evidenceContext = confidenceEvidenceContext(confidence.validTime, LocalConfiguration.current.locales[0])
    TextButton(onClick = { show = true }, modifier = Modifier.heightIn(min = 48.dp)) {
        ConfidenceRating(confidence.level, text = description)
    }
    if (show) AlertDialog(onDismissRequest = { show = false },
        title = { Text(description) },
        text = {
            Column(Modifier.heightIn(max = LocalConfiguration.current.screenHeightDp.dp * 0.65f)
                .verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                confidence.expectedHours?.let { expected ->
                    Text(stringResource(R.string.confidence_day_coverage, confidence.coveredHours ?: 0, expected))
                }
                if (confidence.expectedHours == null || evidenceContext != null) {
                    if (evidenceContext != null && (confidence.expectedHours != null || confidence.reason == ConfidenceReason.HISTORICAL)) {
                        if (confidence.expectedHours != null) Text(stringResource(R.string.confidence_day_method))
                        Text("${stringResource(R.string.forecast)} · ${requireNotNull(evidenceContext).validTime}")
                    }
                    confidence.evidence?.let { evidence ->
                        Text(stringResource(R.string.confidence_models, confidence.modelCount))
                        Text(stringResource(R.string.confidence_temperature_spread, units.temperatureDifference(evidence.temperatureRangeCelsius)))
                        Text(stringResource(R.string.confidence_wind_spread, units.windSpeed(evidence.windRangeKmh)))
                        Text(stringResource(R.string.confidence_cloud_spread, evidence.cloudRangePercent.toInt()))
                    }
                    confidence.precipitationEvidence?.let { rain ->
                        if (confidence.expectedHours != null) Text(stringResource(R.string.precipitation_interval,
                            requireNotNull(evidenceContext).precipitationInterval))
                        Text("${stringResource(R.string.precipitation)} · ${units.precipitation(rain.minimumPrecipitationMm)} – ${units.precipitation(rain.maximumPrecipitationMm)}")
                    }
                }
                Text(stringResource(R.string.confidence_method), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                ForecastModelComparison(confidence, units)
            }
        }, confirmButton = { TextButton(onClick = { show = false }) { Text(stringResource(android.R.string.ok)) } })
}

@Composable
internal fun ForecastModelComparison(confidence: ForecastConfidence, units: WeatherUnitFormatter, isDay: Boolean = true) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val locale = LocalConfiguration.current.locales[0]
        val forecastTime = confidenceEvidenceContext(confidence.validTime, locale)?.validTime
        val shownTime = confidenceEvidenceContext(confidence.shownConditionTime, locale)?.validTime
        if (forecastTime != null && shownTime != null && shownTime != forecastTime) {
            Text("${stringResource(R.string.now)} · $shownTime", style = MaterialTheme.typography.bodySmall)
            Text("${stringResource(R.string.forecast)} · $forecastTime", style = MaterialTheme.typography.titleSmall)
        }
        Text(stringResource(R.string.confidence_label, stringResource(confidence.level.labelResource())),
            style = MaterialTheme.typography.titleSmall)
        Text(stringResource(when (confidence.reason) {
            ConfidenceReason.AGREEMENT -> R.string.confidence_agreement
            ConfidenceReason.DISAGREEMENT -> R.string.confidence_disagreement
            ConfidenceReason.LONG_RANGE -> R.string.confidence_long_range
            ConfidenceReason.FETCH_AGE -> R.string.confidence_old_fetch
            ConfidenceReason.MISSING_MODELS -> R.string.confidence_missing
            ConfidenceReason.PARTIAL_DAY -> R.string.confidence_partial_day
            ConfidenceReason.HISTORICAL -> R.string.confidence_historical
            ConfidenceReason.UNKNOWN_CONDITION -> R.string.confidence_unknown_condition
        }), style = MaterialTheme.typography.bodySmall)
        confidence.disagreements.forEach { disagreement ->
            Text("• " + stringResource(when (disagreement) {
                ForecastDisagreement.TEMPERATURE -> R.string.confidence_conflict_temperature
                ForecastDisagreement.WIND -> R.string.confidence_conflict_wind
                ForecastDisagreement.CLOUDS -> R.string.confidence_conflict_clouds
                ForecastDisagreement.CONDITIONS -> R.string.confidence_conflict_conditions
                ForecastDisagreement.RAIN_OCCURRENCE -> R.string.confidence_conflict_rain
                ForecastDisagreement.RAIN_AMOUNT -> R.string.confidence_conflict_amount
                ForecastDisagreement.DISPLAYED_CONDITION -> R.string.confidence_conflict_displayed_sky
                ForecastDisagreement.DISPLAYED_PRECIPITATION -> R.string.confidence_conflict_displayed_rain
            }), style = MaterialTheme.typography.bodySmall)
        }
        val inputs = confidence.evidence?.modelInputs.orEmpty()
        if (inputs.isEmpty()) {
            Text(stringResource(R.string.confidence_model_values_missing), style = MaterialTheme.typography.bodySmall)
        } else {
            Text(stringResource(R.string.confidence_model_values), style = MaterialTheme.typography.titleSmall)
            val precipitation = confidence.precipitationEvidence?.modelInputs.orEmpty().associateBy { it.modelId }
            inputs.forEach { input ->
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text("${forecastModelName(input.modelId)} · ${stringResource(conditionFor(input.weatherCode, isDay).labelResource())}",
                    style = MaterialTheme.typography.labelLarge)
                Text("${units.temperature(input.temperatureCelsius)} · ${stringResource(R.string.cloud_cover)} ${input.cloudCoverPercent.toInt()}% · " +
                    "${stringResource(R.string.wind)} ${units.windSpeed(input.windSpeedKmh)} ${stringResource(windDirectionResource(input.windDirectionDegrees.toInt()))}",
                    style = MaterialTheme.typography.bodySmall)
                Text("${stringResource(R.string.precipitation)} · " + (precipitation[input.modelId]?.let {
                    units.precipitation(it.precipitationMm)
                } ?: stringResource(R.string.unavailable)), style = MaterialTheme.typography.bodySmall)
            }
        }
        confidence.skySourceModelId?.let { Text(stringResource(R.string.confidence_regional_sky, forecastModelName(it)),
            style = MaterialTheme.typography.bodySmall) }
        Text(stringResource(R.string.confidence_time_support), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

internal fun forecastModelName(id: String): String = when (id) {
    "chmi_aladin_seamless" -> "ČHMÚ ALADIN seamless"
    "icon_seamless" -> "DWD ICON seamless"
    "ecmwf_ifs025" -> "ECMWF IFS"
    "ecmwf_aifs025_single" -> "ECMWF AIFS"
    "gfs_seamless" -> "NOAA GFS seamless"
    "gem_seamless" -> "ECCC GEM seamless"
    "meteofrance_seamless" -> "Météo-France seamless"
    "ukmo_seamless" -> "UK Met Office seamless"
    "cma_grapes_global" -> "CMA GRAPES"
    "jma_seamless" -> "JMA seamless"
    "bom_access_global" -> "BOM ACCESS"
    else -> id
}

internal data class ConfidenceEvidenceContext(val validTime: String, val precipitationInterval: String)

internal fun confidenceEvidenceContext(time: String?, locale: Locale): ConfidenceEvidenceContext? {
    val sourceTime = time ?: return null
    val validTime = runCatching {
        LocalDateTime.parse(sourceTime).format(DateTimeFormatter.ofPattern("d MMM uuuu, HH:mm", locale))
    }.getOrNull() ?: return null
    val interval = hourlyStartingPrecipitationInterval(sourceTime, locale) ?: return null
    return ConfidenceEvidenceContext(validTime, interval)
}

@Composable
internal fun ConfidenceRating(level: ConfidenceLevel, modifier: Modifier = Modifier, text: String = stringResource(level.labelResource())) {
    val description = stringResource(R.string.confidence_label, stringResource(level.labelResource()))
    val active = MaterialTheme.colorScheme.primary
    val inactive = MaterialTheme.colorScheme.outlineVariant
    Row(modifier.semantics(mergeDescendants = true) { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (level == ConfidenceLevel.LIMITED) {
            Text("—", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.clearAndSetSemantics { })
        } else Canvas(Modifier.size(16.dp, 14.dp).clearAndSetSemantics { }) {
            repeat(3) { index ->
                val height = size.height * (index + 2) / 4
                drawRoundRect(if (index < confidenceBarCount(level)) active else inactive,
                    Offset(index * size.width / 3, size.height - height), Size(size.width / 5, height), CornerRadius(1.dp.toPx()))
            }
        }
        Text(text, fontSize = 12.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.clearAndSetSemantics { })
    }
}

internal fun confidenceBarCount(level: ConfidenceLevel): Int = when (level) {
    ConfidenceLevel.HIGH -> 3
    ConfidenceLevel.MODERATE -> 2
    ConfidenceLevel.LOW -> 1
    ConfidenceLevel.LIMITED -> 0
}

internal fun ConfidenceLevel.labelResource(): Int = when (this) {
    ConfidenceLevel.HIGH -> R.string.confidence_high
    ConfidenceLevel.MODERATE -> R.string.confidence_moderate
    ConfidenceLevel.LOW -> R.string.confidence_low
    ConfidenceLevel.LIMITED -> R.string.confidence_limited
}
