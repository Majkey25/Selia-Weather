package cz.majkey.pocasicesko.ui

import android.os.Build
import android.app.TimePickerDialog
import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Air
import androidx.compose.material.icons.rounded.CalendarToday
import androidx.compose.material.icons.rounded.Umbrella
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import cz.majkey.pocasicesko.R
import cz.majkey.pocasicesko.locale.AppLocale
import cz.majkey.pocasicesko.notification.WeatherAlertCategory
import cz.majkey.pocasicesko.notification.WeatherAlertSettings
import cz.majkey.pocasicesko.notification.BRIEFING_TIME
import cz.majkey.pocasicesko.notification.temperatureDropText
import cz.majkey.pocasicesko.units.MeasurementSystem
import cz.majkey.pocasicesko.units.WeatherUnitFormatter
import kotlin.math.roundToInt
import java.time.LocalTime
import java.time.format.DateTimeFormatter

enum class NotificationSettingsSection(val title: Int) {
    GENERAL(R.string.notifications),
    OFFICIAL(R.string.notification_official),
    TEMPERATURE(R.string.settings_temperature_alerts),
    WIND(R.string.notification_wind),
    UV(R.string.notification_uv),
    TIMING(R.string.settings_alert_timing),
    DELIVERY(R.string.notification_background_delivery),
    MORNING(R.string.daily_briefing),
    RAIN(R.string.notification_rain),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationSettingsSheet(
    settings: WeatherAlertSettings,
    measurementSystem: MeasurementSystem,
    dailyBriefingEnabled: Boolean,
    notificationsAllowed: Boolean,
    onSettingsChange: (WeatherAlertSettings) -> Unit,
    onDailyBriefingChange: (Boolean) -> Unit,
    onRequestPermission: () -> Unit,
    onChannelSettings: (String) -> Unit,
    onBackgroundSettings: () -> Unit,
    onDismiss: () -> Unit,
    blockedChannels: Set<String> = emptySet(),
    followDeviceLocation: Boolean = false,
    initialSection: NotificationSettingsSection = NotificationSettingsSection.GENERAL,
    briefingTime: LocalTime = BRIEFING_TIME,
    onBriefingTimeChange: (LocalTime) -> Unit = {},
    exactAlarmsAllowed: Boolean = true,
    onRequestExactAlarms: () -> Unit = {},
) {
    val context = LocalContext.current
    val units = WeatherUnitFormatter(measurementSystem, AppLocale.locale(context))
    val timeText = briefingTime.format(DateTimeFormatter.ofPattern(
        if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm a", AppLocale.locale(context)))
    val current = settings.normalized()
    val temperatureCategories = listOf(WeatherAlertCategory.COLD, WeatherAlertCategory.DROP, WeatherAlertCategory.HEAT)
    val enabledTemperatureCategories = temperatureCategories.filter(current::isEnabled)
    var section by rememberSaveable(initialSection) { mutableStateOf(initialSection) }
    val rootListState = rememberLazyListState()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        properties = ModalBottomSheetProperties(shouldDismissOnBackPress = section == initialSection),
    ) {
        SheetSystemBars()
        BackHandler(enabled = section != initialSection) { section = initialSection }
        SheetHeader(stringResource(section.title), onBack = {
            if (section == initialSection) onDismiss() else section = initialSection
        })
        key(section) {
            LazyColumn(
                state = if (section == initialSection) rootListState else rememberLazyListState(),
                modifier = Modifier.weight(1f, fill = false).fillMaxWidth()
                    .navigationBarsPadding().padding(bottom = 24.dp),
            ) {
                item {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.notification_background_consent_title)) },
                        supportingContent = { Text(stringResource(if (followDeviceLocation)
                            R.string.location_follow_background_help else R.string.notification_background_disclosure)) },
                        trailingContent = { Switch(checked = current.backgroundAlertsAllowed, onCheckedChange = null) },
                        modifier = Modifier.fillMaxWidth().toggleable(value = current.backgroundAlertsAllowed,
                            role = Role.Switch, onValueChange = { onSettingsChange(current.copy(backgroundAlertsAllowed = it)) }),
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }
                if (current.backgroundAlertsAllowed && !notificationsAllowed) {
                    item {
                        Text(stringResource(if (followDeviceLocation) R.string.location_follow_notifications_blocked
                            else R.string.notification_permission_summary),
                            modifier = Modifier.padding(horizontal = 20.dp))
                        Button(onClick = onRequestPermission,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                            Text(stringResource(R.string.notification_enable))
                        }
                    }
                }
                when (section) {
                    NotificationSettingsSection.GENERAL -> {
                        item {
                            SettingsCategoryRow(R.string.notification_background_delivery, Icons.Rounded.Sync) {
                                section = NotificationSettingsSection.DELIVERY
                            }
                        }
                        item {
                            SettingsCategoryRow(R.string.daily_briefing, Icons.Rounded.CalendarToday,
                                when {
                                    "daily_weather_briefing" in blockedChannels -> stringResource(R.string.notification_channel_blocked)
                                    dailyBriefingEnabled -> stringResource(R.string.notification_morning_at, timeText)
                                    else -> stringResource(R.string.settings_off)
                                }) { section = NotificationSettingsSection.MORNING }
                        }
                        item {
                            SettingsCategoryRow(R.string.notification_official, Icons.Rounded.WarningAmber,
                                alertStatus(current.officialWarningsEnabled,
                                    WeatherAlertCategory.OFFICIAL.channelId in blockedChannels)) {
                                section = NotificationSettingsSection.OFFICIAL
                            }
                        }
                        item {
                            SettingsCategoryRow(R.string.notification_rain, Icons.Rounded.Umbrella,
                                when {
                                    WeatherAlertCategory.RAIN.channelId in blockedChannels -> stringResource(R.string.notification_channel_blocked)
                                    current.rainEnabled -> stringResource(R.string.notification_rain_lead, current.rainLeadHours)
                                    else -> stringResource(R.string.settings_off)
                                }) { section = NotificationSettingsSection.RAIN }
                        }
                        item {
                            SettingsCategoryRow(R.string.settings_temperature_alerts, Icons.Rounded.Thermostat,
                                alertStatus(enabledTemperatureCategories.isNotEmpty(), enabledTemperatureCategories.isNotEmpty() &&
                                    enabledTemperatureCategories.all { it.channelId in blockedChannels })) {
                                section = NotificationSettingsSection.TEMPERATURE
                            }
                        }
                        item {
                            SettingsCategoryRow(R.string.notification_wind, Icons.Rounded.Air,
                                alertStatus(current.windEnabled, WeatherAlertCategory.WIND.channelId in blockedChannels)) {
                                section = NotificationSettingsSection.WIND
                            }
                        }
                        item {
                            SettingsCategoryRow(R.string.notification_uv, Icons.Rounded.WbSunny,
                                alertStatus(current.uvEnabled, WeatherAlertCategory.UV.channelId in blockedChannels)) {
                                section = NotificationSettingsSection.UV
                            }
                        }
                        item {
                            SettingsCategoryRow(R.string.settings_alert_timing, Icons.Rounded.Schedule,
                                stringResource(R.string.notification_look_ahead, current.lookAheadHours)) {
                                section = NotificationSettingsSection.TIMING
                            }
                        }
                        item {
                            Text(stringResource(R.string.notification_settings_summary),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                        }
                    }
                    NotificationSettingsSection.DELIVERY -> item {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                            Text(stringResource(if (followDeviceLocation) R.string.location_follow_background_help
                                else R.string.notification_background_help))
                            if (Build.MANUFACTURER.equals("huawei", ignoreCase = true) ||
                                Build.MANUFACTURER.equals("honor", ignoreCase = true)) {
                                Text(stringResource(R.string.notification_huawei_help), Modifier.padding(top = 16.dp))
                            }
                            Button(onClick = onBackgroundSettings, modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
                                Text(stringResource(R.string.open_app_settings))
                            }
                            ExactAlarmSettings(exactAlarmsAllowed, onRequestExactAlarms)
                        }
                    }
                    NotificationSettingsSection.MORNING -> item {
                        NotificationToggle(title = stringResource(R.string.daily_briefing),
                            summary = stringResource(R.string.notification_morning_summary), enabled = dailyBriefingEnabled,
                            onChange = onDailyBriefingChange,
                            onChannelSettings = { onChannelSettings("daily_weather_briefing") },
                            systemBlocked = "daily_weather_briefing" in blockedChannels)
                        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                            Button(onClick = {
                                TimePickerDialog(context, { _, hour, minute -> onBriefingTimeChange(LocalTime.of(hour, minute)) },
                                    briefingTime.hour, briefingTime.minute, DateFormat.is24HourFormat(context)).show()
                            }, modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.notification_morning_at, timeText))
                            }
                            Text(stringResource(R.string.notification_morning_expiry), Modifier.padding(top = 12.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            ExactAlarmSettings(exactAlarmsAllowed, onRequestExactAlarms)
                        }
                    }
                    NotificationSettingsSection.TIMING -> item {
                        NotificationThreshold(
                            stringResource(R.string.notification_look_ahead, current.lookAheadHours),
                            current.lookAheadHours.toDouble(), 1f..12f,
                        ) { onSettingsChange(current.copy(lookAheadHours = it.roundToInt())) }
                    }
                    else -> items(
                        when (section) {
                            NotificationSettingsSection.OFFICIAL -> listOf(WeatherAlertCategory.OFFICIAL)
                            NotificationSettingsSection.TEMPERATURE -> temperatureCategories
                            NotificationSettingsSection.WIND -> listOf(WeatherAlertCategory.WIND)
                            NotificationSettingsSection.UV -> listOf(WeatherAlertCategory.UV)
                            NotificationSettingsSection.RAIN -> listOf(WeatherAlertCategory.RAIN)
                            else -> emptyList()
                        },
                        key = { it.name },
                    ) { category ->
                        AlertCategorySettings(category, current, units, measurementSystem,
                            blockedChannels, onSettingsChange, onChannelSettings)
                    }
                }
            }
        }
    }
}

