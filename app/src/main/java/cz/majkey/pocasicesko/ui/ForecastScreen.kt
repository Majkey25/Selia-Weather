package cz.majkey.pocasicesko.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import cz.majkey.pocasicesko.R
import cz.majkey.pocasicesko.data.CzechLocation
import cz.majkey.pocasicesko.data.DailyWeather
import cz.majkey.pocasicesko.data.HourlyWeather
import cz.majkey.pocasicesko.data.WeatherKind
import cz.majkey.pocasicesko.data.WeatherSnapshot
import cz.majkey.pocasicesko.data.conditionFor
import cz.majkey.pocasicesko.units.MeasurementSystem
import cz.majkey.pocasicesko.units.WeatherUnitFormatter
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ForecastScreen(
    snapshot: WeatherSnapshot,
    location: CzechLocation,
    padding: PaddingValues,
    fromCache: Boolean,
    refreshing: Boolean,
    refreshError: String?,
    measurementSystem: MeasurementSystem,
    onSearch: () -> Unit,
    onRefresh: () -> Unit,
    onSettings: () -> Unit,
) {
    val condition = conditionFor(snapshot.current.weatherCode, snapshot.current.isDay)
    val accent = conditionAccent(condition.kind, snapshot.current.isDay)
    val locale = LocalConfiguration.current.locales[0]
    val units = remember(measurementSystem, locale) { WeatherUnitFormatter(measurementSystem, locale) }
    var selectedDay by remember { mutableStateOf<DailyWeather?>(null) }
    var showDetails by remember { mutableStateOf(false) }
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        contentPadding = PaddingValues(start = 18.dp, top = 16.dp, end = 18.dp, bottom = 118.dp),
        verticalArrangement = Arrangement.spacedBy(26.dp),
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
            WeatherHero(snapshot = snapshot, accent = accent, units = units)
        }
        item {
            HourlyGraphPanel(snapshot = snapshot, accent = accent, units = units)
        }
        item {
            CurrentMetrics(snapshot = snapshot, accent = accent, units = units)
        }
        item {
            WeatherDetailAction { showDetails = true }
        }
        item {
            DailyForecastPanel(
                days = snapshot.daily,
                units = units,
                onDayClick = { selectedDay = it },
            )
        }
        item {
            Text(
                text = "ALADIN CZ 1 km · ECMWF · ČHMÚ · Open-Meteo",
                color = Color.White.copy(alpha = 0.52f),
                fontSize = 12.sp,
                lineHeight = 17.sp,
            )
        }
    }
    selectedDay?.let { day ->
        DayDetailSheet(
            day = day,
            hours = hourlyForDay(snapshot.hourly, day.date),
            units = units,
            onDismiss = { selectedDay = null },
        )
    }
    if (showDetails) {
        WeatherDetailSheet(
            snapshot = snapshot,
            location = location,
            units = units,
            onDismiss = { showDetails = false },
        )
    }
}

