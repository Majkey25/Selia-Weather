package cz.majkey.pocasicesko.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Air
import androidx.compose.material.icons.rounded.Compress
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Opacity
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import cz.majkey.pocasicesko.R
import cz.majkey.pocasicesko.data.CzechLocation
import cz.majkey.pocasicesko.data.CurrentWeather
import cz.majkey.pocasicesko.data.DailyWeather
import cz.majkey.pocasicesko.data.HistoryArchive
import cz.majkey.pocasicesko.data.HourlyWeather
import cz.majkey.pocasicesko.data.WeatherKind
import cz.majkey.pocasicesko.data.WeatherSnapshot
import cz.majkey.pocasicesko.data.WeatherWarningsResult
import cz.majkey.pocasicesko.data.ForecastConfidence
import cz.majkey.pocasicesko.data.ConfidenceLevel
import cz.majkey.pocasicesko.data.ConfidenceReason
import cz.majkey.pocasicesko.data.dailyForecastConfidence
import cz.majkey.pocasicesko.data.hourlyForecastConfidence
import cz.majkey.pocasicesko.data.conditionFor
import cz.majkey.pocasicesko.units.MeasurementSystem
import cz.majkey.pocasicesko.units.WeatherUnitFormatter
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.roundToInt

private const val HOURLY_OUTLOOK_COUNT = 24

@Composable
internal fun rememberForecastLocalTime(timezone: String, utcOffsetSeconds: Int?): LocalDateTime? {
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val active = lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
    val instant by produceState(initialValue = Instant.now(), key1 = timezone, key2 = utcOffsetSeconds, key3 = active) {
        while (active && isActive) {
            val now = Instant.now()
            value = now
            delay(60_000L - Math.floorMod(now.toEpochMilli(), 60_000L))
        }
    }
    return forecastLocalTime(instant, timezone, utcOffsetSeconds)
}

internal fun forecastLocalTime(instant: Instant, timezone: String, utcOffsetSeconds: Int?): LocalDateTime? {
    val offset = utcOffsetSeconds?.takeIf { it in -64_800..64_800 }?.let(ZoneOffset::ofTotalSeconds)
    val zone = offset ?: runCatching { ZoneId.of(timezone) }.getOrNull() ?: return null
    return LocalDateTime.ofInstant(instant, zone)
}

internal fun isCurrentForecastHour(time: String, localNow: LocalDateTime?): Boolean =
    localNow != null && runCatching { LocalDateTime.parse(time).truncatedTo(ChronoUnit.HOURS) }.getOrNull() ==
        localNow.truncatedTo(ChronoUnit.HOURS)

