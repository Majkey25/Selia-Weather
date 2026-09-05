package cz.majkey.pocasicesko.ui

import android.content.ActivityNotFoundException
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cz.majkey.pocasicesko.R
import cz.majkey.pocasicesko.astro.MoonCalculator
import cz.majkey.pocasicesko.astro.MoonDetails
import cz.majkey.pocasicesko.astro.MoonPhaseKey
import cz.majkey.pocasicesko.data.CzechLocation
import cz.majkey.pocasicesko.data.CalibrationTruthClass
import cz.majkey.pocasicesko.data.DailyWeather
import cz.majkey.pocasicesko.data.ForecastCalculation
import cz.majkey.pocasicesko.data.ForecastCalculationMode
import cz.majkey.pocasicesko.data.ForecastFallbackReason
import cz.majkey.pocasicesko.data.ForecastRegion
import cz.majkey.pocasicesko.data.HistoricalDay
import cz.majkey.pocasicesko.data.HistoryArchive
import cz.majkey.pocasicesko.data.HistorySummary
import cz.majkey.pocasicesko.data.HourlyWeather
import cz.majkey.pocasicesko.data.WeatherSnapshot
import cz.majkey.pocasicesko.data.currentDay
import cz.majkey.pocasicesko.data.summary
import cz.majkey.pocasicesko.data.inDateRange
import cz.majkey.pocasicesko.units.WeatherUnitFormatter
import java.time.LocalDateTime
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.roundToInt
import java.io.IOException
import kotlinx.coroutines.launch
import org.json.JSONException

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WeatherDetailSheet(
    snapshot: WeatherSnapshot,
    location: CzechLocation,
    units: WeatherUnitFormatter,
    loadHistory: suspend (CzechLocation) -> HistoryArchive,
    initialHistory: Boolean = false,
    onDismiss: () -> Unit,
) {
    val locale = LocalConfiguration.current.locales[0]
    val at = remember(snapshot.current.time, snapshot.timezone) {
        runCatching {
            LocalDateTime.parse(snapshot.current.time).atZone(ZoneId.of(snapshot.timezone))
        }.getOrNull()
    }
    val moon = remember(at, location) {
        at?.let { time ->
            runCatching {
                MoonCalculator.calculate(time, location.latitude, location.longitude)
            }.getOrNull()
        }
    }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val today = snapshot.daily.firstOrNull()?.let { snapshot.currentDay() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val shareChooserTitle = stringResource(R.string.history_share_chooser)
    var historyState by remember(location) { mutableStateOf<HistoryUiState>(HistoryUiState.Idle) }
    var showHistoryDays by remember(location) { mutableStateOf(false) }
    var historyShareError by remember(location) { mutableStateOf(false) }
    var historyPeriod by rememberSaveable(location.latitude, location.longitude) { mutableStateOf(HistoryPeriod.ALL) }
    var customStart by rememberSaveable(location.latitude, location.longitude) { mutableStateOf<Long?>(null) }
    var customEnd by rememberSaveable(location.latitude, location.longitude) { mutableStateOf<Long?>(null) }
    var showRangePicker by rememberSaveable(location.latitude, location.longitude) { mutableStateOf(false) }
    val customRange = customStart?.let { start -> customEnd?.let { end ->
        historyDateFromUtcMillis(start)..historyDateFromUtcMillis(end)
    } }
    val historyIndex = if (snapshot.calculation == null) 2 else 3
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = if (initialHistory) historyIndex else 0)
    fun loadArchive() {
        historyState = HistoryUiState.Loading
        showHistoryDays = false
        historyShareError = false
        scope.launch {
            historyState = try {
                HistoryUiState.Content(loadHistory(location))
            } catch (_: IOException) {
                HistoryUiState.Error
            } catch (_: JSONException) {
                HistoryUiState.Error
            }
        }
    }
    fun shareArchive(archive: HistoryArchive) {
        historyShareError = false
        scope.launch {
            try {
                context.startActivity(createHistoryShareIntent(context, archive, shareChooserTitle))
            } catch (_: IOException) {
                historyShareError = true
            } catch (_: ActivityNotFoundException) {
                historyShareError = true
            } catch (_: IllegalArgumentException) {
                historyShareError = true
            }
        }
    }
    LaunchedEffect(initialHistory, location) {
        if (initialHistory) loadArchive()
    }
    LaunchedEffect(initialHistory, historyIndex) {
        if (initialHistory) listState.scrollToItem(historyIndex)
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF101820),
        contentColor = Color.White,
        sheetState = sheetState,
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .navigationBarsPadding(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(26.dp),
        ) {
            item {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.back)) }
                Text(
                    stringResource(R.string.weather_details),
                    fontSize = 28.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    location.name,
                    color = Color.White.copy(alpha = 0.58f),
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            item {
                AtAGlanceSection(snapshot, today, units, locale)
            }
            snapshot.calculation?.let { calculation ->
                item { ForecastCalculationSection(calculation) }
            }
            item {
                HistoryArchiveSection(
                    state = historyState,
                    units = units,
                    locale = locale,
                    showDays = showHistoryDays,
                    shareError = historyShareError,
                    onLoad = ::loadArchive,
                    onShare = ::shareArchive,
                    onToggleDays = { showHistoryDays = !showHistoryDays },
                    period = historyPeriod,
                    customRange = customRange,
                    onPeriod = {
                        if (it == HistoryPeriod.CUSTOM) showRangePicker = true else historyPeriod = it
                    },
                )
            }
            val history = (historyState as? HistoryUiState.Content)?.archive
            if (history != null && showHistoryDays) {
                item {
                    Text(
                        stringResource(R.string.history_daily_title),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                items(history.periodDays(historyPeriod, customRange).asReversed(), key = { it.date.toString() }) { day ->
                    HistoricalDayRow(day, units, locale)
                }
            }
            item {
                DetailSection(stringResource(R.string.current_details)) {
                    DetailRow(stringResource(R.string.temperature), units.temperature(snapshot.current.temperature))
                    DetailRow(stringResource(R.string.feels_like), units.temperature(snapshot.current.feelsLike))
                    OptionalDetailRow(
                        stringResource(R.string.dew_point),
                        snapshot.current.dewPoint?.let(units::temperature),
                    )
                    OptionalDetailRow(
                        stringResource(R.string.wet_bulb_temperature),
                        snapshot.current.wetBulbTemperature?.let(units::temperature),
                    )
                    OptionalDetailRow(
                        stringResource(R.string.apparent_temperature_range),
                        today?.let { day ->
                            day.apparentTemperatureMin?.let { minimum ->
                                day.apparentTemperatureMax?.let { maximum ->
                                    "${units.temperature(minimum)} – ${units.temperature(maximum)}"
                                }
                            }
                        },
                    )
                    DetailRow(stringResource(R.string.humidity), "${snapshot.current.humidity} %")
                    DetailRow(stringResource(R.string.pressure), units.pressure(snapshot.current.pressure))
                    OptionalDetailRow(
                        stringResource(R.string.surface_pressure),
                        snapshot.current.surfacePressure?.let(units::pressure),
                    )
                    OptionalDetailRow(
                        stringResource(R.string.visibility),
                        snapshot.current.visibilityMeters?.let(units::visibility),
                    )
                }
            }
            item {
                DetailSection(stringResource(R.string.precipitation_and_clouds)) {
                    DetailRow(
                        stringResource(R.string.precipitation),
                        units.precipitation(snapshot.current.precipitation),
                    )
                    OptionalDetailRow(
                        stringResource(R.string.rain),
                        snapshot.current.rain?.let(units::precipitation),
                    )
                    OptionalDetailRow(
                        stringResource(R.string.showers),
                        snapshot.current.showers?.let(units::precipitation),
                    )
                    OptionalDetailRow(
                        stringResource(R.string.snowfall),
                        snapshot.current.snowfall?.let(units::snowfall),
                    )
                    OptionalDetailRow(
                        stringResource(R.string.snow_water_equivalent),
                        snapshot.current.snowDepthWaterEquivalent?.let(units::precipitation),
                    )
                    OptionalDetailRow(
                        stringResource(R.string.today_precipitation),
                        today?.let { units.precipitation(it.precipitationSum) },
                    )
                    OptionalDetailRow(
                        stringResource(R.string.today_rain),
                        today?.rainSum?.let(units::precipitation),
                    )
                    OptionalDetailRow(
                        stringResource(R.string.today_snowfall),
                        today?.snowfallSum?.let(units::snowfall),
                    )
                    OptionalDetailRow(
                        stringResource(R.string.precipitation_probability),
                        today?.let { "${it.precipitationProbability} %" },
                    )
                    OptionalDetailRow(
                        stringResource(R.string.precipitation_hours),
                        today?.precipitationHours?.let { String.format(locale, "%.1f h", it) },
                    )
                    DetailRow(stringResource(R.string.cloud_cover), "${snapshot.current.cloudCover} %")
                    OptionalDetailRow(
                        stringResource(R.string.low_clouds),
                        snapshot.current.cloudCoverLow?.let { "$it %" },
                    )
                    OptionalDetailRow(
                        stringResource(R.string.middle_clouds),
                        snapshot.current.cloudCoverMid?.let { "$it %" },
                    )
                    OptionalDetailRow(
                        stringResource(R.string.high_clouds),
                        snapshot.current.cloudCoverHigh?.let { "$it %" },
                    )
                }
            }
            item {
                DetailSection(stringResource(R.string.wind)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Rounded.Navigation,
                            contentDescription = null,
                            modifier = Modifier
                                .size(42.dp)
                                .rotate(snapshot.current.windDirection.toFloat()),
                            tint = Color(0xFF83D6E8),
                        )
                        Column(Modifier.padding(start = 14.dp)) {
                            Text(
                                units.windSpeed(snapshot.current.windSpeed),
                                fontSize = 22.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                "${stringResource(windDirectionResource(snapshot.current.windDirection))} · " +
                                    "${snapshot.current.windDirection}°",
                                color = Color.White.copy(alpha = 0.58f),
                            )
                        }
                    }
                    DetailRow(
                        stringResource(R.string.wind_gusts),
                        units.windSpeed(snapshot.current.windGusts),
                    )
                    OptionalDetailRow(
                        stringResource(R.string.daily_wind_speed),
                        today?.let { units.windSpeed(it.windSpeedMax) },
                    )
                    OptionalDetailRow(
                        stringResource(R.string.daily_wind_gusts),
                        today?.windGustsMax?.let(units::windSpeed),
                    )
                    OptionalDetailRow(
                        stringResource(R.string.dominant_wind),
                        today?.dominantWindDirection?.let {
                            "${stringResource(windDirectionResource(it))} · $it°"
                        },
                    )
                }
            }
            item {
                DetailSection(stringResource(R.string.atmosphere)) {
                    OptionalDetailRow(
                        stringResource(R.string.uv_index),
                        snapshot.current.uvIndex?.let { String.format(locale, "%.1f", it) },
                    )
                    OptionalDetailRow(
                        stringResource(R.string.uv_index_max),
                        today?.uvIndexMax?.let { String.format(locale, "%.1f", it) },
                    )
                    OptionalDetailRow(
                        stringResource(R.string.freezing_level),
                        snapshot.current.freezingLevelHeightMeters?.let { units.distance(it / 1_000.0) },
                    )
                    OptionalDetailRow(
                        stringResource(R.string.boundary_layer_height),
                        snapshot.current.boundaryLayerHeightMeters?.let { units.distance(it / 1_000.0) },
                    )
                    OptionalDetailRow(
                        stringResource(R.string.integrated_water_vapour),
                        snapshot.current.integratedWaterVapour?.let {
                            String.format(locale, "%.1f kg/m²", it)
                        },
                    )
                    OptionalDetailRow(
                        stringResource(R.string.lifted_index),
                        snapshot.current.liftedIndex?.let { String.format(locale, "%.1f", it) },
                    )
                    OptionalDetailRow(
                        stringResource(R.string.convective_inhibition),
                        snapshot.current.convectiveInhibition?.let {
                            String.format(locale, "%.0f J/kg", it)
                        },
                    )
                    OptionalDetailRow(
                        stringResource(R.string.cape),
                        snapshot.current.cape?.let { String.format(locale, "%.0f J/kg", it) },
                    )
                    OptionalDetailRow(
                        stringResource(R.string.vapour_pressure_deficit),
                        snapshot.current.vapourPressureDeficit?.let {
                            String.format(locale, "%.1f kPa", it)
                        },
                    )
                }
            }
            item {
                DetailSection(stringResource(R.string.ground)) {
                    OptionalDetailRow(
                        stringResource(R.string.soil_temperature),
                        snapshot.current.soilTemperature0Cm?.let(units::temperature),
                    )
                    OptionalDetailRow(
                        stringResource(R.string.soil_moisture),
                        snapshot.current.soilMoisture0To1Cm?.let {
                            String.format(locale, "%.3f m³/m³", it)
                        },
                    )
                    OptionalDetailRow(
                        stringResource(R.string.surface_temperature),
                        snapshot.current.surfaceTemperature?.let(units::temperature),
                    )
                    OptionalDetailRow(
                        stringResource(R.string.et0_evapotranspiration),
                        today?.et0?.let(units::precipitation),
                    )
                }
            }
            item {
                DetailSection(stringResource(R.string.sun)) {
                    OptionalDetailRow(stringResource(R.string.sunrise), today?.sunrise?.takeLast(5))
                    OptionalDetailRow(stringResource(R.string.sunset), today?.sunset?.takeLast(5))
                    OptionalDetailRow(
                        stringResource(R.string.daylight_duration),
                        durationValue(today?.daylightDurationSeconds),
                    )
                    OptionalDetailRow(
                        stringResource(R.string.sunshine_duration),
                        durationValue(today?.sunshineDurationSeconds),
                    )
                    OptionalDetailRow(
                        stringResource(R.string.shortwave_radiation),
                        today?.shortwaveRadiationSum?.let {
                            String.format(locale, "%.1f MJ/m²", it)
                        },
                    )
                }
            }
            item {
                MoonSection(moon, locale)
            }
        }
    }
    val history = (historyState as? HistoryUiState.Content)?.archive
    if (showRangePicker && history != null) {
        HistoryDateRangeDialog(
            bounds = history.periodRange(HistoryPeriod.ALL),
            initialRange = history.periodRange(historyPeriod, customRange),
            onDismiss = { showRangePicker = false },
            onConfirm = { range ->
                customStart = range.start.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
                customEnd = range.endInclusive.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
                historyPeriod = HistoryPeriod.CUSTOM
                showRangePicker = false
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistoryDateRangeDialog(
    bounds: ClosedRange<LocalDate>,
    initialRange: ClosedRange<LocalDate>,
    onDismiss: () -> Unit,
    onConfirm: (ClosedRange<LocalDate>) -> Unit,
) {
    val start = initialRange.start.coerceIn(bounds.start, bounds.endInclusive)
    val end = initialRange.endInclusive.coerceIn(start, bounds.endInclusive)
    val picker = rememberDateRangePickerState(
        initialSelectedStartDateMillis = start.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        initialSelectedEndDateMillis = end.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        initialDisplayedMonthMillis = end.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        yearRange = bounds.start.year..bounds.endInclusive.year,
        selectableDates = remember(bounds) {
            object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean = historyDateFromUtcMillis(utcTimeMillis) in bounds
            }
        },
    )
    val selected = historyPickerRange(picker.selectedStartDateMillis, picker.selectedEndDateMillis, bounds)
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { selected?.let(onConfirm) }, enabled = selected != null) {
                Text(stringResource(android.R.string.ok))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    ) {
        DateRangePicker(
            state = picker,
            modifier = Modifier.heightIn(max = LocalConfiguration.current.screenHeightDp.dp * 0.72f),
            title = { Text(stringResource(R.string.history_custom_period), Modifier.padding(24.dp)) },
        )
    }
}

internal fun historyDateFromUtcMillis(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()

internal fun historyPickerRange(startMillis: Long?, endMillis: Long?, bounds: ClosedRange<LocalDate>): ClosedRange<LocalDate>? {
    val start = startMillis?.let(::historyDateFromUtcMillis) ?: return null
    val end = endMillis?.let(::historyDateFromUtcMillis) ?: return null
    return (start..end).takeIf { start <= end && start in bounds && end in bounds }
}

@Composable
private fun ForecastCalculationSection(calculation: ForecastCalculation) {
    val contributorText = if (calculation.contributorIds.isEmpty()) {
        stringResource(R.string.unavailable)
    } else {
        calculation.contributorIds.joinToString(", ")
    }
    DetailSection(stringResource(R.string.forecast_calculation_title)) {
        DetailRow(
            stringResource(R.string.forecast_calculation_region),
            stringResource(calculation.region.labelResource()),
        )
        DetailRow(
            stringResource(R.string.forecast_calculation_mode),
            stringResource(calculation.mode.labelResource()),
        )
        DetailRow(
            stringResource(R.string.forecast_calculation_models),
            stringResource(
                R.string.forecast_calculation_model_count,
                calculation.contributorIds.size,
                calculation.requestedModelIds.size,
            ),
        )
        calculation.truthClass?.let { truthClass ->
            DetailRow(
                stringResource(R.string.forecast_calculation_truth),
                stringResource(truthClass.labelResource()),
            )
        }
        calculation.artifactGeneratedAtEpochSeconds?.let { generatedAt ->
            DetailRow(
                stringResource(R.string.forecast_calculation_artifact),
                stringResource(
                    R.string.forecast_calculation_artifact_value,
                    requireNotNull(calculation.artifactVersion),
                    Instant.ofEpochSecond(generatedAt).toString(),
                ),
            )
        }
        if (calculation.weights.isNotEmpty()) {
            DetailRow(
                stringResource(R.string.forecast_calculation_weights),
                calculation.weights.entries.joinToString(", ") { (modelId, weight) ->
                    "$modelId ${(weight * 100).roundToInt()}%"
                },
            )
        }
        Text(
            stringResource(R.string.forecast_calculation_contributors),
            color = Color.White.copy(alpha = 0.62f),
            modifier = Modifier.padding(top = 11.dp),
        )
        Text(
            contributorText,
            fontSize = 12.sp,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp, bottom = 11.dp),
        )
        calculation.fallbackReason?.let { reason ->
            DetailRow(
                stringResource(R.string.forecast_calculation_fallback),
                stringResource(reason.labelResource()),
            )
        }
        Text(
            stringResource(R.string.forecast_calculation_note),
            color = Color.White.copy(alpha = 0.48f),
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 10.dp),
        )
    }
}