@Composable
private fun alertStatus(enabled: Boolean, blocked: Boolean): String = stringResource(
    if (blocked) R.string.notification_channel_blocked else if (enabled) R.string.settings_on else R.string.settings_off,
)

@Composable
private fun AlertCategorySettings(
    category: WeatherAlertCategory,
    current: WeatherAlertSettings,
    units: WeatherUnitFormatter,
    measurementSystem: MeasurementSystem,
    blockedChannels: Set<String>,
    onSettingsChange: (WeatherAlertSettings) -> Unit,
    onChannelSettings: (String) -> Unit,
) {
    NotificationToggle(
        title = stringResource(category.labelResource),
        summary = stringResource(category.summaryResource()),
        enabled = current.isEnabled(category),
        onChange = { onSettingsChange(current.withEnabled(category, it)) },
        onChannelSettings = { onChannelSettings(category.channelId) },
        systemBlocked = category.channelId in blockedChannels,
    )
    if (current.isEnabled(category)) {
        when (category) {
            WeatherAlertCategory.COLD -> NotificationThreshold(
                stringResource(R.string.notification_at_or_below, units.temperature(current.coldCelsius)),
                current.coldCelsius, -30f..15f,
            ) { onSettingsChange(current.copy(coldCelsius = it)) }
            WeatherAlertCategory.DROP -> NotificationThreshold(
                stringResource(R.string.notification_drop_threshold, temperatureDropText(current.dropCelsius, measurementSystem)),
                current.dropCelsius, 3f..20f,
            ) { onSettingsChange(current.copy(dropCelsius = it)) }
            WeatherAlertCategory.HEAT -> NotificationThreshold(
                stringResource(R.string.notification_at_or_above, units.temperature(current.heatCelsius)),
                current.heatCelsius, 20f..45f,
            ) { onSettingsChange(current.copy(heatCelsius = it)) }
            WeatherAlertCategory.WIND -> NotificationThreshold(
                stringResource(R.string.notification_at_or_above, units.windSpeed(current.windKmh)),
                current.windKmh, 20f..120f,
            ) { onSettingsChange(current.copy(windKmh = it)) }
            WeatherAlertCategory.UV -> NotificationThreshold(
                stringResource(R.string.notification_uv_threshold, current.uvIndex.roundToInt()),
                current.uvIndex, 3f..11f,
            ) { onSettingsChange(current.copy(uvIndex = it)) }
            WeatherAlertCategory.RAIN -> {
                NotificationThreshold(stringResource(R.string.notification_rain_lead, current.rainLeadHours),
                    current.rainLeadHours.toDouble(), 1f..12f) { onSettingsChange(current.copy(rainLeadHours = it.roundToInt())) }
                NotificationThreshold(stringResource(R.string.notification_rain_probability, current.rainProbabilityPercent),
                    current.rainProbabilityPercent.toDouble(), 10f..90f) {
                    onSettingsChange(current.copy(rainProbabilityPercent = it.roundToInt()))
                }
            }
            WeatherAlertCategory.OFFICIAL -> Unit
        }
    }
}