internal fun forecastStartIndex(days: List<DailyWeather>, currentDate: String?): Int =
    if (currentDate == null) 0 else days.indexOfFirst { it.date >= currentDate }.coerceAtLeast(0)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ForecastScreen(
    snapshot: WeatherSnapshot,
    location: CzechLocation,
    padding: PaddingValues,
    fromCache: Boolean,
    refreshing: Boolean,
    refreshError: String?,
    measurementSystem: MeasurementSystem,
    loadHistory: suspend (CzechLocation) -> HistoryArchive,
    onSearch: () -> Unit,
    onRefresh: () -> Unit,
    onSettings: () -> Unit,
    warnings: WeatherWarningsResult?,
    onWarnings: () -> Unit,
) {
    val condition = conditionFor(snapshot.current.weatherCode, snapshot.current.isDay)
    val accent = weatherAccent(condition.kind, snapshot.current.isDay)
    val locale = LocalConfiguration.current.locales[0]
    val units = remember(measurementSystem, locale) { WeatherUnitFormatter(measurementSystem, locale) }
    val localNow = rememberForecastLocalTime(snapshot.timezone, snapshot.utcOffsetSeconds)
    val currentDate = localNow?.toLocalDate()?.toString()
    val currentDayIndex = forecastStartIndex(snapshot.daily, currentDate)
    val precipitationByStart = remember(snapshot.hourly) { hourlyPrecipitationByStart(snapshot.hourly) }
    val fetchedAge = System.currentTimeMillis() - snapshot.updatedAtEpochMillis
    val dailyConfidence = remember(snapshot, localNow) {
        snapshot.daily.associate { it.date to dailyForecastConfidence(it.date, snapshot.hourly, precipitationByStart, localNow, fetchedAge,
            snapshot.current, snapshot.calculation?.currentSkyModelId) }
    }
    var selectedDayIndex by rememberSaveable(location.latitude, location.longitude) { mutableStateOf<Int?>(null) }
    var selectedHourTime by rememberSaveable(location.latitude, location.longitude) { mutableStateOf<String?>(null) }
    var showDetails by rememberSaveable(location.latitude, location.longitude) { mutableStateOf(false) }
    var openHistory by rememberSaveable(location.latitude, location.longitude) { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(start = 20.dp, top = 8.dp, end = 20.dp, bottom = 100.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            item {
                LocationHeader(
                    location = location,
                    refreshing = refreshing,
                    fromCache = fromCache || refreshError != null,
                    updatedAt = snapshot.updatedAtEpochMillis,
                    onSearch = onSearch,
                    onRefresh = onRefresh,
                    onSettings = onSettings,
                )
            }
            item {
                WeatherHero(snapshot = snapshot, accent = accent, units = units, localNow = localNow)
            }
            item {
                HourlyGraphPanel(snapshot = snapshot, units = units, localNow = localNow,
                    onHourClick = { time ->
                        snapshot.daily.indexOfFirst { it.date == time.take(10) }.takeIf { it >= 0 }?.let {
                            selectedHourTime = time
                            selectedDayIndex = it
                        }
                    })
            }
            item {
                CurrentMetrics(snapshot = snapshot, units = units, localNow = localNow)
            }
            item {
                DailyForecastPanel(
                    days = snapshot.daily.drop(currentDayIndex),
                    units = units,
                    currentDate = currentDate,
                    onDayClick = { selectedHourTime = null; selectedDayIndex = currentDayIndex + it },
                    confidenceByDay = dailyConfidence,
                )
            }
            item {
                WeatherPanel {
                    Column {
                        WeatherDetailAction {
                            openHistory = false
                            showDetails = true
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        WarningsAction(warnings, onWarnings)
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        WeatherDetailAction(label = R.string.history_title) {
                            openHistory = true
                            showDetails = true
                        }
                    }
                }
            }
        }
    }
    selectedDayIndex?.let { initialPage ->
        DayDetailSheet(
            days = snapshot.daily,
            hourly = snapshot.hourly,
            initialPage = initialPage,
            localNow = localNow,
            units = units,
            onDismiss = { selectedDayIndex = null },
            updatedAtEpochMillis = snapshot.updatedAtEpochMillis,
            initialHourTime = selectedHourTime,
            current = snapshot.current,
            currentSkyModelId = snapshot.calculation?.currentSkyModelId,
        )
    }
    if (showDetails) {
        WeatherDetailSheet(
            snapshot = snapshot,
            location = location,
            units = units,
            loadHistory = loadHistory,
            initialHistory = openHistory,
            currentTime = localNow,
            onDismiss = { showDetails = false },
        )
    }
}

@Composable
private fun WeatherDetailAction(label: Int = R.string.open_weather_details, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        color = Color.Transparent,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(if (label == R.string.history_title) Icons.Rounded.History else Icons.Rounded.Info,
                contentDescription = null, modifier = Modifier.padding(end = 12.dp).size(22.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                stringResource(label),
                modifier = Modifier.weight(1f),
                fontWeight = FontWeight.Medium,
            )
            Icon(
                Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LocationHeader(
    location: CzechLocation,
    refreshing: Boolean,
    fromCache: Boolean,
    updatedAt: Long,
    onSearch: () -> Unit,
    onRefresh: () -> Unit,
    onSettings: () -> Unit,
) {
    val locale = LocalConfiguration.current.locales[0]
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = onSettings,
                modifier = Modifier.size(48.dp),
            ) {
                Icon(Icons.Rounded.Settings, contentDescription = stringResource(R.string.open_settings))
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Surface(
                    onClick = onSearch,
                    modifier = Modifier.widthIn(max = 240.dp).heightIn(min = 48.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.45f),
                    shape = RoundedCornerShape(24.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 11.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = location.name,
                            modifier = Modifier.weight(1f, fill = false),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.width(6.dp))
                        Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = null, modifier = Modifier.size(20.dp))
                    }
                }
            }
            IconButton(
                onClick = onRefresh,
                modifier = Modifier.size(48.dp),
            ) {
                if (refreshing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = MaterialTheme.colorScheme.primary,
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(Icons.Rounded.Refresh, contentDescription = stringResource(R.string.refresh_forecast))
                }
            }
        }
        Text(
            text = location.localizedRegion(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 2.dp),
        )
        if (fromCache) {
            Text(
                text = stringResource(R.string.cached_data, formatUpdatedAt(updatedAt, locale)),
                color = MaterialTheme.colorScheme.primary,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
internal fun WeatherHero(snapshot: WeatherSnapshot, accent: Color, units: WeatherUnitFormatter, localNow: LocalDateTime?) {
    val condition = conditionFor(snapshot.current.weatherCode, snapshot.current.isDay)
    val conditionLabel = stringResource(condition.labelResource())
    val today = snapshot.daily.firstOrNull { it.date == localNow?.toLocalDate()?.toString() }
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        WeatherIcon(
            kind = condition.kind,
            conditionKey = condition.key,
            isDay = snapshot.current.isDay,
            contentDescription = null,
            modifier = Modifier.size(40.dp),
            tint = accent,
        )
        BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            val temperature = units.temperature(snapshot.current.temperature)
            val style = MaterialTheme.typography.displayLarge.copy(fontSize = 88.sp,
                lineHeight = 96.sp, fontWeight = FontWeight.Light, letterSpacing = (-2).sp)
            val measuredWidth = rememberTextMeasurer().measure(temperature, style, softWrap = false).size.width
            val scale = (with(LocalDensity.current) { maxWidth.toPx() } / measuredWidth.coerceAtLeast(1)).coerceAtMost(1f)
            Text(temperature, style = style.copy(fontSize = style.fontSize * scale, lineHeight = style.lineHeight * scale),
                maxLines = 1)
        }
        Text(conditionLabel, fontSize = 18.sp, fontWeight = FontWeight.Normal, textAlign = TextAlign.Center)
        if (!isCurrentForecastHour(snapshot.current.time, localNow)) {
            Text(snapshot.current.time.replace('T', ' '), fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center)
        }
        Text(
            text = (today?.let { "${units.temperature(it.temperatureMin)} / ${units.temperature(it.temperatureMax)}" }
                ?: stringResource(R.string.unavailable)) + "  ·  " +
                stringResource(R.string.feels_like_temperature, units.temperature(snapshot.current.feelsLike)),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
internal fun HourlyGraphPanel(snapshot: WeatherSnapshot, units: WeatherUnitFormatter, localNow: LocalDateTime?, onHourClick: (String) -> Unit) {
    val locale = LocalConfiguration.current.locales[0]
    val precipitationByStart = remember(snapshot.hourly) { hourlyPrecipitationByStart(snapshot.hourly) }
    val currentHour = localNow?.toString()?.take(13) ?: snapshot.current.time.take(13)
    val hours = upcomingHours(snapshot.hourly, currentHour)
    val confidenceByTime = remember(snapshot, localNow) {
        val age = System.currentTimeMillis() - snapshot.updatedAtEpochMillis
        hours.associate { hour ->
            val shownCurrent = snapshot.current.takeIf {
                isCurrentForecastHour(hour.time, localNow) && isCurrentForecastHour(it.time, localNow)
            }
            hour.time to hourlyForecastConfidence(hour, precipitationByStart[hour.time], localNow, age,
                hourlyDisplayCondition(hour, shownCurrent, localNow).first.kind,
                if (shownCurrent != null) snapshot.calculation?.currentSkyModelId else hour.skySourceModelId,
                shownConditionTime = shownCurrent?.time ?: hour.time)
        }
    }
    val scrollState = rememberScrollState()
    val itemWidth = maxOf(68.dp, 56.dp * LocalDensity.current.fontScale)
    Column {
        SectionTitle(stringResource(R.string.next_hours, HOURLY_OUTLOOK_COUNT))
        Spacer(Modifier.height(12.dp))
        if (hours.isEmpty()) {
            Text(stringResource(R.string.unavailable), color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            WeatherPanel {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(scrollState),
                ) {
                    Box(
                        modifier = Modifier
                            .width(itemWidth * hours.size)
                            .padding(vertical = 12.dp),
                    ) {
                        Column(Modifier.clearAndSetSemantics { }) {
                            Row {
                                hours.forEach { hour ->
                                    Text(
                                        text = if (isCurrentForecastHour(hour.time, localNow)) {
                                            stringResource(R.string.now)
                                        } else {
                                            hour.time.substringAfter('T').take(5)
                                        },
                                        modifier = Modifier.width(itemWidth),
                                        color = if (isCurrentForecastHour(hour.time, localNow)) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 12.sp,
                                        textAlign = TextAlign.Center,
                                    )
                                }
                            }
                            Spacer(Modifier.height(9.dp))
                            Row {
                                hours.forEach { hour ->
                                    val (condition, iconIsDay) = hourlyDisplayCondition(hour, snapshot.current, localNow)
                                    Box(Modifier.width(itemWidth), contentAlignment = Alignment.Center) {
                                        WeatherIcon(
                                            kind = condition.kind,
                                            conditionKey = condition.key,
                                            isDay = iconIsDay,
                                            contentDescription = null,
                                            modifier = Modifier.size(25.dp),
                                            tint = weatherAccent(condition.kind, iconIsDay),
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.height(5.dp))
                            HourlyMeteogram(
                                hours = hours,
                                precipitationHours = hours.map { precipitationByStart[it.time] },
                                columnWidth = itemWidth,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(56.dp),
                            )
                            Row {
                                hours.forEach { hour ->
                                    Text(
                                        text = units.temperature(hour.temperature),
                                        modifier = Modifier.width(itemWidth),
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Medium,
                                        textAlign = TextAlign.Center,
                                    )
                                }
                            }
                            Spacer(Modifier.height(5.dp))
                            Row {
                                hours.forEach { hour ->
                                    HourPrecipitationColumn(precipitationByStart[hour.time], units, Modifier.width(itemWidth))
                                }
                            }
                            Row {
                                hours.forEach { hour ->
                                    Row(
                                        modifier = Modifier.width(itemWidth),
                                        horizontalArrangement = Arrangement.Center,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Navigation,
                                            contentDescription = null,
                                            modifier = Modifier
                                                .size(12.dp)
                                                .graphicsLayer { rotationZ = windArrowRotation(hour.windDirection) },
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        Spacer(Modifier.width(2.dp))
                                        Text(
                                            text = units.windSpeed(hour.windSpeed),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            fontSize = 10.sp,
                                            maxLines = 1,
                                        )
                                    }
                                }
                            }
                            Row {
                                hours.forEach { hour ->
                                    Box(Modifier.width(itemWidth), contentAlignment = Alignment.Center) {
                                        ConfidenceRating(confidenceByTime.getValue(hour.time).level, text = "")
                                    }
                                }
                            }
                        }
                        Row(Modifier.matchParentSize()) {
                            hours.forEach { hour ->
                                val (condition, _) = hourlyDisplayCondition(hour, snapshot.current, localNow)
                                val time = if (isCurrentForecastHour(hour.time, localNow)) {
                                    stringResource(R.string.now)
                                } else {
                                    hour.time.substringAfter('T').take(5)
                                }
                                val precipitationHour = precipitationByStart[hour.time]
                                val interval = hourlyStartingPrecipitationInterval(hour.time, locale)
                                val precipitationLabel = "${stringResource(R.string.precipitation)} " +
                                    (precipitationHour?.let {
                                        val chance = it.precipitationProbability?.let { chance -> "$chance%" }
                                            ?: "${stringResource(R.string.precipitation_probability)} ${stringResource(R.string.unavailable)}"
                                        "$chance, ${units.precipitation(it.precipitation)}"
                                    }
                                        ?: stringResource(R.string.unavailable)) +
                                    (interval?.let { ", " + stringResource(R.string.precipitation_interval, it) } ?: "")
                                val windLabel = "${stringResource(R.string.wind)} ${units.windSpeed(hour.windSpeed)}, " +
                                    stringResource(windDirectionResource(hour.windDirection))
                                val confidence = confidenceByTime.getValue(hour.time)
                                val confidenceText = stringResource(R.string.confidence_label, stringResource(confidence.level.labelResource()))
                                val description = hourlyAccessibilityDescription(
                                    time = time,
                                    condition = stringResource(condition.labelResource()),
                                    temperature = units.temperature(hour.temperature) + ", " +
                                        stringResource(R.string.feels_like_temperature,
                                            hour.apparentTemperature?.takeIf(Double::isFinite)?.let(units::temperature)
                                                ?: stringResource(R.string.unavailable)),
                                    precipitationLabel = precipitationLabel,
                                    windLabel = windLabel,
                                ) + ", " + confidenceText
                                Box(
                                    modifier = Modifier
                                        .width(itemWidth)
                                        .fillMaxHeight()
                                        .clickable { onHourClick(hour.time) }
                                        .semantics { contentDescription = description },
                                )
                            }
                        }
                    }
                }
            }
            Text(stringResource(R.string.confidence_hour_hint), fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
internal fun HourPrecipitationColumn(precipitationHour: HourlyWeather?, units: WeatherUnitFormatter, modifier: Modifier = Modifier) {
    Column(modifier.heightIn(min = 42.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = precipitationHour?.precipitationProbability?.let { "$it %" } ?: "—",
            color = MaterialTheme.colorScheme.secondary, fontSize = 11.sp, lineHeight = 15.sp, textAlign = TextAlign.Center,
        )
        if (precipitationHour != null && precipitationHour.precipitation > 0.0) {
            Text(
                text = units.precipitation(precipitationHour.precipitation),
                color = MaterialTheme.colorScheme.secondary, fontSize = 10.sp, lineHeight = 14.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}

private data class CurrentMetric(val icon: ImageVector, val label: String, val value: String, val note: String)

@Composable
internal fun CurrentMetrics(snapshot: WeatherSnapshot, units: WeatherUnitFormatter, localNow: LocalDateTime?) {
    val today = snapshot.daily.firstOrNull { it.date == localNow?.toLocalDate()?.toString() }
    val metrics = listOf(
        CurrentMetric(
            Icons.Rounded.WaterDrop,
            stringResource(R.string.precipitation),
            units.precipitation(snapshot.current.precipitation),
            if (isCurrentForecastHour(snapshot.current.time, localNow)) stringResource(R.string.now)
                else snapshot.current.time.replace('T', ' '),
        ),
        CurrentMetric(
            Icons.Rounded.Air,
            stringResource(R.string.wind),
            units.windSpeed(snapshot.current.windSpeed),
            stringResource(windDirectionResource(snapshot.current.windDirection)),
        ),
        CurrentMetric(Icons.Rounded.Opacity, stringResource(R.string.humidity), "${snapshot.current.humidity} %",
            stringResource(R.string.relative)),
        CurrentMetric(Icons.Rounded.Compress, stringResource(R.string.pressure), units.pressure(snapshot.current.pressure),
            stringResource(R.string.sea_level)),
        CurrentMetric(Icons.Rounded.WbSunny, stringResource(R.string.sunrise), today?.sunrise?.takeLast(5)
            ?: stringResource(R.string.unavailable), stringResource(R.string.today)),
        CurrentMetric(Icons.Rounded.WbSunny, stringResource(R.string.sunset), today?.sunset?.takeLast(5)
            ?: stringResource(R.string.unavailable), stringResource(R.string.today)),
    )
    Column {
        SectionTitle(stringResource(R.string.current_details))
        Spacer(Modifier.height(16.dp))
        WeatherPanel {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                metrics.chunked(2).forEach { pair ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        pair.forEach { metric ->
                            Column(Modifier.weight(1f).semantics(mergeDescendants = true) { },
                                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(metric.icon, contentDescription = null, modifier = Modifier.size(18.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Spacer(Modifier.width(8.dp))
                                    Text(metric.label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                                }
                                Text(metric.value, fontSize = 20.sp, fontWeight = FontWeight.Medium)
                                Text(metric.note, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun DailyForecastPanel(
    days: List<DailyWeather>,
    units: WeatherUnitFormatter,
    currentDate: String?,
    onDayClick: (Int) -> Unit,
    confidenceByDay: Map<String, ForecastConfidence> = emptyMap(),
) {
    var expanded by rememberSaveable(days.firstOrNull()?.date) { mutableStateOf(false) }
    val availableDays = days.take(14)
    val shownDays = if (expanded) availableDays else availableDays.take(4)
    val expansionState = stringResource(if (expanded) R.string.hour_expanded else R.string.hour_collapsed)
    Column {
        SectionTitle(stringResource(R.string.days_14))
        Spacer(Modifier.height(12.dp))
        WeatherPanel {
            Column {
                shownDays.forEachIndexed { index, day ->
                    DailyRow(
                        day = day,
                        today = day.date == currentDate,
                        units = units,
                        onClick = { onDayClick(index) },
                        confidence = confidenceByDay[day.date],
                    )
                    if (index != shownDays.lastIndex) {
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                    }
                }
                if (availableDays.size > 4) {
                    TextButton(
                        onClick = { expanded = !expanded },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .semantics { stateDescription = expansionState },
                    ) {
                        Text(if (expanded) stringResource(R.string.forecast_collapse)
                            else stringResource(R.string.forecast_expand, availableDays.size))
                        Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, contentDescription = null)
                    }
                }
            }
        }
    }
}

@Composable
internal fun DailyRow(
    day: DailyWeather,
    today: Boolean,
    units: WeatherUnitFormatter,
    onClick: () -> Unit,
    confidence: ForecastConfidence? = null,
) {
    val condition = conditionFor(day.displayWeatherCode)
    val conditionLabel = stringResource(condition.labelResource())
    val hazardLabel = if (conditionFor(day.weatherCode).kind == WeatherKind.STORM && condition.kind != WeatherKind.STORM)
        " · " + stringResource(conditionFor(day.weatherCode).labelResource()) else ""
    val locale = LocalConfiguration.current.locales[0]
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 78.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = if (today) stringResource(R.string.today) else formatDay(day.date, locale),
                    fontSize = 14.sp,
                    fontWeight = if (today) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (today) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = LocalDate.parse(day.date).format(DateTimeFormatter.ofPattern("d MMM", locale)),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                )
            }
            WeatherIcon(
                kind = condition.kind,
                conditionKey = condition.key,
                isDay = true,
                contentDescription = conditionLabel,
                modifier = Modifier.size(27.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = conditionLabel,
                modifier = Modifier.weight(1.5f).padding(horizontal = 8.dp),
                fontSize = 14.sp,
                lineHeight = 20.sp,
            )
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = units.temperature(day.temperatureMin),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 14.sp,
                )
                Text(
                    text = units.temperature(day.temperatureMax),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Icon(
                Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = stringResource(R.string.open_hourly_detail),
                modifier = Modifier
                    .padding(start = 5.dp)
                    .size(17.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Rounded.WaterDrop,
                contentDescription = stringResource(R.string.precipitation),
                modifier = Modifier.size(12.dp),
                tint = MaterialTheme.colorScheme.secondary,
            )
            Text(
                text = " ${dailyPrecipitationSummary(day, units)} · " + units.windSpeed(day.windSpeedMax) + hazardLabel,
                color = MaterialTheme.colorScheme.secondary,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "${stringResource(R.string.feels_like)} " +
                    "${units.temperature(day.apparentTemperatureMin ?: day.temperatureMin)} / " +
                    units.temperature(day.apparentTemperatureMax ?: day.temperatureMax),
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
            val rating = confidence ?: ForecastConfidence(ConfidenceLevel.LIMITED, ConfidenceReason.MISSING_MODELS)
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                ConfidenceRating(rating.level, text = "")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DayDetailSheet(
    days: List<DailyWeather>,
    hourly: List<HourlyWeather>,
    initialPage: Int,
    localNow: LocalDateTime?,
    units: WeatherUnitFormatter,
    onDismiss: () -> Unit,
    updatedAtEpochMillis: Long = System.currentTimeMillis(),
    initialHourTime: String? = null,
    current: CurrentWeather? = null,
    currentSkyModelId: String? = null,
) {
    if (days.isEmpty()) return
    val latestLocalNow = rememberUpdatedState(localNow)
    val precipitationByStart = remember(hourly) { hourlyPrecipitationByStart(hourly) }
    val locale = LocalConfiguration.current.locales[0]
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val pagerState = rememberPagerState(
        initialPage = initialPage.coerceIn(days.indices),
        pageCount = { days.size },
    )
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        sheetState = sheetState,
    ) {
        Column(Modifier.fillMaxWidth().fillMaxHeight()) {
            SheetHeader(
                title = dayForPage(days, pagerState.currentPage)?.let { formatFullDay(it.date, locale) }
                    ?: stringResource(R.string.whole_day_hours),
                onBack = onDismiss,
                subtitle = if (localNow != null && dayForPage(days, pagerState.currentPage)?.date == localNow.toLocalDate().toString()) stringResource(R.string.today) else null,
            )
            HorizontalPager(
                state = pagerState,
                reverseLayout = false,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) { page ->
                val day = dayForPage(days, page) ?: return@HorizontalPager
                val hours = hourlyForDay(hourly, day.date)
                val initialHour = initialHourTime?.takeIf { it.take(10) == day.date }
                var expandedHourTime by rememberSaveable(day.date) { mutableStateOf(initialHour) }
                val hourIndex = hours.indexOfFirst { it.time == initialHour }
                val listState = rememberLazyListState(initialFirstVisibleItemIndex = if (hourIndex >= 0) hourIndex + 1 else 0)
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight()
                        .navigationBarsPadding(),
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 34.dp),
                ) {
                    item {
                        val dayNow = latestLocalNow.value
                        Text(
                            "${stringResource(R.string.whole_day_hours)} · ${hours.size} · " +
                                stringResource(
                                    if (dayNow != null && day.date < dayNow.toLocalDate().toString()) {
                                        R.string.historical_forecast
                                    } else {
                                        R.string.forecast
                                    },
                                ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(top = 3.dp),
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 16.dp, bottom = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            DaySummaryMetric(
                                label = stringResource(R.string.temperature),
                                value = "${units.temperature(day.temperatureMin)} / ${units.temperature(day.temperatureMax)}",
                                modifier = Modifier.weight(1f),
                            )
                            DaySummaryMetric(
                                label = stringResource(R.string.precipitation),
                                value = dailyPrecipitationSummary(day, units),
                                modifier = Modifier.weight(1f),
                            )
                            DaySummaryMetric(
                                label = stringResource(R.string.wind),
                                value = units.windSpeed(day.windSpeedMax),
                                modifier = Modifier.weight(1f),
                            )
                        }
                        Text(
                            text = "${stringResource(R.string.feels_like)} · " +
                                "${units.temperature(day.apparentTemperatureMin ?: day.temperatureMin)} / " +
                                units.temperature(day.apparentTemperatureMax ?: day.temperatureMax),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(bottom = 12.dp),
                        )
                        DayAvailableDetails(day, units, locale)
                        ForecastConfidenceBadge(dailyForecastConfidence(day.date, hourly, precipitationByStart, dayNow,
                            System.currentTimeMillis() - updatedAtEpochMillis, current, currentSkyModelId), units)
                    }
                    itemsIndexed(hours, key = { index, hour -> "${hour.time}-$index" }) { _, hour ->
                        val hourNow = latestLocalNow.value
                        val precipitationHour = precipitationByStart[hour.time]
                        val currentHour = isCurrentForecastHour(hour.time, hourNow)
                        val shownCurrent = current?.takeIf { currentHour && isCurrentForecastHour(it.time, hourNow) }
                        val (condition, iconIsDay) = hourlyDisplayCondition(hour, shownCurrent, hourNow)
                        val conditionLabel = stringResource(condition.labelResource())
                        val hourConfidence = hourlyForecastConfidence(hour, precipitationHour, hourNow,
                            System.currentTimeMillis() - updatedAtEpochMillis, condition.kind,
                            if (shownCurrent != null) currentSkyModelId else hour.skySourceModelId,
                            shownConditionTime = shownCurrent?.time ?: hour.time)
                        val expanded = expandedHourTime == hour.time
                        val expansionState = stringResource(
                            if (expanded) R.string.hour_expanded else R.string.hour_collapsed,
                        )
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 78.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (currentHour) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f) else Color.Transparent)
                                .border(1.dp, if (currentHour) MaterialTheme.colorScheme.primary.copy(alpha = 0.6f) else Color.Transparent,
                                    RoundedCornerShape(12.dp))
                                .padding(horizontal = 12.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Column(
                                Modifier.fillMaxWidth().heightIn(min = 56.dp)
                                    .clickable { expandedHourTime = toggleExpandedHour(expandedHourTime, hour.time) }
                                    .semantics { stateDescription = expansionState },
                            ) {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.widthIn(min = 55.dp).padding(end = 8.dp)) {
                                        Text(hour.time.substringAfter('T').take(5), fontWeight = FontWeight.SemiBold)
                                        if (currentHour) {
                                            Text(stringResource(R.string.now), color = MaterialTheme.colorScheme.primary,
                                                fontSize = 10.sp, lineHeight = 14.sp)
                                        }
                                    }
                                    WeatherIcon(
                                        kind = condition.kind,
                                        conditionKey = condition.key,
                                        isDay = iconIsDay,
                                        contentDescription = conditionLabel,
                                        modifier = Modifier.size(26.dp),
                                        tint = weatherAccent(condition.kind, iconIsDay),
                                    )
                                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                        Text(conditionLabel, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        ConfidenceRating(hourConfidence.level)
                                    }
                                    Text(
                                        units.temperature(hour.temperature),
                                        fontSize = 17.sp,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    Icon(
                                        imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                                        contentDescription = null,
                                        modifier = Modifier
                                            .padding(start = 4.dp)
                                            .size(20.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Text(
                                    "${stringResource(R.string.feels_like)} " +
                                        "${hour.apparentTemperature?.takeIf(Double::isFinite)?.let(units::temperature) ?: stringResource(R.string.unavailable)} · " +
                                        (precipitationHour?.let {
                                            "${it.precipitationProbability?.let { chance -> "$chance%" } ?: "—"} · ${units.precipitation(it.precipitation)}"
                                        }
                                            ?: "${stringResource(R.string.precipitation)} ${stringResource(R.string.unavailable)}") + " · " +
                                        "${units.windSpeed(hour.windSpeed)} " +
                                        stringResource(windDirectionResource(hour.windDirection)),
                                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp),
                                    color = MaterialTheme.colorScheme.secondary,
                                    fontSize = 11.sp,
                                    lineHeight = 16.sp,
                                )
                            }
                            if (expanded) {
                                ForecastConfidenceBadge(hourConfidence, units)
                                ExpandedHourDetails(
                                    hour = hour,
                                    precipitationHour = precipitationHour,
                                    units = units,
                                    locale = locale,
                                    modifier = Modifier.padding(top = 12.dp, bottom = 8.dp),
                                    displayCondition = condition,
                                    displayIsDay = iconIsDay,
                                    confidence = hourConfidence,
                                )
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }
}

@Composable
internal fun DayAvailableDetails(day: DailyWeather, units: WeatherUnitFormatter, locale: java.util.Locale) {
    val basic = listOfNotNull(
        day.sunrise.takeIf(String::isNotBlank)?.let { R.string.sunrise to it.takeLast(5) },
        day.sunset.takeIf(String::isNotBlank)?.let { R.string.sunset to it.takeLast(5) },
        day.uvIndexMax?.takeIf { it.isFinite() && it >= 0.0 }?.let {
            R.string.uv_index_max to String.format(locale, "%.1f", it)
        },
        day.windGustsMax?.takeIf { it.isFinite() && it >= 0.0 }?.let { R.string.daily_wind_gusts to units.windSpeed(it) },
    )
    val advanced = listOfNotNull(
        dayDurationValue(day.sunshineDurationSeconds)?.let { R.string.sunshine_duration to it },
        dayDurationValue(day.daylightDurationSeconds)?.let { R.string.daylight_duration to it },
        day.precipitationHours?.takeIf { it.isFinite() && it >= 0.0 }?.let {
            R.string.precipitation_hours to String.format(locale, "%.1f h", it)
        },
        day.snowfallSum?.takeIf { it.isFinite() && it >= 0.0 }?.let { R.string.snowfall to units.snowfall(it) },
        day.rainSum?.takeIf { it.isFinite() && it >= 0.0 }?.let { R.string.rain to units.precipitation(it) },
        day.dominantWindDirection?.let {
            R.string.dominant_wind to "${stringResource(windDirectionResource(it))} · ${Math.floorMod(it, 360)}°"
        },
        day.shortwaveRadiationSum?.takeIf { it.isFinite() && it >= 0.0 }?.let {
            R.string.shortwave_radiation to String.format(locale, "%.1f MJ/m²", it)
        },
        day.et0?.takeIf { it.isFinite() && it >= 0.0 }?.let { R.string.et0_evapotranspiration to units.precipitation(it) },
    )
    var expanded by rememberSaveable(day.date) { mutableStateOf(false) }
    val expansionState = stringResource(if (expanded) R.string.hour_expanded else R.string.hour_collapsed)
    DayMetricGrid(basic)
    if (advanced.isNotEmpty()) {
        TextButton(onClick = { expanded = !expanded }, modifier = Modifier.heightIn(min = 48.dp)
            .semantics { stateDescription = expansionState }) {
            Text(stringResource(R.string.advanced_details))
            Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, contentDescription = null)
        }
        if (expanded) DayMetricGrid(advanced)
    }
}

@Composable
private fun DayMetricGrid(metrics: List<Pair<Int, String>>) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        metrics.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                pair.forEach { (label, value) ->
                    DaySummaryMetric(stringResource(label), value, Modifier.weight(1f))
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun dayDurationValue(seconds: Double?): String? = seconds?.takeIf { it.isFinite() && it >= 0.0 }
    ?.roundToInt()?.div(60)?.let { stringResource(R.string.duration_hours_minutes, it / 60, it % 60) }

@Composable
private fun DaySummaryMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
        Text(value, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

internal fun dailyPrecipitationSummary(
    day: DailyWeather,
    units: WeatherUnitFormatter,
): String = "${day.precipitationProbability?.let { "$it%" } ?: "—"} · ${units.precipitation(day.precipitationSum)}"

@Composable
private fun WeatherPanel(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        content = content,
    )
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, fontSize = 17.sp, fontWeight = FontWeight.Medium, modifier = Modifier.semantics { heading() })
}

@Composable
internal fun weatherAccent(kind: WeatherKind, isDay: Boolean): Color = if (LocalWeatherAppearance.current.dark) {
    conditionAccent(kind, isDay)
} else when {
    !isDay -> Color(0xFF5867AA)
    kind == WeatherKind.CLEAR || kind == WeatherKind.MAINLY_CLEAR -> Color(0xFFA06416)
    kind == WeatherKind.STORM -> Color(0xFF78509C)
    else -> MaterialTheme.colorScheme.secondary
}

fun conditionAccent(kind: WeatherKind, isDay: Boolean): Color = when {
    !isDay -> Color(0xFFA9B9FF)
    kind == WeatherKind.CLEAR -> Color(0xFFFFC766)
    kind == WeatherKind.MAINLY_CLEAR -> Color(0xFFFFCF78)
    kind == WeatherKind.PARTLY_CLOUDY -> Color(0xFF9ED9EA)
    kind == WeatherKind.RAIN -> Color(0xFF6DD3EA)
    kind == WeatherKind.STORM -> Color(0xFFC4A7FF)
    kind == WeatherKind.SNOW -> Color(0xFFD8F4FF)
    kind == WeatherKind.FOG -> Color(0xFFB8C7CA)
    else -> Color(0xFFA8C8D4)
}

internal fun formatDay(date: String, locale: java.util.Locale): String {
    val formatter = DateTimeFormatter.ofPattern("EEE", locale)
    return LocalDate.parse(date).format(formatter).replaceFirstChar { it.uppercase(locale) }
}

internal fun formatFullDay(date: String, locale: java.util.Locale): String =
    LocalDate.parse(date).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale))

internal fun hourlyForDay(hourly: List<HourlyWeather>, date: String): List<HourlyWeather> =
    hourly.asSequence()
        .filter { it.time.startsWith("${date}T") }
        .sortedBy { it.time }
        .toList()

internal fun dayForPage(days: List<DailyWeather>, page: Int): DailyWeather? = days.getOrNull(page)

internal fun upcomingHours(
    hourly: List<HourlyWeather>,
    currentHour: String,
): List<HourlyWeather> = hourly.dropWhile { it.time.take(13) < currentHour }.take(HOURLY_OUTLOOK_COUNT)

internal fun formatUpdatedAt(epochMillis: Long, locale: java.util.Locale): String =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
        .withLocale(locale)
        .format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.of("Europe/Prague")))

internal fun windDirectionResource(degrees: Int): Int = when ((Math.floorMod(degrees, 360) + 22) % 360 / 45) {
    0 -> R.string.wind_direction_north
    1 -> R.string.wind_direction_northeast
    2 -> R.string.wind_direction_east
    3 -> R.string.wind_direction_southeast
    4 -> R.string.wind_direction_south
    5 -> R.string.wind_direction_southwest
    6 -> R.string.wind_direction_west
    else -> R.string.wind_direction_northwest
}
