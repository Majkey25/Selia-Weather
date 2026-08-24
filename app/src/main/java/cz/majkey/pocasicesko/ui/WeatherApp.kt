package cz.majkey.pocasicesko.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Radar
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cz.majkey.pocasicesko.data.CzechLocation
import cz.majkey.pocasicesko.data.DailyWeather
import cz.majkey.pocasicesko.data.HourlyWeather
import cz.majkey.pocasicesko.data.WeatherKind
import cz.majkey.pocasicesko.data.WeatherRepository
import cz.majkey.pocasicesko.data.WeatherSnapshot
import cz.majkey.pocasicesko.data.conditionFor
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private enum class Destination {
    WEATHER,
    RADAR,
}

private sealed interface WeatherUiState {
    data object Loading : WeatherUiState

    data class Content(
        val snapshot: WeatherSnapshot,
        val fromCache: Boolean,
        val refreshing: Boolean,
        val refreshError: String? = null,
    ) : WeatherUiState

    data class Error(val message: String) : WeatherUiState
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WeatherApp(repository: WeatherRepository) {
    var destination by remember { mutableStateOf(Destination.WEATHER) }
    var location by remember { mutableStateOf(repository.lastLocation()) }
    var reloadKey by remember { mutableIntStateOf(0) }
    var showLocationSearch by remember { mutableStateOf(false) }
    var state by remember { mutableStateOf<WeatherUiState>(WeatherUiState.Loading) }

    LaunchedEffect(location, reloadKey) {
        val cached = withContext(Dispatchers.IO) { repository.cachedForecast(location) }
        state = cached?.let { WeatherUiState.Content(it, fromCache = true, refreshing = true) }
            ?: WeatherUiState.Loading
        try {
            val fresh = repository.fetchForecast(location)
            state = WeatherUiState.Content(fresh, fromCache = false, refreshing = false)
        } catch (error: Exception) {
            val message = error.message ?: "Předpověď se nepodařilo načíst."
            state = cached?.let {
                WeatherUiState.Content(it, fromCache = true, refreshing = false, refreshError = message)
            } ?: WeatherUiState.Error(message)
        }
    }

    val snapshot = (state as? WeatherUiState.Content)?.snapshot
    val gradient = weatherGradient(snapshot)

    WeatherTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(gradient)),
        ) {
            Scaffold(
                containerColor = Color.Transparent,
                bottomBar = {
                    NavigationBar(
                        modifier = Modifier.navigationBarsPadding(),
                        containerColor = Color(0x33101E2A),
                        tonalElevation = 0.dp,
                    ) {
                        NavigationBarItem(
                            selected = destination == Destination.WEATHER,
                            onClick = { destination = Destination.WEATHER },
                            icon = { Icon(Icons.Rounded.WbSunny, contentDescription = null) },
                            label = { Text("Počasí") },
                            colors = navigationColors(),
                        )
                        NavigationBarItem(
                            selected = destination == Destination.RADAR,
                            onClick = { destination = Destination.RADAR },
                            icon = { Icon(Icons.Rounded.Radar, contentDescription = null) },
                            label = { Text("Radar") },
                            colors = navigationColors(),
                        )
                    }
                },
            ) { padding ->
                when (destination) {
                    Destination.WEATHER -> WeatherDestination(
                        state = state,
                        location = location,
                        padding = padding,
                        onSearch = { showLocationSearch = true },
                        onRetry = { reloadKey++ },
                    )

                    Destination.RADAR -> RadarScreen(
                        padding = padding,
                    )
                }
            }

            if (showLocationSearch) {
                LocationSearchSheet(
                    repository = repository,
                    onDismiss = { showLocationSearch = false },
                    onSelect = { selected ->
                        repository.selectLocation(selected)
                        location = selected
                        showLocationSearch = false
                    },
                )
            }
        }
    }
}