private sealed interface HistoryUiState {
    data object Idle : HistoryUiState
    data object Loading : HistoryUiState
    data class Content(val archive: HistoryArchive) : HistoryUiState
    data object Error : HistoryUiState
}

@Composable
private fun HistoryArchiveSection(
    state: HistoryUiState,
    units: WeatherUnitFormatter,
    locale: java.util.Locale,
    showDays: Boolean,
    shareError: Boolean,
    onLoad: () -> Unit,
    onShare: (HistoryArchive) -> Unit,
    onToggleDays: () -> Unit,
    period: HistoryPeriod,
    customRange: ClosedRange<LocalDate>?,
    onPeriod: (HistoryPeriod) -> Unit,
) {
    DetailSection(stringResource(R.string.history_title)) {
        when (state) {
            HistoryUiState.Idle -> {
                Text(
                    stringResource(R.string.history_description),
                    color = Color.White.copy(alpha = 0.62f),
                    modifier = Modifier.padding(vertical = 8.dp),
                )
                Button(
                    onClick = onLoad,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF83D6E8),
                        contentColor = Color(0xFF0D151C),
                    ),
                ) { Text(stringResource(R.string.history_load)) }
            }
            HistoryUiState.Loading -> Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(96.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = Color(0xFF83D6E8), modifier = Modifier.size(32.dp))
            }
            HistoryUiState.Error -> Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.history_unavailable),
                    color = Color.White.copy(alpha = 0.62f),
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onLoad) { Text(stringResource(R.string.retry)) }
            }
            is HistoryUiState.Content -> {
                val archive = state.archive
                val range = remember(archive, period, customRange) { archive.periodRange(period, customRange) }
                val summary = remember(archive, range) { archive.rangeSummary(range) }
                val dateFormatter = remember(locale) {
                    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HistoryPeriod.entries.forEach { option ->
                        FilterChip(
                            selected = period == option,
                            onClick = { onPeriod(option) },
                            label = { Text(stringResource(option.labelResource())) },
                        )
                    }
                }
                Text(
                    stringResource(
                        R.string.history_period,
                        range.start.format(dateFormatter),
                        range.endInclusive.format(dateFormatter),
                    ),
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Text(
                    stringResource(R.string.history_source_note),
                    color = Color.White.copy(alpha = 0.62f),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 5.dp, bottom = 8.dp),
                )
                Text(
                    stringResource(R.string.history_coverage, summary?.dayCount ?: 0, ChronoUnit.DAYS.between(range.start, range.endInclusive) + 1),
                    color = Color.White.copy(alpha = 0.62f),
                    fontSize = 12.sp,
                )
                if (summary == null) {
                    Text(stringResource(R.string.history_no_period_data), modifier = Modifier.padding(vertical = 12.dp))
                } else {
                    Row(Modifier.fillMaxWidth()) {
                        GlanceValue(
                            stringResource(R.string.history_total_precipitation),
                            units.precipitation(summary.totalPrecipitationMm),
                            Modifier.weight(1f),
                        )
                        GlanceValue(
                            stringResource(R.string.history_wet_days),
                            stringResource(R.string.history_wet_days_value, summary.wetDayCount),
                            Modifier.weight(1f),
                        )
                    }
                    HorizontalDivider(color = Color.White.copy(alpha = 0.07f))
                    Row(Modifier.fillMaxWidth()) {
                        GlanceValue(
                            stringResource(R.string.history_average_temperature),
                            units.temperature(summary.averageTemperatureC),
                            Modifier.weight(1f),
                        )
                        GlanceValue(
                            stringResource(R.string.history_temperature_range),
                            "${units.temperature(summary.minimumTemperatureC)} – " +
                                units.temperature(summary.maximumTemperatureC),
                            Modifier.weight(1f),
                        )
                    }
                    OptionalDetailRow(
                        stringResource(R.string.history_solar_energy) + " · " +
                            stringResource(R.string.history_coverage, summary.solarEnergyDayCount, summary.calendarDayCount),
                        summary.totalSolarEnergyMegajoulesPerSquareMeter?.let {
                            String.format(locale, "%.0f MJ/m²", it)
                        },
                    )
                    OptionalDetailRow(
                        stringResource(R.string.humidity) + " · " +
                            stringResource(R.string.history_coverage, summary.humidityDayCount, summary.calendarDayCount),
                        summary.averageRelativeHumidityPercent?.let { String.format(locale, "%.0f %%", it) },
                    )
                    OptionalDetailRow(
                        stringResource(R.string.wind) + " · " +
                            stringResource(R.string.history_coverage, summary.windDayCount, summary.calendarDayCount),
                        summary.averageWindSpeedMetersPerSecond?.let { units.windSpeed(it * 3.6) },
                    )
                }
                Button(
                    onClick = { onShare(archive) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF83D6E8),
                        contentColor = Color(0xFF0D151C),
                    ),
                ) { Text(stringResource(R.string.history_ask_chatgpt)) }
                Text(
                    stringResource(R.string.history_ai_share_note, archive.days.size),
                    color = Color.White.copy(alpha = 0.62f),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 6.dp),
                )
                OutlinedButton(
                    onClick = onToggleDays,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                ) {
                    Text(
                        stringResource(
                            if (showDays) R.string.history_hide_days else R.string.history_show_days,
                        ),
                    )
                }
                if (shareError) {
                    Text(
                        stringResource(R.string.history_share_failed),
                        color = Color(0xFFFFB4AB),
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
    }
}