@Composable
private fun WeatherDetailAction(onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color = Color(0xA61A252E),
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.09f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 15.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.open_weather_details),
                modifier = Modifier.weight(1f),
                fontWeight = FontWeight.SemiBold,
            )
            Icon(
                Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.58f),
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
        Box(Modifier.fillMaxWidth()) {
            IconButton(
                onClick = onSettings,
                modifier = Modifier.align(Alignment.CenterStart),
            ) {
                Icon(Icons.Rounded.Settings, contentDescription = stringResource(R.string.open_settings))
            }
            Surface(
                modifier = Modifier
                    .align(Alignment.Center)
                    .clickable(onClick = onSearch),
                color = Color.White.copy(alpha = 0.13f),
                shape = RoundedCornerShape(24.dp),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = location.name,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = null, modifier = Modifier.size(20.dp))
                }
            }
            IconButton(
                onClick = onRefresh,
                modifier = Modifier.align(Alignment.CenterEnd),
            ) {
                if (refreshing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = Color.White,
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(Icons.Rounded.Refresh, contentDescription = stringResource(R.string.refresh_forecast))
                }
            }
        }
        Text(
            text = location.localizedRegion(),
            color = Color.White.copy(alpha = 0.55f),
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 7.dp),
        )
        if (fromCache) {
            Text(
                text = stringResource(R.string.cached_data, formatUpdatedAt(updatedAt, locale)),
                color = Color(0xFFFFD38B),
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun WeatherHero(snapshot: WeatherSnapshot, accent: Color, units: WeatherUnitFormatter) {
    val condition = conditionFor(snapshot.current.weatherCode, snapshot.current.isDay)
    val conditionLabel = stringResource(condition.labelResource())
    val today = snapshot.daily.first()
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        WeatherIcon(
            kind = condition.kind,
            isDay = snapshot.current.isDay,
            contentDescription = conditionLabel,
            modifier = Modifier.size(58.dp),
            tint = accent,
        )
        Text(
            text = units.temperature(snapshot.current.temperature),
            fontSize = 104.sp,
            lineHeight = 112.sp,
            fontWeight = FontWeight.Normal,
            letterSpacing = (-4).sp,
        )
        Text(conditionLabel, fontSize = 22.sp, fontWeight = FontWeight.Medium)
        Text(
            text = "${units.temperature(today.temperatureMin)} / ${units.temperature(today.temperatureMax)}  ·  " +
                stringResource(R.string.feels_like_temperature, units.temperature(snapshot.current.feelsLike)),
            color = Color.White.copy(alpha = 0.65f),
            fontSize = 14.sp,
            modifier = Modifier.padding(top = 5.dp),
        )
        Surface(
            modifier = Modifier.padding(top = 14.dp),
            color = accent.copy(alpha = 0.15f),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, accent.copy(alpha = 0.35f)),
        ) {
            Text(
                "ALADIN CZ 1 km",
                color = accent,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            )
        }
    }
}

