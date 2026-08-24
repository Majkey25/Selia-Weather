package cz.majkey.pocasicesko.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import cz.majkey.pocasicesko.data.CzechLocation
import cz.majkey.pocasicesko.data.DeviceLocationRepository
import cz.majkey.pocasicesko.data.LocationOutsideCzechiaException
import cz.majkey.pocasicesko.data.LocationPermissionException
import cz.majkey.pocasicesko.data.SystemLocationDisabledException
import cz.majkey.pocasicesko.data.WeatherKind
import cz.majkey.pocasicesko.data.WeatherRepository
import cz.majkey.pocasicesko.data.WeatherSnapshot
import cz.majkey.pocasicesko.data.conditionFor
import cz.majkey.pocasicesko.locale.normalizeLanguageTag
import cz.majkey.pocasicesko.R
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class Destination {
    WEATHER,
    MAPS,
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
fun WeatherApp(repository: WeatherRepository, onLanguage: (String) -> Unit) {
    val context = LocalContext.current
    val deviceLocationRepository = remember { DeviceLocationRepository(context) }
    var destination by remember { mutableStateOf(Destination.WEATHER) }
    var location by remember { mutableStateOf(repository.lastLocation()) }
    var reloadKey by remember { mutableIntStateOf(0) }
    var showLocationSearch by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var state by remember { mutableStateOf<WeatherUiState>(WeatherUiState.Loading) }
    val forecastLoadFailed = stringResource(R.string.forecast_load_failed)
    val serverError = stringResource(R.string.server_error)

    LaunchedEffect(location, reloadKey) {
        val cached = withContext(Dispatchers.IO) { repository.cachedForecast(location) }
        state = cached?.let { WeatherUiState.Content(it, fromCache = true, refreshing = true) }
            ?: WeatherUiState.Loading
        try {
            val fresh = repository.fetchForecast(location)
            state = WeatherUiState.Content(fresh, fromCache = false, refreshing = false)
        } catch (error: Exception) {
            val message = if (error is java.io.IOException) serverError else forecastLoadFailed
            state = cached?.let {
                WeatherUiState.Content(it, fromCache = true, refreshing = false, refreshError = message)
            } ?: WeatherUiState.Error(message)
        }
    }

    val snapshot = (state as? WeatherUiState.Content)?.snapshot
    WeatherTheme {
        Box(Modifier.fillMaxSize()) {
            WeatherBackdrop(snapshot = snapshot)
            Scaffold(
                containerColor = Color.Transparent,
                contentColor = Color.White,
                bottomBar = {
                    FloatingNavigation(
                        destination = destination,
                        onDestination = { destination = it },
                    )
                },
            ) { padding ->
                when (destination) {
                    Destination.WEATHER -> WeatherDestination(
                        state = state,
                        location = location,
                        padding = padding,
                        onSearch = { showLocationSearch = true },
                        onRetry = { reloadKey++ },
                        onSettings = { showSettings = true },
                    )

                    Destination.MAPS -> MapHubScreen(padding = padding)
                }
            }

            if (showLocationSearch) {
                LocationSearchSheet(
                    repository = repository,
                    deviceLocationRepository = deviceLocationRepository,
                    selectedLocation = location,
                    onDismiss = { showLocationSearch = false },
                    onSelect = { selected ->
                        repository.selectLocation(selected)
                        location = selected
                        showLocationSearch = false
                    },
                )
            }
            if (showSettings) {
                SettingsSheet(
                    selectedTag = selectedLanguageTag(context),
                    onLanguage = onLanguage,
                    onDismiss = { showSettings = false },
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
    onSettings: () -> Unit,
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

        is WeatherUiState.Content -> ForecastScreen(
            snapshot = state.snapshot,
            location = location,
            padding = padding,
            fromCache = state.fromCache,
            refreshing = state.refreshing,
            refreshError = state.refreshError,
            onSearch = onSearch,
            onRefresh = onRetry,
            onSettings = onSettings,
        )
    }
}

@Composable
private fun FloatingNavigation(destination: Destination, onDestination: (Destination) -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(bottom = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            color = Color(0xED101B23),
            shape = RoundedCornerShape(34.dp),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
        ) {
            Row(
                modifier = Modifier
                    .width(246.dp)
                    .height(64.dp)
                    .padding(6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                NavigationItem(
                    selected = destination == Destination.WEATHER,
                    label = stringResource(R.string.nav_weather),
                    icon = { Icon(Icons.Rounded.WbSunny, contentDescription = null) },
                    modifier = Modifier.weight(1f),
                    onClick = { onDestination(Destination.WEATHER) },
                )
                NavigationItem(
                    selected = destination == Destination.MAPS,
                    label = stringResource(R.string.nav_maps),
                    icon = { Icon(Icons.Rounded.Map, contentDescription = null) },
                    modifier = Modifier.weight(1f),
                    onClick = { onDestination(Destination.MAPS) },
                )
            }
        }
    }
}

@Composable
private fun NavigationItem(
    selected: Boolean,
    label: String,
    icon: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        modifier = modifier
            .fillMaxSize()
            .clickable(onClick = onClick),
        color = if (selected) Color(0xFF245969) else Color.Transparent,
        shape = RoundedCornerShape(28.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            icon()
            if (selected) {
                Spacer(Modifier.width(7.dp))
                Text(label, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            }
        }
    }
}

@Composable
private fun WeatherBackdrop(snapshot: WeatherSnapshot?) {
    val palette = weatherPalette(snapshot)
    Canvas(Modifier.fillMaxSize()) {
        drawRect(brush = Brush.verticalGradient(palette.background))
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(palette.primaryGlow, Color.Transparent),
                center = Offset(size.width * 0.14f, size.height * 0.18f),
                radius = size.width * 0.78f,
            ),
            radius = size.width * 0.78f,
            center = Offset(size.width * 0.14f, size.height * 0.18f),
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(palette.secondaryGlow, Color.Transparent),
                center = Offset(size.width * 0.9f, size.height * 0.38f),
                radius = size.width * 0.7f,
            ),
            radius = size.width * 0.7f,
            center = Offset(size.width * 0.9f, size.height * 0.38f),
        )
        if (snapshot?.current?.isDay == false) {
            NIGHT_STARS.forEach { star ->
                drawCircle(
                    color = Color.White.copy(alpha = star.third),
                    radius = 1.2.dp.toPx(),
                    center = Offset(size.width * star.first, size.height * star.second),
                )
            }
        }
    }
}

private data class WeatherPalette(
    val background: List<Color>,
    val primaryGlow: Color,
    val secondaryGlow: Color,
)

private fun weatherPalette(snapshot: WeatherSnapshot?): WeatherPalette {
    if (snapshot == null) {
        return WeatherPalette(
            background = listOf(Color(0xFF17384A), Color(0xFF09131C), Color(0xFF050A0F)),
            primaryGlow = Color(0x333E9FBD),
            secondaryGlow = Color(0x22205A76),
        )
    }
    val kind = conditionFor(snapshot.current.weatherCode, snapshot.current.isDay).kind
    return when {
        !snapshot.current.isDay -> WeatherPalette(
            background = listOf(Color(0xFF111A33), Color(0xFF080D1A), Color(0xFF04070D)),
            primaryGlow = Color(0x2D536BAA),
            secondaryGlow = Color(0x1F6D4F8A),
        )
        kind == WeatherKind.STORM || kind == WeatherKind.RAIN -> WeatherPalette(
            background = listOf(Color(0xFF263A49), Color(0xFF101D28), Color(0xFF070C11)),
            primaryGlow = Color(0x2D537F92),
            secondaryGlow = Color(0x1F758494),
        )
        kind == WeatherKind.FOG || kind == WeatherKind.CLOUDY -> WeatherPalette(
            background = listOf(Color(0xFF35444E), Color(0xFF17232B), Color(0xFF080D11)),
            primaryGlow = Color(0x2A9AA6A8),
            secondaryGlow = Color(0x1F617984),
        )
        else -> WeatherPalette(
            background = listOf(Color(0xFF1D5D7A), Color(0xFF102A3C), Color(0xFF060E16)),
            primaryGlow = Color(0x35E0A85D),
            secondaryGlow = Color(0x2D3FA0BF),
        )
    }
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
        Icon(Icons.Rounded.WbSunny, contentDescription = null, modifier = Modifier.size(56.dp))
        Spacer(Modifier.height(18.dp))
        Text(stringResource(R.string.forecast_unavailable), fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(message, color = Color.White.copy(alpha = 0.7f), textAlign = TextAlign.Center)
        Spacer(Modifier.height(14.dp))
        TextButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LocationSearchSheet(
    repository: WeatherRepository,
    deviceLocationRepository: DeviceLocationRepository,
    selectedLocation: CzechLocation,
    onDismiss: () -> Unit,
    onSelect: (CzechLocation) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf(emptyList<CzechLocation>()) }
    var favorites by remember { mutableStateOf(repository.favoriteLocations()) }
    var searching by remember { mutableStateOf(false) }
    var locating by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val focusManager = LocalFocusManager.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val locationPermissionRequired = stringResource(R.string.location_permission_required)
    val locationLookupFailed = stringResource(R.string.location_lookup_failed)
    val locationOutsideCzechia = stringResource(R.string.location_outside_czechia)
    val enableSystemLocation = stringResource(R.string.enable_system_location)
    val favoriteLimit = stringResource(R.string.favorite_limit)
    val searchFailed = stringResource(R.string.search_failed)
    val searchInCzechia = stringResource(R.string.search_in_czechia)

    fun locationError(failure: Exception): String = when (failure) {
        is LocationPermissionException -> locationPermissionRequired
        is LocationOutsideCzechiaException -> locationOutsideCzechia
        is SystemLocationDisabledException -> enableSystemLocation
        else -> locationLookupFailed
    }

    fun loadDeviceLocation() {
        scope.launch {
            locating = true
            error = null
            try {
                onSelect(deviceLocationRepository.currentLocation())
            } catch (failure: Exception) {
                error = locationError(failure)
            } finally {
                locating = false
            }
        }
    }

    fun toggleFavorite(location: CzechLocation) {
        try {
            repository.toggleFavorite(location)
            favorites = repository.favoriteLocations()
            error = null
        } catch (_: IllegalStateException) {
            error = "$favoriteLimit (12)"
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        if (grants.values.any { it }) {
            loadDeviceLocation()
        } else {
            error = locationPermissionRequired
        }
    }

    fun requestDeviceLocation() {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            loadDeviceLocation()
        } else {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                ),
            )
        }
    }

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
            error = searchFailed
        } finally {
            searching = false
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF101820),
        contentColor = Color.White,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 30.dp),
        ) {
            Text(stringResource(R.string.locations), fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(14.dp))
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = ::requestDeviceLocation),
                color = Color(0xFF1A2A34),
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.09f)),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 15.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.MyLocation, contentDescription = null, tint = Color(0xFF83D6E8))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.use_my_location), fontWeight = FontWeight.SemiBold)
                        Text(
                            stringResource(R.string.location_purpose),
                            color = Color.White.copy(alpha = 0.52f),
                            fontSize = 12.sp,
                        )
                    }
                    if (locating) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            color = Color(0xFF83D6E8),
                            strokeWidth = 2.dp,
                        )
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 15.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.current_location), color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp)
                    Text(selectedLocation.name, fontWeight = FontWeight.SemiBold)
                }
                IconButton(onClick = { toggleFavorite(selectedLocation) }) {
                    Icon(
                        imageVector = if (favorites.containsLocation(selectedLocation)) {
                            Icons.Rounded.Star
                        } else {
                            Icons.Rounded.StarBorder
                        },
                        contentDescription = stringResource(R.string.toggle_favorite),
                        tint = Color(0xFFFFC766),
                    )
                }
            }

            if (favorites.isNotEmpty()) {
                Text(
                    stringResource(R.string.favorites),
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                    color = Color.White.copy(alpha = 0.58f),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                favorites.forEach { favorite ->
                    LocationRow(
                        location = favorite,
                        favorite = true,
                        onSelect = { onSelect(favorite) },
                        onFavorite = { toggleFavorite(favorite) },
                    )
                }
            }

            Text(
                searchInCzechia,
                modifier = Modifier.padding(top = 14.dp, bottom = 8.dp),
                color = Color.White.copy(alpha = 0.58f),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = searchInCzechia },
                label = { Text(stringResource(R.string.city_or_municipality)) },
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
                LocationRow(
                    location = result,
                    favorite = favorites.containsLocation(result),
                    onSelect = { onSelect(result) },
                    onFavorite = { toggleFavorite(result) },
                )
            }
            if (!searching && error == null && query.trim().length >= 2 && results.isEmpty()) {
                Text(
                    stringResource(R.string.no_czech_result),
                    color = Color.White.copy(alpha = 0.68f),
                    modifier = Modifier.padding(vertical = 14.dp),
                )
            }
        }
    }
}