internal enum class HistoryPeriod(val dayCount: Long?) {
    LAST_30(30), LAST_365(365), ALL(null), CUSTOM(null),
}

internal fun HistoryArchive.periodRange(
    period: HistoryPeriod,
    customRange: ClosedRange<LocalDate>? = null,
): ClosedRange<LocalDate> {
    if (period == HistoryPeriod.CUSTOM && customRange != null) {
        require(customRange.start <= customRange.endInclusive) { "History range starts after its end." }
        return customRange
    }
    val end = days.maxOf(HistoricalDay::date)
    val start = period.dayCount?.let { end.minusDays(it - 1) } ?: days.minOf(HistoricalDay::date)
    return start..end
}

internal fun HistoryArchive.periodDays(period: HistoryPeriod, customRange: ClosedRange<LocalDate>? = null): List<HistoricalDay> {
    val range = periodRange(period, customRange)
    return days.filter { it.date in range }
}

internal fun HistoryArchive.rangeSummary(range: ClosedRange<LocalDate>): HistorySummary? =
    inDateRange(range.start, range.endInclusive)?.summary()
        ?.copy(calendarDayCount = ChronoUnit.DAYS.between(range.start, range.endInclusive) + 1)

private fun HistoryPeriod.labelResource(): Int = when (this) {
    HistoryPeriod.LAST_30 -> R.string.history_last_30
    HistoryPeriod.LAST_365 -> R.string.history_last_365
    HistoryPeriod.ALL -> R.string.history_all_available
    HistoryPeriod.CUSTOM -> R.string.history_custom_period
}

