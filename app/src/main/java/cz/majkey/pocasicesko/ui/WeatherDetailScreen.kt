package cz.majkey.pocasicesko.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cz.majkey.pocasicesko.R
import cz.majkey.pocasicesko.astro.MoonCalculator
import cz.majkey.pocasicesko.astro.MoonDetails
import cz.majkey.pocasicesko.astro.MoonPhaseKey
import cz.majkey.pocasicesko.data.CzechLocation
import cz.majkey.pocasicesko.data.WeatherSnapshot
import cz.majkey.pocasicesko.units.WeatherUnitFormatter
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WeatherDetailSheet(
    snapshot: WeatherSnapshot,
    location: CzechLocation,
    units: WeatherUnitFormatter,
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
    val today = snapshot.daily.firstOrNull()
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
                        stringResource(R.string.surface_temperature),
                        snapshot.current.surfaceTemperature?.let(units::temperature),
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
                    OptionalDetailRow(
                        stringResource(R.string.et0_evapotranspiration),
                        today?.et0?.let(units::precipitation),
                    )
                }
            }
            item {
                MoonSection(moon, locale)
            }
        }
    }
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