@Composable
private fun HourlyGraphPanel(snapshot: WeatherSnapshot, accent: Color, units: WeatherUnitFormatter) {
    val currentHour = snapshot.current.time.take(13)
    val hours = upcomingHours(snapshot.hourly, currentHour)
    val scrollState = rememberScrollState()
    val itemWidth = 68.dp
    Column {
        SectionTitle(stringResource(R.string.next_hours, 20))
        Spacer(Modifier.height(12.dp))
        WeatherPanel {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(scrollState),
            ) {
                Column(
                    modifier = Modifier
                        .width(itemWidth * hours.size)
                        .padding(vertical = 16.dp),
                ) {
                    Row {
                        hours.forEachIndexed { index, hour ->
                            Text(
                                text = if (index == 0) stringResource(R.string.now) else hour.time.substringAfter('T').take(5),
                                modifier = Modifier.width(itemWidth),
                                color = Color.White.copy(alpha = 0.58f),
                                fontSize = 12.sp,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                    Spacer(Modifier.height(9.dp))
                    Row {
                        hours.forEach { hour ->
                            val condition = conditionFor(hour.weatherCode, hour.isDay)
                            Box(Modifier.width(itemWidth), contentAlignment = Alignment.Center) {
                                WeatherIcon(
                                    kind = condition.kind,
                                    isDay = hour.isDay,
                                    contentDescription = stringResource(condition.labelResource()),
                                    modifier = Modifier.size(25.dp),
                                    tint = conditionAccent(condition.kind, hour.isDay),
                                )
                            }
                        }
                    }
                    HourlyTemperatureLine(
                        temperatures = hours.map { it.temperature },
                        accent = accent,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(66.dp)
                            .padding(horizontal = itemWidth / 2, vertical = 8.dp),
                    )
                    Row {
                        hours.forEach { hour ->
                            Text(
                                text = units.temperature(hour.temperature),
                                modifier = Modifier.width(itemWidth),
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                    Spacer(Modifier.height(5.dp))
                    Row {
                        hours.forEach { hour ->
                            Text(
                                text = if (hour.precipitationProbability > 0) "${hour.precipitationProbability} %" else "–",
                                modifier = Modifier.width(itemWidth),
                                color = Color(0xFF8EDCF0),
                                fontSize = 11.sp,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HourlyTemperatureLine(temperatures: List<Double>, accent: Color, modifier: Modifier = Modifier) {
    if (temperatures.size < 2) return
    Canvas(modifier) {
        val min = temperatures.minOrNull() ?: return@Canvas
        val max = temperatures.maxOrNull() ?: return@Canvas
        val range = (max - min).coerceAtLeast(1.0)
        val step = size.width / (temperatures.size - 1)
        val path = Path()
        temperatures.forEachIndexed { index, temperature ->
            val x = step * index
            val y = size.height * (0.82f - ((temperature - min) / range).toFloat() * 0.64f)
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(
            path = path,
            brush = Brush.horizontalGradient(listOf(Color(0xFF58C8E2), accent, Color(0xFFFFC468))),
            style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round),
        )
        temperatures.forEachIndexed { index, temperature ->
            val x = step * index
            val y = size.height * (0.82f - ((temperature - min) / range).toFloat() * 0.64f)
            drawCircle(Color(0xFF111B24), radius = 5.dp.toPx(), center = Offset(x, y))
            drawCircle(accent, radius = 2.6.dp.toPx(), center = Offset(x, y))
        }
    }
}

@Composable
private fun CurrentMetrics(snapshot: WeatherSnapshot, accent: Color, units: WeatherUnitFormatter) {
    val today = snapshot.daily.first()
    val metrics = listOf(
        Triple(
            stringResource(R.string.precipitation),
            units.precipitation(snapshot.current.precipitation),
            stringResource(R.string.now),
        ),
        Triple(
            stringResource(R.string.wind),
            units.windSpeed(snapshot.current.windSpeed),
            stringResource(windDirectionResource(snapshot.current.windDirection)),
        ),
        Triple(stringResource(R.string.humidity), "${snapshot.current.humidity} %", stringResource(R.string.relative)),
        Triple(stringResource(R.string.pressure), units.pressure(snapshot.current.pressure), stringResource(R.string.sea_level)),
        Triple(stringResource(R.string.sun), "${today.sunrise.takeLast(5)}–${today.sunset.takeLast(5)}", stringResource(R.string.today)),
    )
    Column {
        SectionTitle(stringResource(R.string.current_details))
        Spacer(Modifier.height(12.dp))
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(end = 18.dp),
        ) {
            items(metrics) { metric ->
                Surface(
                    modifier = Modifier
                        .width(154.dp)
                        .height(98.dp),
                    color = Color(0xA61A252E),
                    shape = RoundedCornerShape(22.dp),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.09f)),
                ) {
                    Column(
                        modifier = Modifier.padding(15.dp),
                        verticalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier
                                    .size(7.dp)
                                    .padding(0.dp),
                            ) {
                                Canvas(Modifier.fillMaxSize()) { drawCircle(accent) }
                            }
                            Spacer(Modifier.width(7.dp))
                            Text(metric.first, color = Color.White.copy(alpha = 0.58f), fontSize = 12.sp)
                        }
                        Text(metric.second, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                        Text(metric.third, color = Color.White.copy(alpha = 0.45f), fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun DailyForecastPanel(
    days: List<DailyWeather>,
    units: WeatherUnitFormatter,
    onDayClick: (DailyWeather) -> Unit,
) {
    Column {
        SectionTitle(stringResource(R.string.days_14))
        Spacer(Modifier.height(12.dp))
        WeatherPanel {
            Column {
                days.forEachIndexed { index, day ->
                    DailyRow(
                        day = day,
                        today = index == 0,
                        units = units,
                        onClick = { onDayClick(day) },
                    )
                    if (index != days.lastIndex) {
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 15.dp),
                            color = Color.White.copy(alpha = 0.07f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DailyRow(
    day: DailyWeather,
    today: Boolean,
    units: WeatherUnitFormatter,
    onClick: () -> Unit,
) {
    val condition = conditionFor(day.weatherCode)
    val conditionLabel = stringResource(condition.labelResource())
    val locale = LocalConfiguration.current.locales[0]
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 78.dp)
            .padding(horizontal = 15.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.width(74.dp)) {
            Text(
                text = if (today) stringResource(R.string.today) else formatDay(day.date, locale),
                fontSize = 14.sp,
                fontWeight = if (today) FontWeight.SemiBold else FontWeight.Normal,
            )
            Text(
                text = LocalDate.parse(day.date).format(DateTimeFormatter.ofPattern("d MMM", locale)),
                color = Color.White.copy(alpha = 0.48f),
                fontSize = 11.sp,
            )
        }
        WeatherIcon(
            kind = condition.kind,
            isDay = true,
            contentDescription = conditionLabel,
            modifier = Modifier.size(27.dp),
            tint = conditionAccent(condition.kind, true),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 11.dp, end = 8.dp),
        ) {
            Text(
                text = conditionLabel,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${day.precipitationProbability}% · ${units.windSpeed(day.windSpeedMax)}",
                color = Color(0xFF8EDCF0),
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = units.temperature(day.temperatureMin),
                color = Color.White.copy(alpha = 0.5f),
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
            tint = Color.White.copy(alpha = 0.42f),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DayDetailSheet(
    day: DailyWeather,
    hours: List<HourlyWeather>,
    units: WeatherUnitFormatter,
    onDismiss: () -> Unit,
) {
    val locale = LocalConfiguration.current.locales[0]
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF101820),
        contentColor = Color.White,
        sheetState = sheetState,
        sheetGesturesEnabled = false,
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .navigationBarsPadding(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 34.dp),
        ) {
            item {
                Text(formatFullDay(day.date, locale), fontSize = 25.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "${stringResource(R.string.whole_day_hours)} · ${hours.size}",
                    color = Color.White.copy(alpha = 0.56f),
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 3.dp),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp, bottom = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("${units.temperature(day.temperatureMin)} / ${units.temperature(day.temperatureMax)}", fontSize = 12.sp)
                    Text(units.precipitation(day.precipitationSum), fontSize = 12.sp)
                    Text(units.windSpeed(day.windSpeedMax), fontSize = 12.sp)
                }
            }
            itemsIndexed(hours, key = { index, hour -> "${hour.time}-$index" }) { _, hour ->
                val condition = conditionFor(hour.weatherCode, hour.isDay)
                val conditionLabel = stringResource(condition.labelResource())
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(62.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(hour.time.takeLast(5), modifier = Modifier.width(55.dp), fontWeight = FontWeight.SemiBold)
                    WeatherIcon(
                        kind = condition.kind,
                        isDay = hour.isDay,
                        contentDescription = conditionLabel,
                        modifier = Modifier.size(26.dp),
                        tint = conditionAccent(condition.kind, hour.isDay),
                    )
                    Text(
                        conditionLabel,
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 12.dp),
                        color = Color.White.copy(alpha = 0.72f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        if (hour.precipitationProbability > 0) "${hour.precipitationProbability}%" else "–",
                        modifier = Modifier.width(44.dp),
                        color = Color(0xFF8EDCF0),
                        textAlign = TextAlign.End,
                    )
                    Text(
                        units.temperature(hour.temperature),
                        modifier = Modifier
                            .width(48.dp)
                            .padding(start = 8.dp),
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.End,
                    )
                }
                HorizontalDivider(color = Color.White.copy(alpha = 0.06f))
            }
        }
    }
}

@Composable
private fun WeatherPanel(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color(0xB818222B),
        shape = RoundedCornerShape(28.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.09f)),
        content = content,
    )
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
}

fun conditionAccent(kind: WeatherKind, isDay: Boolean): Color = when {
    !isDay -> Color(0xFFA9B9FF)
    kind == WeatherKind.CLEAR -> Color(0xFFFFC766)
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

internal fun upcomingHours(
    hourly: List<HourlyWeather>,
    currentHour: String,
    limit: Int = 20,
): List<HourlyWeather> = hourly.dropWhile { it.time.take(13) < currentHour }.take(limit)

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