@Composable
private fun HistoricalDayRow(
    day: HistoricalDay,
    units: WeatherUnitFormatter,
    locale: java.util.Locale,
) {
    val dateFormatter = remember(locale) {
        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(day.date.format(dateFormatter), fontWeight = FontWeight.Medium)
            Text(
                "${units.temperature(day.temperatureMinimumC)} – " +
                    units.temperature(day.temperatureMaximumC),
                color = Color.White.copy(alpha = 0.58f),
                fontSize = 12.sp,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(units.precipitation(day.precipitationMm), fontWeight = FontWeight.Medium)
            day.solarEnergyMegajoulesPerSquareMeter?.let {
                Text(
                    String.format(locale, "%.1f MJ/m²", it),
                    color = Color.White.copy(alpha = 0.48f),
                    fontSize = 11.sp,
                )
            }
        }
    }
    HorizontalDivider(color = Color.White.copy(alpha = 0.07f))
}

@Composable
private fun AtAGlanceSection(
    snapshot: WeatherSnapshot,
    today: DailyWeather?,
    units: WeatherUnitFormatter,
    locale: java.util.Locale,
) {
    val nextRain = nextWetHour(snapshot.hourly, snapshot.current.time)?.time?.takeLast(5)
        ?: stringResource(R.string.no_rain_next_24h)
    val maximumProbability = maximumPrecipitationProbability(snapshot.hourly, snapshot.current.time)
        ?.let { "$it %" }
    DetailSection(stringResource(R.string.at_a_glance)) {
        Row(Modifier.fillMaxWidth()) {
            GlanceValue(stringResource(R.string.next_rain), nextRain, Modifier.weight(1f))
            GlanceValue(
                stringResource(R.string.max_precipitation_probability),
                maximumProbability ?: stringResource(R.string.unavailable),
                Modifier.weight(1f),
            )
        }
        HorizontalDivider(color = Color.White.copy(alpha = 0.07f))
        Row(Modifier.fillMaxWidth()) {
            GlanceValue(
                stringResource(R.string.uv_index_max),
                today?.uvIndexMax?.let { String.format(locale, "%.1f", it) }
                    ?: stringResource(R.string.unavailable),
                Modifier.weight(1f),
            )
            GlanceValue(
                stringResource(R.string.freezing_level),
                snapshot.current.freezingLevelHeightMeters?.let { units.distance(it / 1_000.0) }
                    ?: stringResource(R.string.unavailable),
                Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun GlanceValue(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier.padding(vertical = 12.dp, horizontal = 2.dp)) {
        Text(label, color = Color.White.copy(alpha = 0.55f), fontSize = 11.sp)
        Text(
            value,
            fontWeight = FontWeight.SemiBold,
            fontSize = 16.sp,
            modifier = Modifier.padding(top = 3.dp),
        )
    }
}

internal fun nextWetHour(hourly: List<HourlyWeather>, currentTime: String): HourlyWeather? =
    detailUpcomingHours(hourly, currentTime).firstOrNull { hour ->
        hour.precipitation >= 0.1 || hour.precipitationProbability >= 50
    }

internal fun maximumPrecipitationProbability(hourly: List<HourlyWeather>, currentTime: String): Int? =
    detailUpcomingHours(hourly, currentTime).maxOfOrNull(HourlyWeather::precipitationProbability)

private fun detailUpcomingHours(hourly: List<HourlyWeather>, currentTime: String): List<HourlyWeather> {
    val currentHour = currentTime.take(13)
    return hourly.asSequence()
        .dropWhile { it.time.take(13) < currentHour }
        .take(24)
        .toList()
}

@Composable
private fun MoonSection(moon: MoonDetails?, locale: java.util.Locale) {
    DetailSection(stringResource(R.string.moon)) {
        if (moon == null) {
            Text(
                stringResource(R.string.unavailable),
                color = Color.White.copy(alpha = 0.58f),
                modifier = Modifier.padding(vertical = 12.dp),
            )
            return@DetailSection
        }
        val moonDescription = stringResource(moon.phase.labelResource())
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MoonPhaseCanvas(
                moon,
                Modifier
                    .size(146.dp)
                    .semantics { contentDescription = moonDescription },
            )
            Column(Modifier.padding(start = 18.dp)) {
                Text(moonDescription, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    stringResource(
                        R.string.moon_illumination_value,
                        (moon.illuminatedFraction * 100).roundToInt(),
                    ),
                    color = Color.White.copy(alpha = 0.68f),
                )
                Text(
                    stringResource(if (moon.waxing) R.string.moon_waxing else R.string.moon_waning),
                    color = Color.White.copy(alpha = 0.68f),
                )
            }
        }
        OptionalDetailRow(stringResource(R.string.moonrise), moon.rise?.format(TIME_FORMATTER))
        OptionalDetailRow(stringResource(R.string.moonset), moon.set?.format(TIME_FORMATTER))
        DetailRow(stringResource(R.string.altitude), "%.1f°".format(locale, moon.altitudeDegrees))
        DetailRow(stringResource(R.string.azimuth), "%.1f°".format(locale, moon.azimuthDegrees))
        DetailRow(stringResource(R.string.next_new_moon), moon.nextNewMoon.formatFor(locale))
        DetailRow(stringResource(R.string.next_full_moon), moon.nextFullMoon.formatFor(locale))
        Text(
            stringResource(R.string.moon_accuracy_note),
            color = Color.White.copy(alpha = 0.48f),
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 10.dp),
        )
    }
}

@Composable
private fun DetailSection(title: String, content: @Composable () -> Unit) {
    Column {
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        content()
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Color.White.copy(alpha = 0.62f), modifier = Modifier.weight(1f))
        Text(value, fontWeight = FontWeight.Medium)
    }
    HorizontalDivider(color = Color.White.copy(alpha = 0.07f))
}