@Composable
private fun WeatherDestination(
    state: WeatherUiState,
    location: CzechLocation,
    padding: PaddingValues,
    onSearch: () -> Unit,
    onRetry: () -> Unit,
) {
    when (state) {
        WeatherUiState.Loading -> Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(color = Color.White)
        }

        is WeatherUiState.Error -> ErrorState(
            message = state.message,
            padding = padding,
            onRetry = onRetry,
        )

        is WeatherUiState.Content -> WeatherScreen(
            snapshot = state.snapshot,
            location = location,
            padding = padding,
            fromCache = state.fromCache,
            refreshing = state.refreshing,
            refreshError = state.refreshError,
            onSearch = onSearch,
            onRefresh = onRetry,
        )
    }
}

@Composable
private fun WeatherScreen(
    snapshot: WeatherSnapshot,
    location: CzechLocation,
    padding: PaddingValues,
    fromCache: Boolean,
    refreshing: Boolean,
    refreshError: String?,
    onSearch: () -> Unit,
    onRefresh: () -> Unit,
) {
    val condition = conditionFor(snapshot.current.weatherCode, snapshot.current.isDay)
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        contentPadding = PaddingValues(start = 20.dp, top = 18.dp, end = 20.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        item {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(18.dp))
                            .clickable(onClick = onSearch)
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.LocationOn, contentDescription = null, tint = Color.White)
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = location.name,
                                color = Color.White,
                                fontSize = 22.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = location.region,
                                color = Color.White.copy(alpha = 0.72f),
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = null, tint = Color.White)
                    }
                    IconButton(onClick = onRefresh) {
                        if (refreshing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(22.dp),
                                color = Color.White,
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(
                                Icons.Rounded.Refresh,
                                contentDescription = "Obnovit předpověď",
                                tint = Color.White,
                            )
                        }
                    }
                }

                if (fromCache || refreshError != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Uložená data · ${formatUpdatedAt(snapshot.updatedAtEpochMillis)}",
                        color = Color.White.copy(alpha = 0.82f),
                        fontSize = 13.sp,
                    )
                }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text(
                        text = "${snapshot.current.temperature.roundToInt()}°",
                        color = Color.White,
                        fontSize = 86.sp,
                        lineHeight = 88.sp,
                        fontWeight = FontWeight.Light,
                    )
                    Text(
                        text = condition.label,
                        color = Color.White,
                        fontSize = 23.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = "Pocitově ${snapshot.current.feelsLike.roundToInt()}°",
                        color = Color.White.copy(alpha = 0.74f),
                        fontSize = 15.sp,
                    )
                }
                WeatherIcon(
                    kind = condition.kind,
                    isDay = snapshot.current.isDay,
                    contentDescription = condition.label,
                    modifier = Modifier.size(92.dp),
                    tint = Color.White.copy(alpha = 0.95f),
                )
            }
        }

        item {
            SectionTitle("Dalších 24 hodin")
            Spacer(Modifier.height(14.dp))
            HourlyForecast(snapshot)
        }

        item {
            SectionTitle("Teď podrobně")
            Spacer(Modifier.height(14.dp))
            MetricsPanel(snapshot)
        }

        item {
            SectionTitle("14 dní")
            Spacer(Modifier.height(14.dp))
            DailyForecast(snapshot.daily)
        }

        item {
            Text(
                text = "ALADIN CZ 1 km pro první 3 dny · navazující ECMWF do 14 dnů\nData ČHMÚ přes Open-Meteo",
                color = Color.White.copy(alpha = 0.64f),
                fontSize = 12.sp,
                lineHeight = 17.sp,
            )
        }
    }
}

@Composable
private fun HourlyForecast(snapshot: WeatherSnapshot) {
    val currentHour = snapshot.current.time.take(13)
    val hours = snapshot.hourly.dropWhile { it.time.take(13) < currentHour }.take(24)
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(22.dp),
        contentPadding = PaddingValues(end = 12.dp),
    ) {
        items(hours) { hour ->
            HourItem(hour = hour, now = hour == hours.firstOrNull())
        }
    }
}

