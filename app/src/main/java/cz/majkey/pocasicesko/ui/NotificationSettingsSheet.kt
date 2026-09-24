package cz.majkey.pocasicesko.ui

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
import androidx.compose.material.icons.rounded.Air
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Settings
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
import cz.majkey.pocasicesko.notification.temperatureDropText
import cz.majkey.pocasicesko.units.MeasurementSystem
import cz.majkey.pocasicesko.units.WeatherUnitFormatter
import kotlin.math.roundToInt

enum class NotificationSettingsSection(val title: Int) {
    GENERAL(R.string.notifications),
    OFFICIAL(R.string.notification_official),
    TEMPERATURE(R.string.settings_temperature_alerts),
    WIND(R.string.notification_wind),
    UV(R.string.notification_uv),
    TIMING(R.string.settings_alert_timing),
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
    onDismiss: () -> Unit,
    blockedChannels: Set<String> = emptySet(),
    initialSection: NotificationSettingsSection = NotificationSettingsSection.GENERAL,
) {
    val units = WeatherUnitFormatter(measurementSystem, AppLocale.locale(LocalContext.current))
    val current = settings.normalized()
    var section by rememberSaveable(initialSection) { mutableStateOf(initialSection) }
    val rootListState = rememberLazyListState()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        properties = ModalBottomSheetProperties(shouldDismissOnBackPress = section == initialSection),
    ) {
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
                if (!notificationsAllowed) {
                    item {
                        Text(stringResource(R.string.notification_permission_summary),
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
                            NotificationToggle(
                                title = stringResource(R.string.daily_briefing),
                                summary = stringResource(R.string.daily_briefing_summary),
                                enabled = dailyBriefingEnabled,
                                onChange = onDailyBriefingChange,
                                onChannelSettings = { onChannelSettings("daily_weather_briefing") },
                                systemBlocked = "daily_weather_briefing" in blockedChannels,
                            )
                        }
                        item {
                            SettingsCategoryRow(R.string.notification_official, Icons.Rounded.WarningAmber,
                                alertStatus(current.officialWarningsEnabled,
                                    WeatherAlertCategory.OFFICIAL.channelId in blockedChannels)) {
                                section = NotificationSettingsSection.OFFICIAL
                            }
                        }
                        item {
                            AlertCategorySettings(WeatherAlertCategory.RAIN, current, units, measurementSystem,
                                blockedChannels, onSettingsChange, onChannelSettings)
                        }
                        item {
                            SettingsCategoryRow(R.string.settings_temperature_alerts, Icons.Rounded.Thermostat,
                                stringResource(R.string.settings_temperature_summary)) {
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
                    NotificationSettingsSection.TIMING -> item {
                        NotificationThreshold(
                            stringResource(R.string.notification_look_ahead, current.lookAheadHours),
                            current.lookAheadHours.toDouble(), 1f..12f,
                        ) { onSettingsChange(current.copy(lookAheadHours = it.roundToInt())) }
                    }
                    else -> items(
                        when (section) {
                            NotificationSettingsSection.OFFICIAL -> listOf(WeatherAlertCategory.OFFICIAL)
                            NotificationSettingsSection.TEMPERATURE -> listOf(
                                WeatherAlertCategory.COLD, WeatherAlertCategory.DROP, WeatherAlertCategory.HEAT)
                            NotificationSettingsSection.WIND -> listOf(WeatherAlertCategory.WIND)
                            NotificationSettingsSection.UV -> listOf(WeatherAlertCategory.UV)
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
            WeatherAlertCategory.RAIN, WeatherAlertCategory.OFFICIAL -> Unit
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
                    Icon(Icons.Rounded.Settings, contentDescription = stringResource(R.string.notification_channel_settings, title))
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