@Composable
private fun OptionalDetailRow(label: String, value: String?) {
    DetailRow(label, value ?: stringResource(R.string.unavailable))
}

@Composable
private fun durationValue(seconds: Double?): String? = seconds?.roundToInt()?.div(60)?.let {
    stringResource(R.string.duration_hours_minutes, it / 60, it % 60)
}

private fun ZonedDateTime.formatFor(locale: java.util.Locale): String = format(
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale),
)

@StringRes
private fun ForecastRegion.labelResource(): Int = when (this) {
    ForecastRegion.CZECHIA -> R.string.forecast_region_czechia
    ForecastRegion.EUROPE -> R.string.forecast_region_europe
    ForecastRegion.NORTH_AMERICA -> R.string.forecast_region_north_america
    ForecastRegion.SOUTH_AMERICA -> R.string.forecast_region_south_america
    ForecastRegion.AFRICA -> R.string.forecast_region_africa
    ForecastRegion.SOUTH_CENTRAL_ASIA -> R.string.forecast_region_south_central_asia
    ForecastRegion.EAST_ASIA -> R.string.forecast_region_east_asia
    ForecastRegion.NORTHERN_ASIA -> R.string.forecast_region_northern_asia
    ForecastRegion.OCEANIA -> R.string.forecast_region_oceania
    ForecastRegion.GLOBAL -> R.string.forecast_region_global
}