@Composable
private fun LocationRow(
    location: CzechLocation,
    favorite: Boolean,
    onSelect: () -> Unit,
    onFavorite: () -> Unit,
) {
    ListItem(
        headlineContent = {
            Text(location.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = { Text(location.region) },
        leadingContent = { Icon(Icons.Rounded.LocationOn, contentDescription = null) },
        trailingContent = {
            IconButton(onClick = onFavorite) {
                Icon(
                    imageVector = if (favorite) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                    contentDescription = stringResource(
                        if (favorite) R.string.remove_favorite else R.string.add_favorite,
                    ),
                    tint = Color(0xFFFFC766),
                )
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

private fun List<CzechLocation>.containsLocation(location: CzechLocation): Boolean = any {
    kotlin.math.abs(it.latitude - location.latitude) <= 0.000_001 &&
        kotlin.math.abs(it.longitude - location.longitude) <= 0.000_001
}

private fun selectedLanguageTag(context: android.content.Context): String = normalizeLanguageTag(
    context.getSharedPreferences("app_locale", android.content.Context.MODE_PRIVATE)
        .getString("language_tag", null),
)

private val NIGHT_STARS = listOf(
    Triple(0.12f, 0.11f, 0.34f),
    Triple(0.27f, 0.22f, 0.22f),
    Triple(0.61f, 0.13f, 0.28f),
    Triple(0.78f, 0.25f, 0.2f),
    Triple(0.9f, 0.1f, 0.3f),
    Triple(0.48f, 0.31f, 0.18f),
)