@Composable
private fun ExactAlarmSettings(allowed: Boolean, onRequest: () -> Unit) {
    if (Build.VERSION.SDK_INT >= 31) {
        Text(stringResource(if (allowed) R.string.notification_exact_allowed else R.string.notification_exact_help),
            Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!allowed) Button(onClick = onRequest, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Text(stringResource(R.string.notification_exact_enable))
        }
    }
}

@Composable
private fun NotificationToggle(
    title: String,
    summary: String,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
    onChannelSettings: () -> Unit,
    systemBlocked: Boolean,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = {
            Text(if (systemBlocked) "$summary\n${stringResource(R.string.notification_channel_blocked)}" else summary)
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = enabled, onCheckedChange = null)
                IconButton(onClick = onChannelSettings) {
                    Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = stringResource(R.string.notification_channel_settings, title))
                }
            }
        },
        modifier = Modifier.fillMaxWidth().toggleable(value = enabled, role = Role.Switch, onValueChange = onChange),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

@Composable
private fun NotificationThreshold(label: String, value: Double, range: ClosedFloatingPointRange<Float>, onChange: (Double) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)) {
        Text(label)
        Slider(value = value.toFloat(), onValueChange = { onChange(it.roundToInt().toDouble()) },
            valueRange = range, steps = (range.endInclusive - range.start).roundToInt() - 1,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = label })
    }
}

private fun WeatherAlertCategory.summaryResource(): Int = when (this) {
    WeatherAlertCategory.RAIN -> R.string.notification_rain_summary
    WeatherAlertCategory.COLD -> R.string.notification_cold_summary
    WeatherAlertCategory.DROP -> R.string.notification_drop_summary
    WeatherAlertCategory.HEAT -> R.string.notification_heat_summary
    WeatherAlertCategory.WIND -> R.string.notification_wind_summary
    WeatherAlertCategory.UV -> R.string.notification_uv_summary
    WeatherAlertCategory.OFFICIAL -> R.string.notification_official_summary
}