@StringRes
private fun ForecastCalculationMode.labelResource(): Int = when (this) {
    ForecastCalculationMode.CALIBRATED -> R.string.forecast_mode_calibrated
    ForecastCalculationMode.DIAGNOSTIC_MEDIAN -> R.string.forecast_mode_diagnostic_median
    ForecastCalculationMode.BEST_MATCH -> R.string.forecast_mode_best_match
}

@StringRes
private fun CalibrationTruthClass.labelResource(): Int = when (this) {
    CalibrationTruthClass.STATION -> R.string.forecast_truth_station
    CalibrationTruthClass.RADAR_GAUGE -> R.string.forecast_truth_radar_gauge
    CalibrationTruthClass.SATELLITE_PRECIPITATION -> R.string.forecast_truth_satellite
    CalibrationTruthClass.REANALYSIS -> R.string.forecast_truth_reanalysis
}

@StringRes
private fun ForecastFallbackReason.labelResource(): Int = when (this) {
    ForecastFallbackReason.INSUFFICIENT_CONTRIBUTORS -> R.string.forecast_fallback_insufficient
    ForecastFallbackReason.PROVIDER_UNAVAILABLE -> R.string.forecast_fallback_provider
}

@StringRes
internal fun MoonPhaseKey.labelResource(): Int = when (this) {
    MoonPhaseKey.NEW_MOON -> R.string.moon_phase_new
    MoonPhaseKey.WAXING_CRESCENT -> R.string.moon_phase_waxing_crescent
    MoonPhaseKey.FIRST_QUARTER -> R.string.moon_phase_first_quarter
    MoonPhaseKey.WAXING_GIBBOUS -> R.string.moon_phase_waxing_gibbous
    MoonPhaseKey.FULL_MOON -> R.string.moon_phase_full
    MoonPhaseKey.WANING_GIBBOUS -> R.string.moon_phase_waning_gibbous
    MoonPhaseKey.LAST_QUARTER -> R.string.moon_phase_last_quarter
    MoonPhaseKey.WANING_CRESCENT -> R.string.moon_phase_waning_crescent
}

private val TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm")