@Composable
private fun HourItem(hour: HourlyWeather, now: Boolean) {
    val condition = conditionFor(hour.weatherCode, hour.isDay)
    Column(
        modifier = Modifier.width(52.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Text(
            text = if (now) "Teď" else hour.time.substringAfter('T').take(5),
            color = Color.White.copy(alpha = 0.82f),
            fontSize = 13.sp,
        )
        WeatherIcon(
            kind = condition.kind,
            isDay = hour.isDay,
            contentDescription = condition.label,
            modifier = Modifier.size(28.dp),
        )
        Text(
            text = "${hour.temperature.roundToInt()}°",
            color = Color.White,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = if (hour.precipitationProbability > 0) "${hour.precipitationProbability} %" else " ",
            color = Color(0xFFCDEBFF),
            fontSize = 11.sp,
        )
    }
}

@Composable
private fun MetricsPanel(snapshot: WeatherSnapshot) {
    val today = snapshot.daily.first()
    val metrics = listOf(
        "Srážky" to "${snapshot.current.precipitation.formatOneDecimal()} mm",
        "Vlhkost" to "${snapshot.current.humidity} %",
        "Vítr" to "${snapshot.current.windSpeed.roundToInt()} km/h ${windDirection(snapshot.current.windDirection)}",
        "Nárazy" to "${snapshot.current.windGusts.roundToInt()} km/h",
        "Tlak" to "${snapshot.current.pressure.roundToInt()} hPa",
        "Slunce" to "${today.sunrise.takeLast(5)}–${today.sunset.takeLast(5)}",
    )
    Surface(
        color = Color.White.copy(alpha = 0.10f),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column {
            metrics.chunked(2).forEachIndexed { rowIndex, row ->
                Row(Modifier.fillMaxWidth()) {
                    row.forEach { metric ->
                        MetricCell(
                            label = metric.first,
                            value = metric.second,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                if (rowIndex < metrics.lastIndex / 2) {
                    HorizontalDivider(color = Color.White.copy(alpha = 0.12f))
                }
            }
        }
    }
}

@Composable
private fun MetricCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
        Text(text = label, color = Color.White.copy(alpha = 0.66f), fontSize = 12.sp)
        Spacer(Modifier.height(3.dp))
        Text(
            text = value,
            color = Color.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun DailyForecast(days: List<DailyWeather>) {
    Surface(
        color = Color.White.copy(alpha = 0.10f),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column {
            days.forEachIndexed { index, day ->
                DayRow(day = day, today = index == 0)
                if (index != days.lastIndex) {
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = Color.White.copy(alpha = 0.12f),
                    )
                }
            }
        }
    }
}

@Composable
private fun DayRow(day: DailyWeather, today: Boolean) {
    val condition = conditionFor(day.weatherCode)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (today) "Dnes" else formatDay(day.date),
            modifier = Modifier.width(74.dp),
            color = Color.White,
            fontSize = 15.sp,
            fontWeight = if (today) FontWeight.SemiBold else FontWeight.Normal,
        )
        WeatherIcon(
            kind = condition.kind,
            isDay = true,
            contentDescription = condition.label,
            modifier = Modifier.size(25.dp),
        )
        Text(
            text = if (day.precipitationProbability > 0) "${day.precipitationProbability} %" else "",
            modifier = Modifier
                .padding(start = 8.dp)
                .weight(1f),
            color = Color(0xFFCDEBFF),
            fontSize = 12.sp,
        )
        Text(
            text = "${day.temperatureMin.roundToInt()}°",
            color = Color.White.copy(alpha = 0.62f),
            fontSize = 16.sp,
            textAlign = TextAlign.End,
        )
        Spacer(Modifier.width(20.dp))
        Text(
            text = "${day.temperatureMax.roundToInt()}°",
            modifier = Modifier.width(32.dp),
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.End,
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        color = Color.White,
        fontSize = 18.sp,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun ErrorState(message: String, padding: PaddingValues, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Rounded.WbSunny,
            contentDescription = null,
            modifier = Modifier.size(62.dp),
            tint = Color.White.copy(alpha = 0.9f),
        )
        Spacer(Modifier.height(18.dp))
        Text("Předpověď není dostupná", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(
            text = message,
            color = Color.White.copy(alpha = 0.72f),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(14.dp))
        TextButton(onClick = onRetry) { Text("Zkusit znovu") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LocationSearchSheet(
    repository: WeatherRepository,
    onDismiss: () -> Unit,
    onSelect: (CzechLocation) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf(emptyList<CzechLocation>()) }
    var searching by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val focusManager = LocalFocusManager.current

    LaunchedEffect(query) {
        val cleaned = query.trim()
        if (cleaned.length < 2) {
            results = emptyList()
            searching = false
            error = null
            return@LaunchedEffect
        }
        delay(350)
        searching = true
        error = null
        try {
            results = repository.searchLocations(cleaned)
        } catch (failure: Exception) {
            results = emptyList()
            error = failure.message ?: "Hledání se nepodařilo."
        } finally {
            searching = false
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF102332),
        contentColor = Color.White,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 30.dp),
        ) {
            Text("Vyberte město v Česku", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "Hledat české město" },
                label = { Text("Město nebo obec") },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
            )
            Spacer(Modifier.height(12.dp))
            if (searching) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .size(28.dp),
                    color = Color.White,
                    strokeWidth = 2.dp,
                )
            }
            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 8.dp))
            }
            results.forEach { result ->
                ListItem(
                    headlineContent = { Text(result.name) },
                    supportingContent = { Text(result.region) },
                    leadingContent = { Icon(Icons.Rounded.LocationOn, contentDescription = null) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(result) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
            if (!searching && error == null && query.trim().length >= 2 && results.isEmpty()) {
                Text(
                    "V Česku jsme nic nenašli.",
                    color = Color.White.copy(alpha = 0.68f),
                    modifier = Modifier.padding(vertical = 14.dp),
                )
            }
        }
    }
}

private fun weatherGradient(snapshot: WeatherSnapshot?): List<Color> {
    if (snapshot == null) return listOf(Color(0xFF315E7D), Color(0xFF102332))
    val condition = conditionFor(snapshot.current.weatherCode, snapshot.current.isDay)
    return when {
        !snapshot.current.isDay -> listOf(Color(0xFF071827), Color(0xFF18364F))
        condition.kind == WeatherKind.STORM -> listOf(Color(0xFF293246), Color(0xFF5B5966))
        condition.kind == WeatherKind.RAIN -> listOf(Color(0xFF234158), Color(0xFF607D8B))
        condition.kind == WeatherKind.CLOUDY || condition.kind == WeatherKind.FOG ->
            listOf(Color(0xFF4E6878), Color(0xFF91A8B2))
        else -> listOf(Color(0xFF2C6C9F), Color(0xFF83BFE2))
    }
}

private fun formatDay(date: String): String {
    val formatter = DateTimeFormatter.ofPattern("EEE", Locale.forLanguageTag("cs-CZ"))
    return LocalDate.parse(date).format(formatter).replaceFirstChar { it.uppercase() }
}

private fun formatUpdatedAt(epochMillis: Long): String {
    val formatter = DateTimeFormatter.ofPattern("d. M. HH:mm", Locale.forLanguageTag("cs-CZ"))
    return formatter.format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.of("Europe/Prague")))
}

private fun windDirection(degrees: Int): String {
    val directions = listOf("S", "SV", "V", "JV", "J", "JZ", "Z", "SZ")
    return directions[((degrees + 22) % 360) / 45]
}

private fun Double.formatOneDecimal(): String = String.format(Locale.US, "%.1f", this)

@Composable
private fun navigationColors() = NavigationBarItemDefaults.colors(
    selectedIconColor = Color.White,
    selectedTextColor = Color.White,
    indicatorColor = Color.White.copy(alpha = 0.16f),
    unselectedIconColor = Color.White.copy(alpha = 0.66f),
    unselectedTextColor = Color.White.copy(alpha = 0.66f),
)
