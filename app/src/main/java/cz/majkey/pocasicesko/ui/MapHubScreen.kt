package cz.majkey.pocasicesko.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cz.majkey.pocasicesko.R
import cz.majkey.pocasicesko.data.CzechLocation
import cz.majkey.pocasicesko.data.PrecipitationField
import cz.majkey.pocasicesko.data.isInCzechia
import cz.majkey.pocasicesko.units.MeasurementSystem
import cz.majkey.pocasicesko.units.WeatherUnitFormatter
import java.io.IOException
import org.json.JSONException

@Composable
internal fun MapHubScreen(
    location: CzechLocation,
    timezone: String,
    measurementSystem: MeasurementSystem,
    loadPrecipitationField: suspend (CzechLocation) -> PrecipitationField,
    padding: PaddingValues,
) {
    val locale = LocalConfiguration.current.locales[0]
    val languageTag = locale?.toLanguageTag()
    val units = remember(measurementSystem, locale) { WeatherUnitFormatter(measurementSystem, locale) }
    var mode by remember { mutableStateOf(MapMode.OBSERVED) }
    var retryKey by remember(location) { mutableIntStateOf(0) }
    val forecastState by produceState<ForecastMapState>(
        initialValue = ForecastMapState.Idle,
        mode,
        location.latitude,
        location.longitude,
        retryKey,
    ) {
        if (mode != MapMode.FORECAST) return@produceState
        val current = value as? ForecastMapState.Content
        if (current?.latitude == location.latitude && current.longitude == location.longitude) {
            return@produceState
        }
        value = ForecastMapState.Loading
        value = try {
            ForecastMapState.Content(
                field = loadPrecipitationField(location),
                latitude = location.latitude,
                longitude = location.longitude,
            )
        } catch (_: IOException) {
            ForecastMapState.Error
        } catch (_: JSONException) {
            ForecastMapState.Error
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(start = 14.dp, top = 16.dp, end = 14.dp, bottom = 4.dp),
    ) {
        Text(stringResource(R.string.radar_title), fontSize = 27.sp, fontWeight = FontWeight.SemiBold)
        Text(
            if (mode == MapMode.OBSERVED) {
                stringResource(R.string.radar_subtitle)
            } else {
                stringResource(R.string.radar_forecast_subtitle, location.name)
            },
            color = Color.White.copy(alpha = 0.62f),
            fontSize = 13.sp,
            modifier = Modifier.padding(top = 3.dp),
        )
        MapTimelineBand(
            mode = mode,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 14.dp),
            onSelect = { mode = it },
        )
        Spacer(Modifier.height(12.dp))
        if (mode == MapMode.OBSERVED) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(RADAR_CARD_ASPECT_RATIO),
                color = Color(0xFF0B1117),
                shape = RoundedCornerShape(26.dp),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.1f)),
            ) {
                ChmiWebScreen(
                    localizedRadarUrl(
                        languageTag,
                        location.latitude,
                        location.longitude,
                        location.isInCzechia(),
                    ),
                )
            }
            Text(
                stringResource(R.string.radar_footer),
                color = Color.White.copy(alpha = 0.5f),
                fontSize = 11.sp,
                modifier = Modifier.padding(start = 5.dp, top = 7.dp),
            )
        } else {
            ForecastMap(
                state = forecastState,
                timezone = timezone,
                units = units,
                onRetry = { retryKey++ },
            )
        }
    }
}

@Composable
private fun MapTimelineBand(
    mode: MapMode,
    modifier: Modifier = Modifier,
    onSelect: (MapMode) -> Unit,
) {
    Surface(
        modifier = modifier.height(52.dp),
        color = Color(0xFF172731),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.10f)),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MapTimelineSegment(
                text = stringResource(R.string.radar_observed_past_2h),
                selected = mode == MapMode.OBSERVED,
                modifier = Modifier.weight(1f),
                onClick = { onSelect(MapMode.OBSERVED) },
            )
            Text(
                text = stringResource(R.string.radar_now),
                color = Color.White.copy(alpha = 0.58f),
                fontSize = 10.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(42.dp),
            )
            MapTimelineSegment(
                text = stringResource(R.string.radar_forecast_next_24h),
                selected = mode == MapMode.FORECAST,
                modifier = Modifier.weight(1f),
                onClick = { onSelect(MapMode.FORECAST) },
            )
        }
    }
}

@Composable
private fun MapTimelineSegment(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxHeight(),
        color = if (selected) Color(0xFF2E6474) else Color.Transparent,
        contentColor = Color.White,
        shape = RoundedCornerShape(18.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(text, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium)
        }
    }
}

@Composable
private fun ForecastMap(
    state: ForecastMapState,
    timezone: String,
    units: WeatherUnitFormatter,
    onRetry: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color(0xFF0B1117),
        shape = RoundedCornerShape(26.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.1f)),
    ) {
        when (state) {
            ForecastMapState.Idle, ForecastMapState.Loading -> Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(430.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = Color(0xFF6DD3EA))
            }
            ForecastMapState.Error -> Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(430.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.radar_forecast_unavailable))
                    TextButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
                }
            }
            is ForecastMapState.Content -> LocalRainField(
                field = state.field,
                timezone = timezone,
                units = units,
                modifier = Modifier.padding(14.dp),
            )
        }
    }
    Text(
        stringResource(R.string.radar_forecast_note),
        color = Color.White.copy(alpha = 0.58f),
        fontSize = 11.sp,
        modifier = Modifier.padding(start = 5.dp, top = 7.dp),
    )
}

private enum class MapMode {
    OBSERVED,
    FORECAST,
}

private sealed interface ForecastMapState {
    data object Idle : ForecastMapState
    data object Loading : ForecastMapState
    data class Content(
        val field: PrecipitationField,
        val latitude: Double,
        val longitude: Double,
    ) : ForecastMapState
    data object Error : ForecastMapState
}

private const val RADAR_CARD_ASPECT_RATIO = 0.9f
